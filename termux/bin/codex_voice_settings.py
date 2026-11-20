"""Validated per-device settings for Codex Cover's experimental Live session.

The file lives in Termux HOME, not in the public repository or Codex's global
config.toml. The regular Codex model and realtime Live model are independent.
"""
import json
import os
import pathlib
import re
import tempfile

SETTINGS_PATH = pathlib.Path.home() / ".termux" / "codex-cover-live-settings.json"
DEFAULTS = {"voice": None, "liveModel": None, "codexModel": None}
LIVE_MODELS = {"gpt-live-1-codex"}
IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.:/-]{0,99}$")


def normalize(raw):
    if not isinstance(raw, dict):
        raise ValueError("设置格式必须是对象")
    if set(raw) - set(DEFAULTS):
        raise ValueError("存在不支持的设置字段")
    result = {}
    for key in DEFAULTS:
        value = raw.get(key)
        if value in (None, "", "auto"):
            result[key] = None
        elif not isinstance(value, str) or not IDENTIFIER.fullmatch(value):
            raise ValueError("设置值格式不正确：" + key)
        else:
            result[key] = value
    if result["liveModel"] not in LIVE_MODELS | {None}:
        raise ValueError("不支持的 Live 模型")
    return result


def load(path=SETTINGS_PATH):
    if not path.exists():
        return dict(DEFAULTS)
    try:
        return normalize(json.loads(path.read_text(encoding="utf-8")))
    except (OSError, ValueError, TypeError):
        # An invalid file must never break an existing working voice setup.
        return dict(DEFAULTS)


def save(settings, path=SETTINGS_PATH):
    value = normalize(settings)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temp = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", dir=path.parent,
            prefix=".codex-cover-settings-", delete=False
        ) as handle:
            temp = pathlib.Path(handle.name)
            json.dump(value, handle, ensure_ascii=False)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        temp.chmod(0o600)
        os.replace(temp, path)
    finally:
        if temp is not None:
            temp.unlink(missing_ok=True)
    return value


def voices_from_rpc(response):
    voices = response.get("voices", {})
    entries = voices.get("v3", []) if isinstance(voices, dict) else []
    if not isinstance(entries, list):
        return []
    return list(dict.fromkeys(
        name for name in entries
        if isinstance(name, str) and IDENTIFIER.fullmatch(name)
    ))


def models_from_rpc(response):
    entries = response.get("data", [])
    if not isinstance(entries, list):
        return []
    result, seen = [], set()
    for entry in entries[:150]:
        if not isinstance(entry, dict) or entry.get("hidden"):
            continue
        ident = entry.get("model") or entry.get("id")
        if not isinstance(ident, str) or not IDENTIFIER.fullmatch(ident) or ident in seen:
            continue
        title = entry.get("displayName")
        seen.add(ident)
        result.append({"id": ident, "label": title if isinstance(title, str) and title else ident})
    return result
