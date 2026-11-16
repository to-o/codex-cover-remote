import importlib.util
import json
import pathlib
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[1]
UPDATER = ROOT / "termux/bin/codex-release-update.py"
spec = importlib.util.spec_from_file_location("codex_release_updater", UPDATER)
updater = importlib.util.module_from_spec(spec)
spec.loader.exec_module(updater)


class OtaManifestTests(unittest.TestCase):
    def setUp(self):
        self.manifest = {
            "schema": 1,
            "repository": "to-o/codex-cover-remote",
            "tag": "v1.1.1",
            "versionName": "1.1.1",
            "versionCode": 3,
            "scripts": {
                "name": "termux-scripts.zip",
                "sha256": "a" * 64,
                "size": 4096,
            },
        }

    def test_release_manifest_avoids_rest_api(self):
        self.assertEqual(
            updater.LATEST_MANIFEST,
            "https://github.com/to-o/codex-cover-remote/releases/latest/download/manifest.json",
        )
        android = (ROOT / "android/src/local/codex/cover/UpdateManager.java").read_text()
        self.assertIn("releases/latest/download/manifest.json", android)
        self.assertNotIn("api.github.com", android)

    def test_valid_release_uses_fixed_tag_asset(self):
        with patch.object(updater, "fetch", return_value=json.dumps(self.manifest).encode()):
            info, assets = updater.get_release()
        self.assertEqual(info["tag"], "v1.1.1")
        self.assertEqual(
            assets["termux-scripts.zip"],
            "https://github.com/to-o/codex-cover-remote/releases/download/v1.1.1/termux-scripts.zip",
        )

    def test_invalid_tag_cannot_escape_release_url(self):
        self.manifest["tag"] = "v1.1.1/../../other"
        with patch.object(updater, "fetch", return_value=json.dumps(self.manifest).encode()):
            with self.assertRaises(ValueError):
                updater.get_release()

    def test_version_and_tag_must_match(self):
        self.manifest["versionName"] = "1.1.0"
        with patch.object(updater, "fetch", return_value=json.dumps(self.manifest).encode()):
            with self.assertRaises(ValueError):
                updater.get_release()


if __name__ == "__main__":
    unittest.main()
