#!/usr/bin/env python3
"""Build/run a separate simulator executable against the production Swift atomic store.

Requires an explicitly task-owned, already booted simulator and fresh fixture checkpoint.
Never builds the product target, initializes Firebase, signs into Auth or issues commands.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import plistlib
import shutil
import subprocess

p = argparse.ArgumentParser()
p.add_argument('--simulator', required=True)
p.add_argument('--framework', type=Path, required=True)
p.add_argument('--resume', action='store_true', help='Reuse only byte-identical existing synthetic inputs; never clear state')
args = p.parse_args()
repo = Path(__file__).resolve().parents[2]
run = repo / '.firebase/m7-pr1a'
fixtures = run / 'native-fixtures'
files = sorted(fixtures.glob('*.json'))
assert files, 'Export fresh Kotlin fixtures first'
checkpoint = {f.name: {'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'bytes': f.stat().st_size} for f in files}
(run / 'native-input-checkpoint.json').write_text(json.dumps(checkpoint, sort_keys=True, indent=2))
app = run / 'AssignmentStorageProbe.app'
app.mkdir(exist_ok=True)
bundle = 'com.delminiusapps.tillfailure.m7pr1asizing'
with (app / 'Info.plist').open('wb') as f:
    plistlib.dump({'CFBundleExecutable': 'AssignmentStorageProbe', 'CFBundleIdentifier': bundle, 'CFBundleName': 'AssignmentStorageProbe',
                  'CFBundlePackageType': 'APPL', 'CFBundleVersion': '1', 'CFBundleShortVersionString': '1', 'MinimumOSVersion': '18.2',
                  'CFBundleSupportedPlatforms': ['iPhoneSimulator'], 'UIDeviceFamily': [1]}, f)
entitlements = run / 'sizing-simulated-entitlements.plist'
with entitlements.open('wb') as f:
    plistlib.dump({'application-identifier': 'FAKETEAMID.' + bundle}, f)
def call(*command, **kwargs):
    return subprocess.run(command, check=True, **kwargs)
der = run / 'sizing-simulated-entitlements.der'
call('/usr/bin/derq', 'query', '-f', 'xml', '-i', str(entitlements), '-o', str(der), '--raw')
sdk = subprocess.check_output(['xcrun', '--sdk', 'iphonesimulator', '--show-sdk-path'], text=True).strip()
call('xcrun', '--sdk', 'iphonesimulator', 'swiftc', '-D', 'DEBUG', '-target', 'arm64-apple-ios18.2-simulator', '-sdk', sdk,
     '-F', str(args.framework.resolve().parent), '-framework', 'Shared', '-framework', 'UIKit', '-framework', 'Security',
     '-framework', 'Foundation', '-framework', 'CoreGraphics', '-framework', 'CoreText', '-framework', 'Metal', '-framework', 'QuartzCore',
     '-Xlinker', '-sectcreate', '-Xlinker', '__TEXT', '-Xlinker', '__entitlements', '-Xlinker', str(entitlements), '-Xlinker', '-sectcreate', '-Xlinker', '__TEXT', '-Xlinker', '__ents_der', '-Xlinker', str(der), '-Xlinker', '-dead_strip', '-lc++', '-lsqlite3', '-lz', str(repo / 'iosApp/iosApp/RecoveryPersistenceBridge.swift'), str(repo / 'iosApp/feasibility/AssignmentStorageMain.swift'),
     '-o', str(app / 'AssignmentStorageProbe'))
call('codesign', '--force', '--sign', '-', '--timestamp=none', '--generate-entitlement-der', str(app))
call('xcrun', 'simctl', 'install', args.simulator, str(app))
container = Path(subprocess.check_output(['xcrun', 'simctl', 'get_app_container', args.simulator, bundle, 'data'], text=True).strip())
target = container / 'Documents' / 'fixtures'
if target.exists():
    assert args.resume, 'Occupied run requires explicit --resume with identical inputs'
else:
    target.parent.mkdir(exist_ok=True)
    shutil.copytree(fixtures, target)
assert checkpoint == {f.name: {'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'bytes': f.stat().st_size} for f in sorted(target.glob('*.json'))}
env = dict(os.environ, SIMCTL_CHILD_TF_M7_PR1A_FIXTURES=str(target), SIMCTL_CHILD_TF_M7_PR1A_OUTPUT=str(container / 'Documents/result.json'))
with (run / 'swift-native.log').open('w') as log:
    call('xcrun', 'simctl', 'launch', '--console', args.simulator, bundle, stdout=log, stderr=subprocess.STDOUT, env=env)
result = json.loads((container / 'Documents/result.json').read_text())
assert result['fixtures'] == len([f for f in files if 'oversize' not in f.name])
shutil.copyfile(container / 'Documents/result.json', run / 'swift-native.json')
encrypted = sorted(f for root in (container / 'Library/Application Support').glob('m7-pr1a-sizing-*') for f in root.rglob('account-*.json'))
assert encrypted
post = {str(f.relative_to(container)): {'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'bytes': f.stat().st_size} for f in encrypted}
(run / 'swift-native-checkpoint.json').write_text(json.dumps(post, sort_keys=True, indent=2))
assert all(hashlib.sha256(f.read_bytes()).hexdigest() == post[str(f.relative_to(container))]['sha256'] for f in encrypted)
print(json.dumps(result, sort_keys=True))
