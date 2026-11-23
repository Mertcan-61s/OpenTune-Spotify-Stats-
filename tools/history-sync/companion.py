"""OpenTune LAN history companion. Start OpenTune normally after the first pairing."""
from contextlib import closing
import datetime
import hashlib
import hmac
import ipaddress
import json
import os
from pathlib import Path
import secrets
import socket
import sqlite3
import ssl
import sys
import tempfile
import threading
import tkinter as tk
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlencode

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID
from qrcodegen import QrCode

ROOT = Path.home() / ".opentune"
DB = ROOT / "library.db"
PAIR = ROOT / "history-sync.json"
PORT = 43187
PAGE = 400


def identity():
    if PAIR.exists():
        return json.loads(PAIR.read_text())
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "OpenTune history")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
            .public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=3650)).sign(key, hashes.SHA256()))
    config = dict(token=secrets.token_hex(32), pin=cert.fingerprint(hashes.SHA256()).hex(),
                  cert=cert.public_bytes(serialization.Encoding.PEM).decode(),
                  key=key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                        serialization.NoEncryption()).decode())
    ROOT.mkdir(exist_ok=True)
    tmp = PAIR.with_suffix(".tmp")
    tmp.write_text(json.dumps(config))
    tmp.replace(PAIR)
    return config


def exchange(path, request):
    rows, cursor = request["rows"], request["cursor"]
    if not isinstance(rows, list) or len(rows) > PAGE or type(cursor) is not int or cursor < 0:
        raise ValueError("invalid page")
    if not path.exists():
        raise RuntimeError("OpenTune must initialize its library first")
    with closing(sqlite3.connect(path, timeout=5)) as db, db:
        db.execute("BEGIN IMMEDIATE")
        db.execute("CREATE INDEX IF NOT EXISTS sync_plays_date ON plays(id, at)")
        db.execute("CREATE TABLE IF NOT EXISTS sync_received(id TEXT, at INTEGER, PRIMARY KEY(id,at))")
        for row in rows:
            song, at = row["videoId"], row["playedAt"]
            if not isinstance(song, str) or not song or type(at) is not int or at <= 0:
                raise ValueError("invalid listen")
            if db.execute("SELECT 1 FROM plays WHERE id=? AND at=?", (song, at)).fetchone():
                continue
            db.execute("INSERT OR IGNORE INTO songs(id,title,artist,cover,length) VALUES(?,?,?,?,?)",
                       (song, row["title"], row["artist"], row.get("thumbnailUrl"), row.get("durationText")))
            db.execute("INSERT INTO plays(id,at) VALUES(?,?)", (song, at))
            db.execute("INSERT OR IGNORE INTO sync_received VALUES(?,?)", (song, at))
        page = db.execute("""SELECT p.rowid,p.id,p.at,s.title,s.artist,s.cover,s.length
            FROM plays p JOIN songs s ON s.id=p.id WHERE p.rowid>? AND NOT EXISTS (SELECT 1 FROM sync_received r WHERE r.id=p.id AND r.at=p.at) ORDER BY p.rowid LIMIT ?""",
                          (cursor, PAGE)).fetchall()
        return {"cursor": page[-1][0] if page else cursor, "more": len(page) == PAGE,
                "rows": [dict(videoId=i, playedAt=t, title=n, artist=a, thumbnailUrl=c, durationText=d)
                         for _, i, t, n, a, c, d in page]}


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/history" or not hmac.compare_digest(
                self.headers.get("Authorization", ""), "Bearer " + self.server.token):
            self.send_error(403)
            return
        try:
            size = int(self.headers["Content-Length"])
            if not 0 < size <= 4_000_000:
                raise ValueError("bad size")
            result = exchange(DB, json.loads(self.rfile.read(size)))
            body = json.dumps(result, ensure_ascii=False).encode()
        except (ValueError, TypeError, KeyError, OverflowError):
            self.send_error(400)
            return
        except (sqlite3.Error, RuntimeError):
            self.send_error(503)
            return
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class Server(HTTPServer):
    def get_request(self):
        conn, addr = super().get_request()
        try:
            conn.settimeout(5)
            return self.tls.wrap_socket(conn, server_side=True), addr
        except Exception:
            conn.close()
            raise


def show_pair(config):
    hosts = [x[4][0] for x in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)
             if ipaddress.ip_address(x[4][0]).is_private and not x[4][0].startswith("127.")]
    if not hosts:
        raise RuntimeError("Önce bilgisayarı Wi-Fi ağına bağla.")
    root = tk.Tk()
    root.title("OpenTune · Dinleme kayıtları")
    tk.Label(root, text="Telefonda aynı Wi-Fi ağına bağlan ve bu kodu kamerayla okut.").pack(padx=16, pady=10)
    selected = tk.StringVar(value=hosts[0])
    canvas = tk.Canvas(root, bg="white", highlightthickness=0)
    def draw(*_):
        link = "opentune://history-sync?" + urlencode(dict(host=selected.get(), pin=config["pin"], token=config["token"], name=socket.gethostname()))
        qr = QrCode.encode_text(link, QrCode.Ecc.MEDIUM)
        n = qr.get_size()
        canvas.configure(width=(n+8)*5, height=(n+8)*5)
        canvas.delete("all")
        for y in range(n):
            for x in range(n):
                if qr.get_module(x,y):
                    canvas.create_rectangle((x+4)*5,(y+4)*5,(x+5)*5,(y+5)*5,fill="black",outline="")
    if len(hosts)>1:
        tk.OptionMenu(root, selected, *hosts, command=draw).pack()
    draw()
    canvas.pack()
    tk.Label(root, text="Eşleştirdikten sonra pencereyi kapatabilirsin.").pack(pady=8)
    root.mainloop()


def main():
    from tkinter import messagebox
    try:
        server = Server(("", PORT), Handler)
    except OSError:
        if "--background" not in sys.argv and PAIR.exists():
            show_pair(identity())
        return
    config = identity()
    server.token = config["token"]
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    with tempfile.TemporaryDirectory() as tmp:
        pem = Path(tmp) / "server.pem"
        pem.write_text(config["cert"] + config["key"])
        tls.load_cert_chain(str(pem))
    server.tls = tls
    if "--background" not in sys.argv:
        if getattr(sys, "frozen", False):
            import winreg
            from shutil import copy2
            installed = ROOT / "OpenTune-History-Sync.exe"
            if Path(sys.executable).resolve() != installed.resolve():
                copy2(sys.executable, installed)
            with winreg.CreateKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run") as key:
                winreg.SetValueEx(key, "OpenTuneHistorySync", 0, winreg.REG_SZ, f'"{installed}" --background')
        threading.Thread(target=server.serve_forever, daemon=True).start()
        show_pair(config)
        threading.Event().wait()
    else:
        server.serve_forever()


if __name__ == "__main__":
    main()
