#!/usr/bin/env python3
"""Create release metadata without embedding credentials or device-local state."""
import argparse
import hashlib
import json
import pathlib
import re
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "android" / "AndroidManifest.xml"
BINARIES = sorted((ROOT / "termux" / "bin").iterdir())


def read_version():
    raw = MANIFEST.read_text(encoding="utf-8")
    def attr(name):
        match = re.search(r'android:' + name + r'="([^"]+)"', raw)
        if not match:
            raise ValueError("Missing Android manifest " + name)
        return match.group(1)
    return int(attr("versionCode")), attr("versionName"), int(attr("minSdkVersion"))


def sha256(path):
    digest = hashlib.sha256()
    with pathlib.Path(path).open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def package_scripts(path):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as out:
        for source in BINARIES:
            if not source.is_file():
                continue
            info = zipfile.ZipInfo("bin/" + source.name)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o700 if source.suffix != ".html" else 0o600) << 16
            out.writestr(info, source.read_bytes())
        source = ROOT / "termux" / "start-codex-remote.sh"
        info = zipfile.ZipInfo("start-codex-remote.sh")
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o700 << 16
        out.writestr(info, source.read_bytes())


def make(tag, apk, output):
    code, name, sdk = read_version()
    if tag != "v" + name:
        raise ValueError(f"Tag {tag} does not match Android versionName {name}")
    output.mkdir(parents=True, exist_ok=True)
    scripts = output / "termux-scripts.zip"
    package_scripts(scripts)
    metadata = {
        "schema": 1,
        "repository": "to-o/codex-cover-remote",
        "tag": tag,
        "versionCode": code,
        "versionName": name,
        "minSdk": sdk,
        "apk": {"name": "codex-cover.apk", "sha256": sha256(apk), "size": apk.stat().st_size},
        "scripts": {"name": scripts.name, "sha256": sha256(scripts), "size": scripts.stat().st_size},
        "codexTested": "0.165.0",
    }
    (output / "manifest.json").write_text(
        json.dumps(metadata, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return metadata


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--tag", required=True)
    p.add_argument("--apk", required=True, type=pathlib.Path)
    p.add_argument("--output", default="out", type=pathlib.Path)
    a = p.parse_args()
    print(json.dumps(make(a.tag, a.apk, a.output), ensure_ascii=False))
