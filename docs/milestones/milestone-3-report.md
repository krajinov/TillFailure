# Milestone 3 report: Firebase environment, native parity, and persistence spike

Status: **implemented and locally verified; milestone acceptance remains conditional on the blockers below**

Date: **2026-09-20**

Branch: `feature/milestone-3-firebase-spike`
Starting commit: `7d0bf7dfd2a6cb4d23d5d72bb76b20e4ebb55f47`

## Outcome

Milestone 3 established an emulator-only Firebase backend, proved the three documented authorization/transaction primitives, exercised official Android and Apple Firebase adapters through real native runtimes, and tested a UID-partitioned atomic-file persistence prototype on both targets. ADR-001 is accepted for official SDKs plus the narrow Swift bridge.

The milestone is not marked fully accepted because the atomic-file prototype does not prove application-level encryption at rest, and a deliberately stalled `waitForPendingWrites` timeout/cancellation was not induced on both native SDKs. Storage Rules were emulator-tested, but native resumable Storage upload/process-death behavior was not evaluated because no Milestone 3 product upload path required it.

No Firebase cloud project was created or changed. Nothing was deployed, staged, committed, pushed, merged, tagged, released, or opened as a PR. No production credentials/data or Milestone 4 behavior was used.

## Implemented structure

- Root `firebase.json` fixes Auth `9099`, Functions `5001`, Firestore `8080`, and Storage `9199` to loopback and disables Emulator UI.
- `firebase/functions` contains a Node 22 Functions package, exact lockfile, TypeScript trusted-operation probes, and serial emulator tests.
- `firebase/firestore.rules`, `firebase/firestore.indexes.json`, and `firebase/storage.rules` deny by default and expose only the spike paths.
- `shared/commonMain` owns immutable Firebase-free contracts, stable failures, cancellation, callback fencing, and versioned persistence records.
- `shared/androidMain` uses official Android Auth/Firestore SDKs and a UID-partitioned atomic file.
- `iosApp` owns the official Apple SDK and callback bridge; `shared/iosMain` maps bridge DTOs into the same common contract and owns the iOS atomic file.
- The Swift harness is Debug-only and opt-in through `TILLFAILURE_FIREBASE_SPIKE=1`; normal launch remains the Milestone 2 Foundation/catalog flow.

## Exact dependencies

| Area | Exact version | Result |
|---|---:|---|
| Firebase Android BoM | `34.19.0` | Android adapter compiled and ran on API 34 emulator |
| Firebase Apple SDK (SPM) | `12.19.2` | Resolved, linked, signed, and ran on iPhone 16e/iOS 26.2 simulator |
| Firebase Functions | `7.4.0` | TypeScript compile and emulator load passed |
| Firebase Admin | `14.4.0` | Trusted transaction tests passed |
| Firebase JS test client | `12.19.0` | Rules integration tests passed |
| Rules unit testing | `5.0.2` | Firestore and Storage Rules tests passed |
| Firebase CLI | `15.30.2` | Locked local emulator start/stop passed |
| TypeScript | `5.9.3` | Exact compile passed |
| Node types | `22.20.3` | Exact compile passed |
| Kotlin serialization JSON | `1.9.0` | Persistence envelope round trips passed |
| AndroidX test runner | `1.7.0` | Android device suite executed |

The package requires and declares Node 22. The available host ran Node `25.9.0`, and the Functions emulator explicitly reported that it used host Node 25 instead. This is a local tool-version limitation and not evidence of a Node 22 runtime pass. `npm audit` reported eight moderate transitive findings; no unsafe automatic audit fix was applied.

## Backend proof

Final emulator run: **14 passed, 0 failed**.

