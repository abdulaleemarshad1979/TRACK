"""Verify that the published Monitor APK matches its update manifest."""
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parent.parent
manifest = json.loads((root / 'monitor-version.json').read_text())
apk = root / 'release/Trace-Monitor.apk'
sdk = Path(os.environ['ANDROID_HOME'])
build_tools = sorted((sdk / 'build-tools').iterdir())[-1]
badging = subprocess.check_output([str(build_tools / 'aapt'), 'dump', 'badging', str(apk)], text=True)
match = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
assert match and match[1] == 'org.trace.monitor', 'Wrong Monitor package'
assert int(match[2]) == manifest['versionCode'], 'Monitor APK versionCode differs from update manifest'
assert match[3] == manifest['versionName'], 'Monitor APK versionName differs from update manifest'
assert hashlib.sha256(apk.read_bytes()).hexdigest() == manifest['sha256'], 'Monitor APK hash differs from update manifest'
cert = subprocess.check_output([str(build_tools / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
assert manifest['signingCertificateSha256'] in cert, 'Monitor signing certificate changed; installed apps cannot upgrade'
print(f"Verified signed Monitor APK {manifest['versionName']} (code {manifest['versionCode']}) and update manifest")
