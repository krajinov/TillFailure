"""Task-owned native stage/kill/cold-resume, with verified checkpoints before stops.
Requires create-only seed, complete initial export, and fresh built native hosts.
Never resets, erases, imports, rekeys, or silently retries an occupied stage.
"""
import argparse,hashlib,json,os,re,shutil,subprocess,time
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--root',type=Path);p.add_argument('--platform',choices=['android','ios'],required=True);p.add_argument('--mode',choices=['applied','fenced','conflict','failure','proof','settled','admission'],required=True);p.add_argument('--phase',choices=['stage','resume'],required=True);a=p.parse_args()
repo=Path(__file__).resolve().parents[3];root=a.root.resolve() if a.root else repo/'.firebase/m7-pr1b-native';run=json.loads((root/'run.json').read_text());assert re.fullmatch('m7p1br[a-f0-9]{8}',run['prefix'])
assert (root/'initial-export.sha256.json').exists();node=Path(os.environ.get('NODE22_BIN') or shutil.which('node') or '');assert node.is_file()
assert subprocess.check_output([str(node),'--version'],text=True).startswith('v22.'),'Set NODE22_BIN to an isolated Node 22 executable'
uid=f"{run['prefix']}_{a.platform}_assign_{a.mode}";out=root/'cases'/a.platform/a.mode;out.mkdir(parents=True,exist_ok=True)
log=out/f'{a.phase}.log';assert not log.exists(),f'Occupied {a.phase} evidence; inspect original history instead of replaying setup'
env={**os.environ,'GCLOUD_PROJECT':'demo-tillfailure-m3','FIRESTORE_EMULATOR_HOST':'127.0.0.1:48780','FIREBASE_AUTH_EMULATOR_HOST':'127.0.0.1:48799'}
def command(args,**kw):return subprocess.run([str(x) for x in args],check=True,**kw)
def checkpoint(label):
    if a.platform=='android':
        data=command([adb,'-s',serial,'exec-out','run-as',package,'tar','cf','-',f'no_backup/{uid}'],stdout=subprocess.PIPE).stdout
        again=command([adb,'-s',serial,'exec-out','run-as',package,'tar','cf','-',f'no_backup/{uid}'],stdout=subprocess.PIPE).stdout
        assert data==again and len(data)>1024,'Native checkpoint unstable/missing';path=out/f'{label}.tar';path.write_bytes(data)
    else:
        container=Path(command(['xcrun','simctl','get_app_container',run['ios'],bundle,'data'],stdout=subprocess.PIPE,text=True).stdout.strip());source=container/'Library/Application Support'/uid;assert source.exists()
        destination=out/label;shutil.copytree(source,destination)
        for f in source.rglob('*'):
            if f.is_file():assert f.read_bytes()==(destination/f.relative_to(source)).read_bytes(),'Native checkpoint unstable'
        path=destination
    files=[path] if path.is_file() else [x for x in sorted(path.rglob('*')) if x.is_file()]
    hashes={str(x.relative_to(out)):hashlib.sha256(x.read_bytes()).hexdigest() for x in files};assert hashes
    (out/f'{label}.sha256.json').write_text(json.dumps(hashes,indent=2))
    for name,h in hashes.items():assert hashlib.sha256((out/name).read_bytes()).hexdigest()==h

def audit():
    command([node,repo/'firebase/functions/scripts/audit-assignment-pr1b-native.mjs',root,a.platform,a.mode,out/'stage.log',a.phase,out/f'{a.phase}-server.json',log],env=env,cwd=repo)
def export():
    target=out/f'{a.phase}-server-export'
    with (out/f'{a.phase}-export.log').open('w') as f:command([node,repo/'firebase/functions/node_modules/firebase-tools/lib/bin/firebase.js','emulators:export',target,'--config',root/'firebase.json','--project','demo-tillfailure-m3'],cwd=repo,stdout=f,stderr=subprocess.STDOUT)
    metadata=json.loads((target/'firebase-export-metadata.json').read_text());assert all(x in metadata for x in ['auth','firestore','storage'])
    hashes={str(x.relative_to(target)):hashlib.sha256(x.read_bytes()).hexdigest() for x in sorted(target.rglob('*')) if x.is_file()}
    (out/f'{a.phase}-server-export.sha256.json').write_text(json.dumps(hashes,indent=2))
    for name,h in hashes.items():assert hashlib.sha256((target/name).read_bytes()).hexdigest()==h