| Primitive | Automated proof |
|---|---|
| Booking contention | 4 tests: complete outward-rounded buffered buckets; overlapping first booking in an empty range allows at most one commit; lock/appointment/receipt replay and reschedule/cancel atomicity; cleanup protects live appointments; bounds reject before write |
| Catalog entitlement | 3 tests: activation/replay/revocation/restoration without count drift; bounded workspace suspend/restore and account disable; unsupported fan-out rejects without partial state |
| Assigned snapshot | 3 tests: bounded atomic publication and replay; replacement gets a new identity and old parent becomes terminal; oversize publication rejects without partial state |
| Firestore/Storage Rules | 4 tests: active catalog entitlement/published item; bounded direct snapshot checks and hidden trainer sources; owner write versus forged owner denial; Storage owner/MIME/metadata/size checks |

The probes keep quantum, timing, membership fan-out, snapshot size, and write caps as inputs. They establish feasibility under tested caps; they do not approve product values. The booking paths use `workspaces/{workspaceId}/bookingSlots/{trainerId}_{utcBucket}` and never treat an empty appointment query as a lock. Snapshot clients can read only the account-owned copy at `users/{uid}/workspaces/{wid}/assignedPrograms/{aid}/snapshots/content`; original trainer sources remain denied.

## Official SDK versus GitLive 2.6.0

GitLive `2.6.0` source exposes the necessary common Auth flow and Firestore get/listen/write/transaction, metadata, error codes, pending-write wait, cancellation through Flow collection, termination, persistence clearing, and emulator routing. It is a functionally credible alternative, not rejected for missing these APIs.

| Required surface | GitLive `2.6.0` | Official adapters | Decision evidence |
|---|---|---|---|
| Auth state/emulator | Common `authStateChanged`, `useEmulator` | Native runtime pass on both | Parity |
| Get/listen/write/transaction | Common suspend/Flow APIs | Native runtime pass on both | Parity |
| Snapshot metadata | `isFromCache`, `hasPendingWrites`, metadata changes | Native runtime pass on both | Parity |
| Rules rejection | Typed Firestore exception code | Stable `PERMISSION_DENIED` mapped on both | Parity |
| Pending-write wait | Common `waitForPendingWrites` | Success path passed on both | Parity; stalled cancellation not induced |
| Cancellation/disposal | Coroutine/Flow cancellation | Explicit project token/registration | Both viable; project bridge is explicit |
| Late-callback fencing | Still an app-owned responsibility | UID/account epoch is built into both adapters and runtime-tested | Equivalent app work |
| Terminate/clear | Common APIs present | Native runtime pass on both | Parity |
| Emulator/restart | Native SDK delegation | Explicit demo-project/loopback guards; native cold launch and persistence restart proof | Official path passed |
| Dependency alignment | Kotlin `2.2.21`, Android BoM `34.17.0`, Apple CocoaPods `11.8.0` in the exact tag | Existing Kotlin `2.4.10`, Android BoM `34.19.0`, Apple SPM `12.19.2` | Official path avoids wrapper and Apple SDK lag |

ADR-001 therefore accepts the official SDK approach. GitLive would reduce handwritten adapter code, but it does not remove app-owned fencing/persistence policy, and its stable tag introduces an extra compatibility owner plus an older CocoaPods-based Apple SDK. No GitLive dependency remains in the tree.

## Seven-case native parity matrix

