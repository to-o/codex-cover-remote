import importlib.util
import pathlib
import tempfile
import unittest
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("make_release", ROOT / "release/make_release.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ReleaseTests(unittest.TestCase):
    def test_version(self):
        code, version, sdk = module.read_version()
        self.assertGreater(code, 0)
        self.assertGreaterEqual(sdk, 34)
        self.assertTrue(version)

    def test_bundle_paths(self):
        with tempfile.TemporaryDirectory() as temp:
            archive = pathlib.Path(temp) / "scripts.zip"
            module.package_scripts(archive)
            with zipfile.ZipFile(archive) as z:
                names = z.namelist()
                self.assertIn("bin/codex-voice-bridge.py", names)
                self.assertIn("start-codex-remote.sh", names)
                self.assertTrue(all(n.startswith("bin/") or n == "start-codex-remote.sh" for n in names))

    def test_tag_mismatch_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            apk = pathlib.Path(temp) / "dummy.apk"
            apk.write_bytes(b"test")
            with self.assertRaises(ValueError):
                module.make("v0.0.0-invalid", apk, pathlib.Path(temp))


if __name__ == "__main__":
    unittest.main()
