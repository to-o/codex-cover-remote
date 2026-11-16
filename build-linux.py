#!/usr/bin/env python3
"""Build with the ARM64 Debian JDK/aapt/zipalign/apksigner and Android API 34."""
import argparse
import os
import pathlib
import subprocess
import tempfile
import zipfile

p = argparse.ArgumentParser()
p.add_argument('--android-jar', required=True)
p.add_argument('--r8-jar', required=True)
p.add_argument('--keystore', required=True)
p.add_argument('--password-file', required=True)
p.add_argument('--alias', default='codex-cover')
p.add_argument('--debug', action='store_true', help='Enable WebView inspection for local verification')
args = p.parse_args()
root = pathlib.Path(__file__).resolve().parent
out = root / 'out'
out.mkdir(exist_ok=True)
env = dict(os.environ)
env['CODEX_COVER_KEY_PASSWORD'] = pathlib.Path(args.password_file).read_text().strip()

def run(command, cwd=None):
    subprocess.run([str(x) for x in command], cwd=cwd, env=env, check=True)

with tempfile.TemporaryDirectory(prefix='codex-cover-build-') as tmp:
    tmp = pathlib.Path(tmp)
    classes, dex = tmp / 'classes', tmp / 'dex'
    classes.mkdir()
    dex.mkdir()
    run(['javac', '-encoding', 'UTF-8', '--release', '8', '-cp', args.android_jar,
         '-d', classes, *sorted((root / 'android/src').rglob('*.java'))])
    run(['java', '-cp', args.r8_jar, 'com.android.tools.r8.D8', '--lib', args.android_jar,
         '--min-api', '34', '--output', dex, *sorted(classes.rglob('*.class'))])
    manifest = root / 'android/AndroidManifest.xml'
    if args.debug:
        debug_manifest = tmp / 'AndroidManifest.xml'
        debug_manifest.write_text(manifest.read_text().replace('<application ', '<application android:debuggable="true" ', 1))
        manifest = debug_manifest
    run(['aapt', 'package', '-f', '-M', manifest,
         '-S', root / 'android/res', '-I', args.android_jar, '-F', tmp / 'unsigned.apk'])
    with zipfile.ZipFile(tmp / 'unsigned.apk', 'a', compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(dex / 'classes.dex', 'classes.dex')
    run(['zipalign', '-f', '4', tmp / 'unsigned.apk', tmp / 'aligned.apk'])
    run(['apksigner', 'sign', '--ks', args.keystore, '--ks-key-alias', args.alias,
         '--ks-pass', 'env:CODEX_COVER_KEY_PASSWORD', '--key-pass', 'env:CODEX_COVER_KEY_PASSWORD',
         '--out', out / 'codex-cover.apk', tmp / 'aligned.apk'])
    run(['apksigner', 'verify', '--verbose', out / 'codex-cover.apk'])
print(out / 'codex-cover.apk')