| Case | Android actual result | Apple actual result | Attribution and limitations |
|---|---|---|---|
| 1. Online accepted read/write | PASS: anonymous Auth, owner write, server read | PASS: signed native app, owner write, server read | Automated Android instrumentation; opt-in Swift runtime harness |
| 2. Rules-rejected stable error | PASS: forged owner mapped to `PERMISSION_DENIED` | PASS: forged owner mapped to `PERMISSION_DENIED` | Emulator Rules, no Admin bypass in client paths |
| 3. Listener metadata/pending transition | PASS: listener observed document; contract exposes source/pending metadata | PASS: observed cache/server and pending true→false | Android assertion is less granular than printed Apple transition; metadata mapping compiles and is used |
| 4. Cancellation/disposal/no late callback | PASS: cancelled get stayed silent; listener removed | PASS: cancelled get stayed silent; listener removed | 500 ms post-cancel observation window; not a proof against every scheduler delay |
| 5. Pending-write wait and cancellation | PASS: drain completed | PASS: drain completed | Completion passed; artificially stalled timeout/cancellation remains **not proven** |
| 6. Terminate/clear/account fencing | PASS: old-epoch listener stayed silent; terminate/clear passed | PASS: old-epoch get stayed silent; real background/foreground read and terminate/clear passed | Actual device/simulator runtimes |
| 7. Restart/process recovery | PASS: Android atomic-file store recreated and recovered UID partitions | PASS: iOS atomic-file store recreated and recovered UID partitions; clean emulator restart obtained a fresh anonymous identity instead of reusing a stale local token | Host/native tests plus signed harness; encryption at rest remains **not proven** |

## Persistence prototype

One versioned JSON envelope stores `OfflineAccessGrant`, download manifests, workout recovery, mutation journal entries, pending uploads, and the switch marker. Files are partitioned by validated UID. Both platform implementations write a temporary file, flush it, and atomically rename it; recreation, isolation, deletion, and temporary-file cleanup tests pass.

Selected direction: a small app-owned atomic record set is sufficient to continue into a security/encryption spike; a second general-purpose database is not justified yet. It is not production-approved. Atomic rename was exercised, but crash injection between flush and rename, backup policy, OS data-protection class, application-layer encryption, key rotation, and secure erase were not proven.

## Verification ledger

| Gate | Final result |
|---|---|
| Locked backend install | PASS (`npm ci`; engine/audit warnings recorded) |
| Functions TypeScript build | PASS |
| Clean emulator suite startup/shutdown | PASS |
| Trusted-operation + Rules/Storage tests | PASS, 14/14 |
| Common metadata / iOS compilation | PASS |
| Android host tests | PASS, 58/58 |
| Android connected tests | PASS, 57/57 including native Firebase case |
| Android debug assembly and cold launch | PASS; Foundation Home remained default |
| iOS simulator shared tests | PASS, 57/57 |
| Native Xcode build | PASS with Firebase SPM `12.19.2` |
| Native Swift Firebase harness | PASS for all emitted checks, including lifecycle and fencing |
| iOS cold launch | PASS; Foundation Home remained default |
| Diff whitespace / conflict markers | PASS |
| Secret/generated/machine-file audit | PASS after ignoring Functions `lib`; tracked Firebase config contents were not printed or changed |

Warnings retained: Android SDK XML parser version warning; Kotlin framework bundle-ID inference warning; ICU object minimum iOS Simulator 18.5 versus app deployment 18.2; Xcode script phase always runs; Storage emulator Java `sun.misc.Unsafe` warning; Node 25 versus requested Node 22 warning.

## Remaining owner decisions and blockers

- Engineering/security must select and prove encryption/data-protection and backup behavior for the app-owned records before production data is stored.
- Engineering must induce and specify stalled pending-write timeout/cancellation semantics on both adapters before the sign-out protocol relies on a timeout.
- Product/security still own offline eligibility duration, locked recovery retention/export/discard, and destructive account-switch policy.
- Product/engineering still own concrete booking quantum/duration/buffer/window caps, workspace fan-out caps, assignment size/horizon/expiry, and retention.
- Platform/security still own Firebase environments, region/data residency, billing/budgets, service accounts, App Check, deployment, and release signing. None were touched here.
- Native Storage upload recovery remains deferred until an approved media/upload feature requires it.

## Evidence

- [iOS cold launch](evidence/ios-m3-cold-launch.png)
- [Android cold launch](evidence/android-m3-cold-launch.png)
- Backend, Gradle, Android instrumentation, Xcode, and sanitized `M3_FIREBASE_SPIKE` console results are summarized here; generated logs/build outputs remain ignored.
