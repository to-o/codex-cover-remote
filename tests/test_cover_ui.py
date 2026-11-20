"""Contract checks for the Galaxy Z Flip cover dashboard and Live WebView."""
import pathlib
import re
import shutil
import subprocess
import unittest
from html.parser import HTMLParser

ROOT = pathlib.Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android/src/local/codex/cover"
VOICE = ROOT / "termux/bin/codex-voice.html"


class IdParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = []

    def handle_starttag(self, tag, attrs):
        self.ids.extend(value for name, value in attrs if name == "id")


class CoverUiTests(unittest.TestCase):
    def test_version_bumped(self):
        manifest = (ROOT / "android/AndroidManifest.xml").read_text()
        code = re.search(r'android:versionCode="([0-9]+)"', manifest)
        version = re.search(r'android:versionName="([^"]+)"', manifest)
        self.assertIsNotNone(code)
        self.assertIsNotNone(version)
        self.assertGreaterEqual(int(code.group(1)), 4)
        self.assertRegex(version.group(1), r"^[0-9]+\.[0-9]+\.[0-9]+$")

    def test_cover_actions_still_wired(self):
        source = (ANDROID / "MainActivity.java").read_text()
        for label in ("CODEX LIVE", "开始语音对话", "连接诊断", "设置与更新"):
            self.assertIn(label, source)
        self.assertIn('new UpdateManager(this).check()', source)
        self.assertIn('new Intent(this, VoiceActivity.class)', source)
        self.assertIn('runCommand("status-json")', source)
        self.assertIn('runCommand("diagnose")', source)
        self.assertIn('createButton("关闭", "off"', source)
        self.assertIn('createButton("启动", "on"', source)
        self.assertIn('createButton("重试", "retry"', source)

    def test_voice_controls_and_protocol_preserved(self):
        source = VOICE.read_text()
        parser = IdParser()
        parser.feed(source)
        for elem in ("state", "hint", "start", "stop", "mute", "play",
                     "approval", "approvalText", "allow", "deny", "task",
                     "transcript", "speaker"):
            self.assertEqual(parser.ids.count(elem), 1, elem)
        for feature in ("getUserMedia", "RTCPeerConnection", "createDataChannel",
                        "api('start'", "api('stop'", "api('approval'",
                        "window.stopVoice", "pagehide"):
            self.assertIn(feature, source)
        self.assertIn("prefers-reduced-motion", source)
        self.assertIn("@media(max-height:480px)", source)

    @unittest.skipUnless(shutil.which("node"), "Node.js required to check browser script")
    def test_webview_javascript_syntax(self):
        html = VOICE.read_text()
        js = re.search(r"<script>([\s\S]*?)</script>", html)
        self.assertIsNotNone(js)
        result = subprocess.run(
            ["node", "--check"], input=js.group(1), capture_output=True, text=True,
            timeout=10,
        )
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
