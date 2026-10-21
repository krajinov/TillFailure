# Milestone 3 Firebase emulator spike

This directory is local-emulator-only. It contains the trusted-operation probes,
Firestore/Storage Rules, indexes, and integration tests for Milestone 3. It does
not contain deploy scripts or a Firebase project alias.

The test project ID is always `demo-tillfailure-m3`. Firebase reserves the
`demo-` prefix for emulator-only projects; the guard script rejects any other
project ID and requires the emulator host variables before tests run.

Prerequisites: Java 21 and a Node 22 runtime. Install exactly locked packages:

```text
npm --prefix firebase/functions ci
npm --prefix firebase/functions run build
npm --prefix firebase/functions run test:emulators
```

Ports are fixed in `firebase.json`: Auth 9099, Functions 5001, Firestore 8080,
and Storage 9199. If a port is occupied, stop the conflicting local process;
do not silently select another port because native harnesses use these values.

`test:emulators` starts and stops the suite through the locally locked Firebase
CLI and cannot select a deployed project. No command in this package deploys.

## Milestone 5 PR 3 basic-profile emulator

`basicProfileEmulator` exposes strict `profile-get` and registered `update-profile` operations on own global basics for a freshly verified active completed client. It refuses non-demo/non-loopback environments. Caller-bound receipts atomically acknowledge an update or definitively reject a stale revision; generic errors cannot settle critical work. Direct mobile account writes and trainer global-account reads remain denied. Production operations and deployment are disabled.

`functions/scripts/seed-m5-pr3.mjs` creates only absent synthetic Android/iOS trainer/client fixtures through existing guarded provisioning, invitation acceptance and onboarding; it requires `TF_M5_PR3_PASSWORD` from temporary environment input. Never reset another dataset or native partition to rerun. The PR 3 report records separate native held-response stages/resumes, actual interaction evidence, ports and retained gaps.
