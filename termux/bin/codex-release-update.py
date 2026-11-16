#!/data/data/com.termux/files/usr/bin/python3
"""Verified, rollback-capable script updater for public GitHub releases.

Only HOME/bin scripts are updated. The device-specific PREFIX/bin/codex wrapper,
Codex executable, login state and configuration are intentionally untouched.
"""
import argparse
import contextlib
import fcntl
import hashlib
import io
import json
import os
import pathlib
import re
import tempfile
import urllib.request
import zipfile

HOME = pathlib.Path.home()
STATE = HOME / ".termux" / "codex-release.json"
LOCK = HOME / ".termux" / "codex-release-update.lock"
BIN = HOME / "bin"
LATEST_MANIFEST = "https://github.com/to-o/codex-cover-remote/releases/latest/download/manifest.json"
PREFIX_URL = "https://github.com/to-o/codex-cover-remote/releases/download/"
SKIP = {"bin/codex"}  # local, device-specific proot wrapper
MAX_ZIP_BYTES = 2 * 1024 * 1024


def fetch(url, max_bytes):
    # github.com redirects release assets to GitHub-operated HTTPS hosts.
    if not url.startswith("https://"):
        raise ValueError("只允许 HTTPS 更新地址")
    request = urllib.request.Request(url, headers={
        "Accept": "application/octet-stream",
        "User-Agent": "codex-cover-updater/1.0",
    })
    with urllib.request.urlopen(request, timeout=20) as response:
        data = response.read(max_bytes + 1)
    if len(data) > max_bytes:
        raise ValueError("更新文件超过大小限制")
    return data


def get_release():
    # Public GitHub Release assets avoid the shared-IP anonymous REST API limit.
    info = json.loads(fetch(LATEST_MANIFEST, 65536))
    tag = info.get("tag", "")
    if (info.get("schema") != 1 or info.get("repository") != "to-o/codex-cover-remote"
            or not isinstance(tag, str)
            or not re.fullmatch(r"v[0-9]+\\.[0-9]+\\.[0-9]+", tag)
            or info.get("versionName") != tag[1:]):
        raise ValueError("发布版本元数据不匹配")
    artifact = info["scripts"]
    if artifact["name"] != "termux-scripts.zip" or not re.fullmatch(r"[a-f0-9]{64}", artifact["sha256"]):
        raise ValueError("脚本校验信息不合法")
    if not 0 < artifact["size"] <= MAX_ZIP_BYTES:
        raise ValueError("脚本包大小不合法")
    assets = {"termux-scripts.zip": PREFIX_URL + tag + "/termux-scripts.zip"}
    return info, assets


def members(content):
    with zipfile.ZipFile(io.BytesIO(content)) as archive:
        names = archive.namelist()
        if len(names) > 40 or len(names) != len(set(names)):
            raise ValueError("脚本包数量不合法或存在重复文件")
        items = {}
        for item in archive.infolist():
            name = item.filename
            if item.is_dir() or not re.fullmatch(r"(bin/[A-Za-z0-9_.-]+|start-codex-remote\.sh)", name):
                raise ValueError("脚本包含非法路径")
            if ((item.external_attr >> 16) & 0o170000) == 0o120000:
                raise ValueError("禁止脚本包符号链接")
            if item.file_size > MAX_ZIP_BYTES:
                raise ValueError("脚本体积异常")
            if name in SKIP:
                continue
            data = archive.read(item)
            items[name] = data
        if "bin/codex-voice-bridge.py" not in items or "bin/codex-release-update.py" not in items:
            raise ValueError("脚本包缺少必需文件")
    return items


def apply(info, assets):
    package = fetch(assets["termux-scripts.zip"], MAX_ZIP_BYTES)
    expected = info["scripts"]
    if len(package) != expected["size"] or hashlib.sha256(package).hexdigest() != expected["sha256"]:
        raise ValueError("脚本 SHA-256 校验失败")
    updates = members(package)
    BIN.mkdir(mode=0o700, parents=True, exist_ok=True)
    STATE.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    old = {}
    written = []
    # Prevent simultaneous installs or watchdog-induced duplicate updates.
    with LOCK.open("a+") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if STATE.exists():
            try:
                if json.loads(STATE.read_text()).get("tag") == info["tag"]:
                    return {"ok": True, "status": "already-current", "tag": info["tag"]}
            except (OSError, ValueError):
                pass
        try:
            for name, data in updates.items():
                dest = BIN / (pathlib.PurePosixPath(name).name)
                if dest.is_symlink():
                    raise ValueError("拒绝覆盖符号链接：" + dest.name)
                old[dest] = (dest.read_bytes(), dest.stat().st_mode & 0o777) if dest.exists() else None
                with tempfile.NamedTemporaryFile(dir=BIN, prefix=".update-", delete=False) as temp:
                    tmp = pathlib.Path(temp.name)
                    temp.write(data)
                try:
                    tmp.chmod(0o600 if dest.suffix == ".html" else 0o700)
                    os.replace(tmp, dest)
                    written.append(dest)
                finally:
                    tmp.unlink(missing_ok=True)
            marker = {"tag": info["tag"], "versionCode": info["versionCode"]}
            with tempfile.NamedTemporaryFile(dir=STATE.parent, mode="w", prefix=".release-", delete=False) as temp:
                marker_tmp = pathlib.Path(temp.name)
                json.dump(marker, temp)
            try:
                marker_tmp.chmod(0o600)
                os.replace(marker_tmp, STATE)
            finally:
                marker_tmp.unlink(missing_ok=True)
        except Exception:
            for dest in reversed(written):
                previous = old[dest]
                if previous is None:
                    dest.unlink(missing_ok=True)
                else:
                    data, mode = previous
                    with tempfile.NamedTemporaryFile(dir=BIN, prefix=".rollback-", delete=False) as temp:
                        tmp = pathlib.Path(temp.name)
                        temp.write(data)
                    tmp.chmod(mode)
                    os.replace(tmp, dest)
            raise
    return {"ok": True, "status": "updated", "tag": info["tag"], "files": len(written),
            "note": "运行中的 Codex 服务不会自动重启；请在任务结束后重新启动。"}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("command", choices=("check", "apply"))
    p.add_argument("--tag")
    opts = p.parse_args()
    info, assets = get_release()
    if opts.tag and opts.tag != info["tag"]:
        raise ValueError("指定的版本与 GitHub 最新正式版本不一致")
    installed = {}
    if STATE.exists():
        try:
            installed = json.loads(STATE.read_text())
        except (OSError, ValueError):
            pass
    if opts.command == "check":
        return {"ok": True, "current": installed.get("tag"), "latest": info["tag"],
                "updateAvailable": installed.get("tag") != info["tag"]}
    return apply(info, assets)


if __name__ == "__main__":
    try:
        print(json.dumps(main(), ensure_ascii=False))
    except Exception as e:
        print(json.dumps({"ok": False, "error": str(e)[:300]}, ensure_ascii=False))
        raise SystemExit(1)
