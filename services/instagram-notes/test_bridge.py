import json
from http.client import HTTPConnection
import sqlite3
import threading
import unittest
from types import SimpleNamespace

from bridge import Bridge, HTTPServer, handler

TRACK = {"title": "Şarkı", "display_artist": "Artist", "audio_asset_id": 12, "audio_cluster_id": 34}
SONG = {"title": " Şarkı ", "artist": "ARTIST"}


class Client:
    user_id = 21

    def __init__(self):
        self.search = [TRACK]
        self.notes = []
        self.writes = []

    def notes_music_browser(self):
        return {"alacorn_session_id": "browser", "items": []}

    def music_search_v2(self, query, product):
        assert product == "music_in_feed"
        return {"items": [{"track": t} for t in self.search]}

    def get_notes(self):
        return self.notes

    def create_music_note(self, **kwargs):
        self.writes.append(kwargs)
        note = SimpleNamespace(id="42", user_id="21", audience=1, note_style=1)
        self.notes = [note]
        return note


class BridgeTest(unittest.TestCase):
    def setUp(self):
        self.client = Client()
        self.db = sqlite3.connect(":memory:", isolation_level=None, check_same_thread=False)
        self.now = 1000
        self.bridge = Bridge(self.client, 1, self.db, lambda: self.now)
        self.addCleanup(self.db.close)

    def resolve(self):
        return self.bridge.handle("/resolve", SONG)

    def publish(self, match):
        return self.bridge.handle("/publish", {"ticket": match["ticket"]})["result"]

    def test_music_readback_and_duplicate_on_restart(self):
        ticket = self.resolve()
        self.assertFalse(self.client.writes)
        self.assertEqual(self.publish(ticket), "recorded")
        self.assertEqual(self.client.writes, [dict(track=TRACK, audience=1, alacorn_session_id="browser")])
        self.assertEqual(self.publish(ticket), "expired")
        self.bridge = Bridge(self.client, 1, self.db, lambda: self.now)
        self.assertEqual(self.resolve()["result"], "cooldown")
        self.now += 121
        self.assertEqual(self.publish(self.resolve()), "same")
        self.assertEqual(len(self.client.writes), 1)

    def test_mismatch_and_ambiguity(self):
        for rows in ([], [dict(TRACK, display_artist="Cover")], [dict(TRACK, title="Şarkı (live)")],
                     [TRACK, dict(TRACK, audio_asset_id=56)], [dict(TRACK, audio_cluster_id="")]):
            self.client.search = rows
            self.assertEqual(self.resolve()["result"], "no_match")
            self.now += 121
        self.assertFalse(self.client.writes)

    def test_notes_browser_playlist_and_search_duplicate(self):
        self.client.notes_music_browser = lambda: {"alacorn_session_id": "b", "items": [
            {"playlist": {"preview_items": [{"track": TRACK}]}}]}
        self.assertEqual(self.resolve()["result"], "matched")

    def test_cannot_overwrite_manual_note(self):
        self.client.notes = [SimpleNamespace(user_id="21", id="100")]
        self.assertEqual(self.publish(self.resolve()), "existing")
        self.assertFalse(self.client.writes)

    def test_expired_match_cannot_publish(self):
        ticket = self.resolve()
        self.now += 61
        self.assertEqual(self.publish(ticket), "expired")
        self.assertFalse(self.client.writes)

    def test_failed_readback_pauses(self):
        self.client.get_notes = lambda: []
        self.assertEqual(self.publish(self.resolve()), "unconfirmed")
        self.assertEqual(self.resolve()["result"], "blocked")
        self.assertEqual(len(self.client.writes), 1)

    def test_http_pairing_and_publish_protocol(self):
        server = HTTPServer(("127.0.0.1", 0), handler(self.bridge, "private-key"))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            def request(path, data, key="private-key"):
                conn = HTTPConnection(*server.server_address, timeout=2)
                try:
                    conn.request("POST", path, json.dumps(data), {"Authorization": "Bearer " + key})
                    response = conn.getresponse()
                    return response.status, json.load(response)
                finally:
                    conn.close()
            self.assertEqual(request("/resolve", SONG, "wrong")[0], 401)
            self.assertEqual(request("/check", {}), (200, {"result": "ready"}))
            status, match = request("/resolve", SONG)
            self.assertEqual(status, 200)
            self.assertEqual(request("/publish", {"ticket": match["ticket"]}), (200, {"result": "recorded"}))
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    unittest.main()
