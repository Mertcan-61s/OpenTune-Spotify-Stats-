"""Exercise the bundled trial script in Chromium using intercepted, offline pages."""
import os
from pathlib import Path
import unittest

from playwright.sync_api import sync_playwright

SCRIPT = (Path(__file__).resolve().parents[1] / "app/src/main/assets/instagram/notes-trial.js").read_text()
INBOX = "https://www.instagram.com/direct/inbox/"
NAV = '<nav><a aria-label="Profil" href="/test_side/">Profil</a></nav>'
NOTE = '<div role="dialog" aria-label="Not paylaş"><button>Müziği kaldır</button><div>Şarkı</div><div>Sanatçı</div>'
MUSIC = '<div role="dialog" aria-label="Müzik">'
CLOSE = '<button>Hedef kitle: Yakın Arkadaşlar</button>'
SHARE = '<button id="submit" onclick="window.submitted++">Paylaş</button></div>'


class Trial(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch(executable_path=os.getenv("CHROMIUM_BIN") or None)
        cls.addClassCleanup(cls.playwright.stop)
        cls.addClassCleanup(cls.browser.close)

    def setUp(self):
        self.page = self.browser.new_page()
        self.addCleanup(self.page.close)
        self.page.route("**/*", lambda route: route.fulfill(content_type="text/html", body="<html></html>"))
        self.page.goto(INBOX)

    def html(self, content):
        self.page.set_content(NAV + content)
        self.page.evaluate("window.submitted = 0")

    def step(self, step, handle="test_side"):
        return self.page.evaluate("args => (" + SCRIPT + ")(...args)", [step, handle, "Şarkı", "Sanatçı"])

    def test_catalog_exact_selection_and_two_step_submission(self):
        self.html('<button>Yeni not</button>')
        self.assertEqual(self.step("open"), "music")
        self.html(NOTE + '<button>Müzik ekle</button></div>')
        self.assertEqual(self.step("music"), "search")
        self.html(MUSIC + '<input type="search" aria-label="Ara"><div role="option">Şarkı<br>Sanatçı</div></div>')
        self.assertEqual(self.step("search"), "select")
        self.assertEqual(self.page.locator("input").input_value(), "Şarkı Sanatçı")
        self.assertEqual(self.step("select"), "review")
        self.html(NOTE + CLOSE + SHARE)
        self.assertEqual(self.step("review"), "ready")
        self.assertEqual(self.step("composer"), "ready")
        self.assertEqual(self.page.evaluate("window.submitted"), 0)
        self.assertEqual(self.step("submit"), "sent")
        self.assertEqual(self.page.evaluate("window.submitted"), 1)

    def test_unselected_or_changed_audience_and_recording(self):
        for button in ['<button>Yakın Arkadaşlar</button>', '<button>Hedef kitle: Arkadaşlar</button>']:
            self.html(NOTE + button + SHARE)
            self.assertEqual(self.step("submit"), "audience")
            self.assertEqual(self.page.evaluate("window.submitted"), 0)
        self.html(NOTE.replace("Şarkı", "Şarkı (Live)") + CLOSE + SHARE)
        self.assertEqual(self.step("submit"), "recording")

    def test_text_without_audio_attachment_never_submits(self):
        self.html(NOTE.replace("<button>Müziği kaldır</button>", "") + CLOSE + SHARE)
        self.assertEqual(self.step("submit"), "recording")
        self.assertEqual(self.page.evaluate("window.submitted"), 0)

    def test_account_origin_and_inbox_boundaries(self):
        self.html(NOTE + CLOSE + SHARE)
        self.assertEqual(self.step("submit", "main_account"), "account")
        for url in ["https://www.instagram.com/direct/t/123/", "https://www.instagram.com/accounts/login/",
                    "https://example.org/direct/inbox/"]:
            self.page.goto(url)
            self.html(NOTE + CLOSE + SHARE)
            self.assertEqual(self.step("submit"), "unavailable")

    def test_ambiguous_results_and_missing_music_editor(self):
        self.html(MUSIC + '<button>Şarkı<br>Sanatçı</button>' * 2 + '</div>')
        self.assertEqual(self.step("select"), "unavailable")
        self.html('<div role="dialog" aria-label="Message"><input type="search" placeholder="Ara"></div>')
        self.assertEqual(self.step("search"), "unavailable")
        self.assertEqual(self.page.locator("input").input_value(), "")

    def test_no_automatic_replacement_or_login_form_interaction(self):
        self.html('<button>Edit your note</button>')
        self.assertEqual(self.step("open"), "unavailable")
        self.html(NOTE + CLOSE + SHARE + '<input type="password">')
        self.assertEqual(self.step("submit"), "unavailable")
        self.assertEqual(self.page.evaluate("window.submitted"), 0)


if __name__ == "__main__":
    unittest.main()
