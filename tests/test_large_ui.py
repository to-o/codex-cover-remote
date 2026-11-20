"""Accessibility and touch-size regressions for Z Flip5 cover UI."""
import pathlib
import re
import unittest
from html.parser import HTMLParser

ROOT = pathlib.Path(__file__).resolve().parents[1]
MAIN = ROOT / "android/src/local/codex/cover/MainActivity.java"
VOICE = ROOT / "termux/bin/codex-voice.html"
ACTIVITY = ROOT / "android/src/local/codex/cover/VoiceActivity.java"


class Elements(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = set()

    def handle_starttag(self, tag, attrs):
        for name, value in attrs:
            if name == "id":
                self.ids.add(value)


class ReadableCoverTests(unittest.TestCase):
    def test_primary_text_and_actions(self):
        source = MAIN.read_text(encoding="utf-8")
        self.assertIn('label("Codex", 22, foreground, true)', source)
        self.assertIn('label("让想法，直接开口", 20, foreground, true)', source)
        self.assertIn('voice.setTextSize(16)', source)
        self.assertIn('button.setTextSize(15)', source)
        self.assertIn('status = label("● 检查中", 14', source)
        self.assertIn('service = label("正在检查", 17', source)
        self.assertIn('heroShape()', source)
        self.assertIn('scroll.setFillViewport(true)', source)
        self.assertIn('remote.setMinimumHeight(dp(78))', source)
        for name in ('"on"', '"off"', '"diagnose"', '"retry"'):
            self.assertIn(name, source)

    def test_web_voice_is_legible_and_responsive(self):
        source = VOICE.read_text(encoding="utf-8")
        self.assertIn("body{font:16px/1.5", source)
        self.assertIn("#state{text-align:center;font-size:21px", source)
        self.assertIn("#transcript{font-size:var(--subtitle-size,17px)", source)
        self.assertIn("button{appearance:none", source)
        self.assertIn("min-height:50px", source)
        self.assertIn("font:16px system-ui,sans-serif", source)
        self.assertIn("@media(max-height:480px)", source)
        self.assertIn("prefers-reduced-motion:reduce", source)
        self.assertIn("subtitleSizes=[17,20,23]", source)
        parser = Elements()
        parser.feed(source)
        self.assertIn("fontSize", parser.ids)
        self.assertIn("settingsToggle", parser.ids)
        self.assertIn("settingsSave", parser.ids)
        self.assertIn("start", parser.ids)
        self.assertIn("stop", parser.ids)
        self.assertIn("transcript", parser.ids)

    def test_android_accessibility_scale(self):
        source = ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("getConfiguration().fontScale", source)
        self.assertIn("setTextZoom", source)
        self.assertIn("message.setTextSize(19)", source)
        self.assertIn("back.setTextSize(15)", source)

    def test_version_increment(self):
        manifest = (ROOT / "android/AndroidManifest.xml").read_text(encoding="utf-8")
        self.assertRegex(manifest, r'android:versionCode="6"')
        self.assertRegex(manifest, r'android:versionName="1\.4\.0"')


if __name__ == "__main__":
    unittest.main()
