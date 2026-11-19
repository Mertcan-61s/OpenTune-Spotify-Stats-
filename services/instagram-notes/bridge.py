"""Single-account OpenTune Notes bridge. Run 'login' once, then 'serve'."""
import argparse
from getpass import getpass
import hmac
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import math
import os
from pathlib import Path
import secrets
import sqlite3
import sys
import time
import unicodedata

STATE = Path.home() / ".opentune-notes"
COOLDOWN = 120
TICKET_TTL = 60


def normalized(value):
    return " ".join(unicodedata.normalize("NFKC", value).casefold().split())


def candidates(response):
    for item in response.get("items", []):
        if isinstance(item, dict):
            yield item.get("track")
            for entry in (item.get("playlist") or {}).get("preview_items", []):
                if isinstance(entry, dict):
                    yield entry.get("track")


def exact_match(tracks, title, artist):
    matches = {}
    for track in tracks:
        if not isinstance(track, dict):
            continue
        if (normalized(track.get("title") or ""), normalized(track.get("display_artist") or "")) != (title, artist):
            continue
        asset = str(track.get("audio_asset_id") or track.get("id") or "")
        cluster = str(track.get("audio_cluster_id") or "")
        if asset.isdecimal() and cluster.isdecimal():
            matches[asset, cluster] = track
    return next(iter(matches.values())) if len(matches) == 1 else None


class Bridge:
    def __init__(self, client, audience, db, clock=time.time):
        self.client, self.audience, self.db, self.clock = client, audience, db, clock
        self.pending = None
        self.blocked = False
        self.db.execute("CREATE TABLE IF NOT EXISTS state (account TEXT PRIMARY KEY, note TEXT, next REAL)")
        self.db.execute("CREATE TABLE IF NOT EXISTS recording (account TEXT PRIMARY KEY, asset TEXT)")
        self.account = str(client.user_id)
        if not self.account.isdecimal():
            raise ValueError("A signed-in session is required")
        self.db.execute("INSERT OR IGNORE INTO state VALUES (?, '', 0)", (self.account,))

    def handle(self, path, body):
        if self.blocked:
            return {"result": "blocked"}
        if path == "/check":
            return {"result": "ready"}
        if path == "/resolve":
            return self.resolve(body)
        if path == "/publish":
            return self.publish(body)
        raise ValueError("unknown route")

    def resolve(self, body):
        title, artist = body.get("title"), body.get("artist")
        if not all(isinstance(x, str) and x.strip() and len(x) <= 512 for x in (title, artist)):
            raise ValueError("invalid track")
        self.pending = None
        _, next_time = self.state()
        if self.clock() < next_time:
            return {"result": "cooldown", "retry_after": max(1, math.ceil(next_time - self.clock()))}
        self.db.execute("UPDATE state SET next=? WHERE account=?", (self.clock() + COOLDOWN, self.account))
        music = self.client.notes_music_browser()
        session = music.get("alacorn_session_id")
        if not session:
            return {"result": "no_match"}
        search = self.client.music_search_v2(f"{title} {artist}", product="music_in_feed")
        tracks = [*candidates(search), *candidates(music)]
        track = exact_match(tracks, normalized(title), normalized(artist))
        if not track:
            return {"result": "no_match"}
        ticket = secrets.token_urlsafe(24)
        self.pending = (ticket, self.clock() + TICKET_TTL, track, session)
        return {"result": "matched", "ticket": ticket}

    def state(self):
        return self.db.execute("SELECT note, next FROM state WHERE account=?", (self.account,)).fetchone()

    def own_notes(self):
        return [note for note in self.client.get_notes() if str(note.user_id) == self.account]

    def publish(self, body):
        pending, self.pending = self.pending, None
        if not pending or body.get("ticket") != pending[0] or self.clock() >= pending[1]:
            return {"result": "expired"}
        _, _, track, session = pending
        previous, _ = self.state()
        existing = self.own_notes()
        if any(note.id != previous for note in existing):
            return {"result": "existing"}
        asset = str(track.get("audio_asset_id") or track["id"])
        saved = self.db.execute("SELECT asset FROM recording WHERE account=?", (self.account,)).fetchone()
        if existing and saved == (asset,):
            return {"result": "same"}
        # An exception after dispatch has an ambiguous outcome. Never replay this POST.
        try:
            note = self.client.create_music_note(track=track, audience=self.audience, alacorn_session_id=session)
            if not str(note.id).isdecimal() or str(note.user_id) != self.account:
                self.blocked = True
                return {"result": "unconfirmed"}
            self.db.execute("UPDATE state SET note=? WHERE account=?", (note.id, self.account))
            self.db.execute("INSERT OR REPLACE INTO recording VALUES (?, ?)", (self.account, asset))
            if any(n.id == note.id and n.note_style == 1 and n.audience == int(self.audience) for n in self.own_notes()):
                return {"result": "recorded"}
        except Exception:
            pass
        self.blocked = True
        return {"result": "unconfirmed"}


def handler(bridge, key):
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            self.request.settimeout(10)
            super().setup()

        def log_message(self, *_):
            pass  # Keep credentials, song titles and request bodies out of logs.

        def reply(self, status, data):
            payload = (json.dumps(data) + "\n").encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(payload)

        def do_POST(self):
            if not hmac.compare_digest(self.headers.get("Authorization", "").encode(), b"Bearer " + key.encode()):
                return self.reply(401, {})
            if self.path not in ("/check", "/resolve", "/publish"):
                return self.reply(404, {})
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 8192:
                    return self.reply(400, {})
                body = json.loads(self.rfile.read(length))
                if not isinstance(body, dict):
                    return self.reply(400, {})
            except (ValueError, UnicodeError):
                return self.reply(400, {})
            try:
                result = bridge.handle(self.path, body)
            except Exception as exc:
                # Login challenges and upstream errors require operator inspection.
                bridge.blocked = True
                print("Notes bridge paused:", type(exc).__name__, file=sys.stderr, flush=True)
                result = {"result": "blocked"}
            self.reply(200, result)

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["login", "serve"])
    parser.add_argument("--port", default=8765, type=int)
    args = parser.parse_args()
    from instagrapi import Client
    from instagrapi.mixins.note import NoteAudience
    import fcntl

    os.umask(0o077)
    STATE.mkdir(mode=0o700, exist_ok=True)
    lock = (STATE / "instance.lock").open("a")
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    client = Client(session_retry_total=0, with_challenge_flow=args.action == "login")
    session = STATE / "session.json"
    key_file = STATE / "pairing.key"
    if args.action == "login":
        client.login(input("Instagram test account: ").strip(), getpass("Password: "),
                     verification_code=getpass("Authenticator code (if enabled): ").strip())
        staged = session.with_suffix(".new")
        client.dump_settings(staged)
        staged.replace(session)
        key_file.write_text(secrets.token_urlsafe(32))
        print("Pairing key (enter once in OpenTune):", key_file.read_text())
        return
    client.load_settings(session)
    # Private requests use the saved session. Interactive challenges belong to 'login'.
    db = sqlite3.connect(STATE / "notes.db", isolation_level=None)
    bridge = Bridge(client, NoteAudience.CLOSE_FRIENDS, db)
    with HTTPServer(("127.0.0.1", args.port), handler(bridge, key_file.read_text().strip())) as server:
        print(f"Notes bridge listening on 127.0.0.1:{args.port}", flush=True)
        server.serve_forever()


if __name__ == "__main__":
    main()
