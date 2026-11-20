"""Regressions for subscription-backed Live preferences (no Codex login needed)."""
import importlib.util
import json
import pathlib
import queue
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[1]
BIN = ROOT / "termux" / "bin"
sys.path.insert(0, str(BIN))

import codex_voice_settings as settings

spec = importlib.util.spec_from_file_location("voice_bridge_under_test", BIN / "codex-voice-bridge.py")
voice_bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(voice_bridge)


class SettingsTests(unittest.TestCase):
    def test_normalize_defaults_and_compatible_model(self):
        self.assertEqual(settings.normalize({}), settings.DEFAULTS)
        self.assertEqual(settings.normalize({"voice":"marin", "liveModel":"gpt-live-1-codex",
            "codexModel":"gpt-5.4"}), {
                "voice":"marin", "liveModel":"gpt-live-1-codex","codexModel":"gpt-5.4"})

    def test_invalid_values_rejected(self):
        for value in [{"voice": "../../bad"}, {"liveModel": "gpt-realtime"},
                      {"codexModel": "foo bar"}, {"privateToken": "secret"}]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                settings.normalize(value)

    def test_save_and_load_permissions(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / ".termux" / "settings.json"
            saved = settings.save({"voice":"cove"}, path=path)
            self.assertEqual(saved["voice"], "cove")
            self.assertEqual(settings.load(path=path), saved)
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)

    def test_invalid_saved_file_does_not_break_voice(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "bad.json"
            path.write_text("{invalid")
            self.assertEqual(settings.load(path), settings.DEFAULTS)

    def test_catalogs_parse_v3_only_and_hide_hidden_models(self):
        voices = settings.voices_from_rpc({"voices": {
            "v1": ["spruce"], "v2": ["alloy"], "v3": ["cove","marin","cove"]}})
        self.assertEqual(voices, ["cove","marin"])
        models = settings.models_from_rpc({"data": [
            {"id":"gpt-5.4","model":"gpt-5.4","displayName":"GPT-5.4"},
            {"model":"private-model","hidden":True},
            {"id":"gpt-5.4","model":"gpt-5.4"}]})
        self.assertEqual(models, [{"id":"gpt-5.4","label":"GPT-5.4"}])


class VoiceRpcTests(unittest.TestCase):
    def make_voice(self):
        voice = voice_bridge.Voice.__new__(voice_bridge.Voice)
        voice.operation = threading.Lock()
        voice.lock = threading.RLock()
        voice.active = False
        voice.working = False
        voice.thread_id = None
        voice.thread_model = None
        voice.start_result = queue.Queue()
        voice.calls = []
        def fake_rpc(method, params, timeout=45):
            voice.calls.append((method, params))
            if method == "thread/start":
                return {"thread":{"id":"thr_test"}}
            if method == "thread/realtime/start":
                voice.start_result.put(("sdp","v=answer"))
            return {}
        voice.rpc = fake_rpc
        return voice

    def test_start_uses_separate_coding_and_realtime_models(self):
        voice = self.make_voice()
        pref = {"voice":"marin", "liveModel":"gpt-live-1-codex","codexModel":"gpt-5.4"}
        with patch.object(voice_bridge, "load_voice_settings", return_value=pref):
            self.assertEqual(voice.start("v=0"), {"sdp":"v=answer"})
        calls = dict(voice.calls)
        self.assertEqual(calls["thread/start"]["model"], "gpt-5.4")
        self.assertEqual(calls["thread/realtime/start"]["voice"], "marin")
        self.assertEqual(calls["thread/realtime/start"]["model"], "gpt-live-1-codex")
        self.assertEqual(calls["thread/realtime/start"]["version"], "v3")

    def test_auto_keeps_original_working_defaults(self):
        voice = self.make_voice()
        with patch.object(voice_bridge, "load_voice_settings", return_value=dict(settings.DEFAULTS)):
            voice.start("v=0")
        calls = dict(voice.calls)
        self.assertNotIn("model", calls["thread/start"])
        self.assertNotIn("model", calls["thread/realtime/start"])
        self.assertNotIn("voice", calls["thread/realtime/start"])

    def test_options_uses_supported_catalog_methods(self):
        voice = self.make_voice()
        def fake_rpc(method, params, timeout=45):
            if method == "thread/realtime/listVoices":
                return {"voices":{"v3":["cove","marin"],"v1":[],"v2":[]}}
            if method == "model/list":
                return {"data":[{"model":"gpt-5.4","displayName":"GPT-5.4"}]}
            raise AssertionError(method)
        voice.rpc = fake_rpc
        with patch.object(voice_bridge, "load_voice_settings", return_value=dict(settings.DEFAULTS)):
            result = voice.options()
        self.assertEqual(result["voices"], ["cove","marin"])
        self.assertEqual(result["models"][0]["id"], "gpt-5.4")


if __name__ == "__main__":
    unittest.main()
