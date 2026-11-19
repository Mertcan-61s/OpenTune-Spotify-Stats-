"""Exercise the pinned SDK's own encoding and response parsing without contacting Instagram."""
import json
import sqlite3
import unittest
from unittest.mock import Mock

from instagrapi import Client
from instagrapi.mixins.note import NoteAudience

from bridge import Bridge


class SdkContractTest(unittest.TestCase):
    def test_attaches_selected_audio_and_accepts_matching_inbox_readback(self):
        client = Client(session_retry_total=0, with_challenge_flow=False)
        client.authorization_data = {"ds_user_id": "21"}
        track = {"title": "Şarkı", "display_artist": "Artist", "audio_asset_id": "12", "audio_cluster_id": "34"}
        client.private_request = Mock(side_effect=[
            {"status": "ok", "items": [], "alacorn_session_id": "browser"}, {"items": [{"track": track}]}
        ])
        note = {"note_id": "42", "author_id": "21", "author": {"pk": "21", "username": "test"},
                "note_style": 1, "audience": 1}
        client.private_graphql_request = Mock(return_value={
            "data": {"create": {"success": True, "inbox_tray_item": {"note_dict": note}}}})
        client.private_graphql_query_request = Mock(side_effect=[
            {"data": {"tray": {"inbox_tray_items": []}}},
            {"data": {"tray": {"inbox_tray_items": [{"note_dict": note}]}}}
        ])
        db = sqlite3.connect(":memory:", isolation_level=None)
        self.addCleanup(db.close)
        bridge = Bridge(client, NoteAudience.CLOSE_FRIENDS, db)
        match = bridge.resolve({"title": "Şarkı", "artist": "Artist"})
        self.assertEqual(bridge.publish(match), {"result": "recorded"})
        packet = client.private_graphql_request.call_args.args[0]
        request = json.loads(packet["variables"])["request"]
        self.assertEqual(request["audience"], int(NoteAudience.CLOSE_FRIENDS))
        params = request["additional_params"]["note_create_params"]
        self.assertEqual(params["note_style"], 1)
        music = params["note_create_info"]["music_note_create_info"]
        self.assertEqual((music["audio_asset_id"], music["audio_cluster_id"]), ("12", "34"))
