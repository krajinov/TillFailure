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

## Milestone 5 own-command resolution prerequisite

`commandResolutionEmulator` is a guarded demo-only own-outcome mediator for invitation create/resend/revoke/accept, onboarding and basic-profile update. It has no revocation operation, resource read surface or production configuration. Build the existing Functions package before the scripts below.

The trusted coverage procedure, stable generation, explicit provenance attestation, preserve/export/import requirements, finite limits and compatibility restrictions are in the [implementation report](../docs/milestones/milestone-5-command-resolution-report.md#coverage-configuration-and-provenance). `functions/scripts/command-resolution-config.mjs` supports only guarded fresh-dataset initialization and fail-closed disable; it supplies no re-enable/repair/reset. `functions/scripts/seed-m5-resolution.mjs` creates task-owned native/UI fixtures and refuses identity/workspace replacement. Credentials are supplied transiently through TF_RESOLUTION_FIXTURE_PASSWORD, never committed. Do not run reset-oriented suites against a preserved native fixture dataset or infer history coverage from an empty query.

Run reset-oriented suites to completion before starting a preserved native supervisor. Avoid concurrent Firebase CLI supervisors for the same project even on separate ports: shared project discovery can break explicit and exit exports. Verify the complete export while the sole supervisor is running before stopping it. A failed export supplies no coverage proof; never recreate old fixture identities/keys to continue a lost dataset.

## Milestone 5 PR 4 client workspace-membership revocation

`clientRevocationEmulator` offers bounded read-only preparation and exact owner-AND-assigned-trainer revocation, with atomic counter/provenance maintenance, immutable success/conflict receipts and the seventh protected-command adapter. The unexposed assignment materializer now serializes artifact creation through workspace membershipRevision and live relationship checks. No generic lifecycle API or production operations are enabled. See the [PR 4 report](../docs/milestones/milestone-5-pr4-report.md).

`functions/scripts/seed-m5-pr4.mjs` creates only new task-prefixed synthetic fixtures; it requires `TF_PR4_FIXTURE_PREFIX` matching `m5p4r` plus digits and transient `TF_PR4_FIXTURE_PASSWORD`. It refuses existing identity/account/workspace replacement and reuses guarded provisioning/invitation/onboarding. Complete reset suites before the sole preserved native supervisor starts, use the approved generation/provenance procedure, verify exports while it runs, and never reuse PR #13's lost-export identities/keys.

## Milestone 5 PR 5 private trainer notes

`trainerNotesEmulator` exposes only note-get and registered save-trainer-note in named loopback demo environments. It validates current author/subject authority and immutable relationship bindings for one occupied slot per client/workspace. Save commits the exact note/revision and minimal immutable receipt atomically, or a recognized stale conflict receipt. The eighth own-command adapter uses the existing ledger/fence protocol. Direct Rules access remains denied; notes do not block membership revocation.

`functions/scripts/seed-m5-pr5.mjs` creates only absent synthetic identities/workspaces using the existing guarded bootstrap/invitation/onboarding operations. Supply a transient TF_PR5_FIXTURE_PASSWORD and a new TF_PR5_FIXTURE_PREFIX matching m5p5r plus digits; optional platform/mode selectors bound the requested fixture set. Preserve native partitions and export Auth/Firestore with coverage configuration before shutting down the sole supervisor. Never run reset-oriented tests concurrently with it or recreate lost history. No production/deployment entry point is enabled. See the [approved plan](../docs/milestones/milestone-5-pr5-plan.md) and [report](../docs/milestones/milestone-5-pr5-report.md).