if a.platform=='android':
    adb=Path.home()/'Library/Android/sdk/platform-tools/adb';serial='emulator-5554';package='com.delminiusapps.tillfailure.shared.test'
    name=command([adb,'-s',serial,'emu','avd','name'],stdout=subprocess.PIPE,text=True).stdout;assert 'TillFailure_M7PR1b_API_34' in name
    with log.open('w') as f:
        logger=subprocess.Popen([str(adb),'-s',serial,'logcat','-v','brief','-T','1'],stdout=f,stderr=subprocess.STDOUT)
        args=[adb,'-s',serial,'shell','am','instrument','-w','-r','-e','class','com.delminiusapps.tillfailure.firebase.AndroidProgramAssignmentsTest','-e','m7p1bPhase',f'assign-{a.mode}-{a.phase}','-e','m7p1bPrefix',run['prefix'],'-e','m7p1bPassword',run['password'],package+'/androidx.test.runner.AndroidJUnitRunner']
        with (out/f'{a.phase}-instrumentation.log').open('w') as instrumentation:process=subprocess.Popen([str(x) for x in args],stdout=instrumentation,stderr=subprocess.STDOUT)
        needle='M7P1B_ANDROID_PASS_assign_'+a.mode if a.phase=='resume' else 'M7P1B_ANDROID_PROOF_DURABLE' if a.mode=='proof' else 'M7P1B_ANDROID_READY_'+a.mode if a.mode in ['settled','failure','admission'] else 'M7P1B_ANDROID_ORIGINAL_HELD'
        deadline=time.monotonic()+110
        verified=False
        try:
            while needle not in log.read_text(errors='replace'):
                assert time.monotonic()<deadline,'Native checkpoint deadline; preserve all occupied state'
                assert process.poll() is None,'Native process/test failed before checkpoint; inspect retained logs'
                time.sleep(.2)
            checkpoint(a.phase+'-native');audit();export();checkpoint(a.phase+'-native-verified-before-stop');verified=True
        finally:
            # Only this explicitly verified task device/package, never an unrelated application.
            if verified:
                command([adb,'-s',serial,'shell','am','force-stop',package]);process.wait(timeout=15);logger.terminate();logger.wait(timeout=10)
            else:print('Verification failed; preserve the task process, keys, logs and occupied state for inspection',flush=True)
else:
    bundle='com.delminiusapps.tillfailure.TillFailure'
    env.update({'SIMCTL_CHILD_TF_M7P1B_NATIVE':f'assign-{a.mode}-{a.phase}','SIMCTL_CHILD_TF_M7P1B_PREFIX':run['prefix'],'SIMCTL_CHILD_TF_M7P1B_PASSWORD':run['password'],
        'SIMCTL_CHILD_TF_AUTH_EMULATOR_PORT':'48799','SIMCTL_CHILD_TF_FIRESTORE_EMULATOR_PORT':'48780','SIMCTL_CHILD_TF_FUNCTIONS_EMULATOR_PORT':'48701'})
    with log.open('w') as f:
        process=subprocess.Popen(['xcrun','simctl','launch','--console',run['ios'],bundle],stdout=f,stderr=subprocess.STDOUT,env=env)
        needle='M7P1B_IOS_PASS_assign_'+a.mode if a.phase=='resume' else 'M7P1B_IOS_PROOF_DURABLE' if a.mode=='proof' else 'M7P1B_IOS_READY_'+a.mode if a.mode in ['settled','failure','admission'] else 'M7P1B_IOS_ORIGINAL_HELD'
        deadline=time.monotonic()+110
        verified=False
        try:
            while needle not in log.read_text(errors='replace'):
                assert time.monotonic()<deadline,'Native checkpoint deadline; preserve all occupied state'
                assert process.poll() is None,'Native process failed before checkpoint; inspect retained logs'
                time.sleep(.2)
            checkpoint(a.phase+'-native');audit();export();checkpoint(a.phase+'-native-verified-before-stop');verified=True
        finally:
            if verified:
                if process.poll() is None:command(['xcrun','simctl','terminate',run['ios'],bundle])
                process.wait(timeout=15)
            else:print('Verification failed; preserve the task process, keys, logs and occupied state for inspection',flush=True)
print(f'{a.platform} {a.mode} {a.phase}: encrypted checkpoints and complete export verified before task process stop',flush=True)
