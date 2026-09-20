# Milestone 3 report: Firebase environment, native parity, and persistence spike

Status: **locally accepted**

Date: **2026-09-20**

Branch: `feature/milestone-3-firebase-spike`

Milestone base: `7d0bf7dfd2a6cb4d23d5d72bb76b20e4ebb55f47`

Acceptance-correction start: `079a1a5698639e91aae2bbe9915d2f3725555ee3`

Second corrective review start: `9e657fac52eb4384b1ea63f3f5bf93ce40e4b6b2`

Third corrective review start: `7951445a2586535dab6eb9cc01b4d0fb4497da74`

## Outcome

Milestone 3 established an emulator-only Firebase backend, proved the selected booking, catalog-entitlement, assignment-publication and Rules primitives, exercised official Android and Apple Firebase adapters through native runtimes, and implemented encrypted UID-partitioned recovery persistence on both platforms. ADR-001 is accepted for official SDKs behind TillFailure contracts and the narrow Swift bridge.

This is local acceptance of the Firebase core/native-parity spike, not production approval. No Firebase cloud project or production resource was created or changed; nothing was deployed, merged, tagged or released; and no Milestone 4 behavior was added. Cloud ownership, production configuration, physical-device security, App Check, retention policy, release signing and deployment remain unresolved.

## Corrective review closure

First round (closed at `9e657fa`):

- Booking reschedule loads the stored appointment before replacement-bucket calculation and rejects trainer or client identity changes atomically. Tests prove the appointment/original locks remain unchanged, the alternate trainer receives no lock, and valid retries remain idempotent.
- Assigned-program publication puts the snapshot and every created `plannedWorkouts/{id}` path in a sorted, duplicate-free `requiredPaths` inventory. Zero/one/many-plan and failed-publication tests prove completeness and all-or-none commit behavior.
- Workspace restoration validates every computed entitlement count against zero and `maxMembershipsPerAccount`. Below-cap and exact-cap restores pass; above-cap restore leaves workspace, contributions, counts and authorization unchanged.

Second round (PR #3 review `pullrequestreview-5260762387`):

- Assignment replacement requires an explicitly supplied expected predecessor revision and reads the predecessor header and its workspace discovery index inside the publication transaction before any write. Missing predecessors, account/workspace/client/trainer identity mismatches, stale revisions, and terminal or already-replaced predecessors are rejected; receipt replay of a successful replacement still returns the original result without re-retiring anything. A deterministic `Promise.allSettled` concurrency test proves exactly one of two competing replacements of the same live predecessor commits, and the loser leaves no successor header, index, plan, manifest or receipt.
- A successful replacement retires both predecessor representations in the same commit: the account-owned header becomes `accessStatus=replaced`/`lifecycleState=terminal` and the workspace discovery index becomes `status=replaced`, each recording `replacedByAssignmentId`, `replacedAt` and the advanced revision. Trainer discovery queries then return only the successor. A missing, wrong-workspace, non-active, or revision-diverged predecessor index fails closed with no partial writes. The publication write budget counts both retirement writes (replacement adds 2, not 1) and the exact-budget test proves the ceiling.
- Membership activation enforces `maxMembershipsPerWorkspace` against the server-owned workspace `activeMembershipCount` while serialized through the expected workspace `membershipRevision`. Malformed, negative, and already-oversized counts, stale workspace revisions, and above-cap activations reject atomically with membership, workspace, entitlement, account and receipt state unchanged; below-cap and exactly-at-cap activations pass; replays never double-count. Suspension/restoration recomputes the same count, and a workspace filled to its cap remains atomically suspendable—the failure mode the finding described can no longer occur.
- Rescheduling writes every retained and newly acquired lock through the same lock writer initial booking uses, including the immutable `utcBucket` derived from the validated bucket calculation (never client-supplied) and the current `appointmentRevision`. Same-interval reschedules preserve every bucket identity; partially overlapping reschedules keep retained identities, give acquired locks the exact initial-booking field set, delete released locks, and replay/rejection leave lock documents byte-identical.

Third round (PR #3 review `pullrequestreview-5260982254`):

- **Callable authorization.** The Admin-SDK callable boundary no longer trusts authentication or request-supplied identifiers. `bookAppointment` reads the caller account, workspace, caller membership, and both participant accounts/memberships inside the booking transaction and requires an active account, active workspace, and active membership with a supported role; a trainer member may book only their own slots for an active client member, and a client member may book only themselves with an active workspace trainer. `rescheduleAppointment` and `cancelAppointment` enforce the same caller boundary, with cancellation additionally restricted to the appointment's stored participants. Authentication alone — including anonymous emulator identities — grants no booking authority; authoritative emulator fixtures are created only through isolated Admin-SDK test setup, never an authorization bypass. Authorization failures create no appointment, lock, or receipt, and every booking/reschedule/cancel receipt records the authorized `callerUid` so a different account can never replay or observe another caller's operation.
- **Plan reference integrity.** Publication builds the normalized set of snapshot workout IDs before the transaction and requires every `plans[*].workoutId` to reference exactly one of them. Absent, blank, or ambiguous references and blank/duplicated snapshot workout IDs reject the whole publication, so a ready assignment or complete-looking manifest can never contain a planned workout that cannot resolve its snapshot workout. The check is in-request only, bounded by the existing publication ceilings.
- **Guarded assignment closure.** `closeAssignment` is now a receipt-backed, revision-checked live-to-terminal transition: it requires the caller's expected header revision, reads the account-owned header and the workspace discovery index in the same transaction before any write, and accepts only an exact `ready`/`active` unexpired state with matching account/workspace/client/trainer/assignment identity and index revision. Previously replaced, archived, cancelled, revoked, expired, stale, or index-divergent assignments are rejected without incrementing revisions, rewriting terminal reasons or `replacedByAssignmentId`, changing timestamps, or creating a receipt; a different operation ID cannot close an already-terminal assignment, while the original caller-bound receipt replays a successful close.
- **Swift one-shot registry lifecycle.** `FirebaseNativeBridge` now pre-allocates the token and stores the cancellation entry exactly once before registering the SDK completion path (`beginOneShot`), and removes the entry from the registry when the completion settles — success or failure alike (`deliverOneShot`) — instead of retaining one closure per `getDocument`/one-shot `writeDocument`/`increment` until explicit cancellation or termination. A single `OneShotGate` serializes completion, cancellation, timeout, and termination so each callback is delivered at most once; a completion after cancellation is suppressed and a cancellation after completion is a safe no-op. Long-lived listener entries are still removed only on explicit disposal, and the harness proves registry non-growth, cleanup on success/failure/cancellation, suppression, terminate-time emptiness, and preserved epoch fencing through a Debug-only diagnostic.

## Runtime and dependency evidence

| Area | Exact version/evidence |
|---|---|
| Node.js | `22.23.2`, isolated `npx` runtime; Functions emulator reported `Using node@22 from host` |
| npm | `11.12.1`; locked `npm ci` completed |
| Firebase CLI | `15.30.2`; clean local startup and shutdown |
| Firebase Functions/Admin/JS | `7.4.0` / `14.4.0` / `12.19.0` |
| Rules unit testing / TypeScript | `5.0.2` / `5.9.3` |
| Firebase Android BoM | `34.19.0`, API 34 emulator runtime |
| Firebase Apple SDK | SPM `12.19.2`, signed iPhone 16e/iOS 26.2 simulator runtime |

`npm ci` reported deprecation notices for transitive `node-domexception`, `json-ptr`, `uuid` and `glob`, plus eight moderate audit findings. No `npm audit fix`, dependency update or lockfile change was made. Xcode retained the existing ICU minimum-simulator and always-run script-phase warnings; the Storage emulator retained its Java `sun.misc.Unsafe` warning.

## Backend proof

Final Node 22 emulator run after the third corrective round: **43 passed, 0 failed** (the first round's final run was 17/17 and the second round's 31/31).

| Primitive | Tests and result |
|---|---|
| Assigned publication | 14/14: atomic replay, deterministic complete zero/one/many-plan inventory, plan-to-snapshot reference integrity (absent/blank/duplicated/mixed rejection with no writes), revision-checked replacement retiring header and discovery index atomically with trainer-discovery exclusion, replacement replay without re-retirement, oversize all-or-none rejection, exact write budget counting both predecessor writes, stale/missing/malformed-request rejection with unchanged state, terminal and already-replaced predecessor rejection, predecessor/index identity and inconsistency rejection, single-winner competing-replacement concurrency, guarded live-to-terminal closure for each terminal reason, terminal/replacement-metadata immutability with receipt replay, and single-winner competing-close concurrency |
| Booking | 12/12: bucket coverage, first-booking contention, idempotent reschedule/cancel, immutable trainer/client rejection, transaction-budget rejection, same-interval reschedule `utcBucket` preservation, retained/acquired lock schema parity with initial booking plus removed-bucket deletion, lock stability across replay and rejected reschedule, participant authorization rejection matrix (anonymous/disabled/suspended/missing/revoked/cross-workspace/wrong-role/unrelated participant/forged fields) with no partial state, reschedule and cancellation authorization boundary with revoked-membership and non-participant rejection, caller-bound receipt non-replay, and fail-closed concurrent membership revocation |
| Callable | 4/4: unauthenticated and anonymous-without-authoritative-data rejection with no partial state, seeded trainer end-to-end authorization with idempotent replay and stored `callerUid`, different-caller receipt non-replay through the live Functions emulator, and forged-identifier/malformed-payload rejection at the boundary |
| Catalog lifecycle | 8/8: count lifecycle without drift on replay/revoke/restore, workspace/account transitions, fan-out rejection, below/exact/above-cap restoration, workspace-cap activation below/exactly-at/above cap with unchanged rejected state, independent account-cap and workspace-cap rejection, stale workspace revision and malformed/negative/oversized count fail-closed, at-cap suspension and restoration |
| Firestore/Storage Rules | 5/5: catalog, assigned snapshot/source denial, stable forged-owner denial, server-authoritative lifecycle/count/lock/index/receipt write denial, Storage owner/MIME/metadata/size checks |

The probes validate the primitives under configured bounds. They do not approve final product caps, booking-rights policy, or a deployed environment.

## Native parity matrix

| Case | Android actual | Apple actual | Attribution |
|---|---|---|---|
| Auth and accepted read/write | PASS | PASS | Android instrumentation; signed opt-in Swift harness |
| Rules rejection mapping | `PERMISSION_DENIED` PASS | `PERMISSION_DENIED` PASS | Client SDK paths only; no Admin bypass |
| Cache/pending metadata | Offline cache + pending true, then server + pending false PASS | Offline cache + pending true, then server + pending false PASS | Metadata-change listeners on both |
| Listener disposal | No callback after disposal PASS | No callback after disposal PASS | Native registrations removed |
| Stalled explicit cancellation | `CANCELLED`, exactly once; late SDK completion fenced PASS | `CANCELLED`, exactly once; late SDK completion fenced PASS | Networking disabled before valid local write |
| Independent stalled timeout | `DEADLINE_EXCEEDED`, exactly once PASS | `DEADLINE_EXCEEDED`, exactly once PASS | 300 ms configured harness timeout; deterministic latches/groups |
| Account-epoch fencing | Old-epoch completion/callback suppressed PASS | Old-epoch completion/callback suppressed PASS | Network re-enabled and current epoch drained |
| Terminate/clear and lifecycle | PASS | Real Settings background/foreground and terminate/clear PASS | Emulator/simulator runtime |
| One-shot registry lifecycle | Not rerun (Android bridge unchanged) | Success/failure/cancel cleanup, cancel-after-completion, suppressed late completion, at-most-once delivery, no growth across repeated reads/writes/increments, listener retention/disposal, terminate emptiness PASS | Signed opt-in Swift harness, third corrective round |
| Encrypted restart recovery | Android Keystore-backed AES-256-GCM PASS | Keychain key + CryptoKit AES-GCM PASS | Actual platform implementations, not common fakes |

The underlying pending-write SDK task is not claimed to be physically cancellable. TillFailure’s exactly-once gate suppresses late completion after cancel, timeout or epoch change.

## Recovery persistence

The common recovery record contract remains Firebase- and platform-type-free. Both implementations use an explicit version-1 encrypted envelope with key identifier `tillfailure.recovery.v1`, random nonce per write, UID/version/key-ID authenticated data, temporary-file flush and atomic rename. Existing unreadable data is authenticated before replacement or deletion; corruption, tamper, wrong/missing key and key loss produce a locked error rather than empty success.

- Android stores a non-exportable AES-256 key in Android Keystore and files under app-private `noBackupFilesDir`. API 34 instrumentation proved round trip/recreation, UID isolation, absent plaintext, unique nonce, envelope version/key ID, no-backup location, atomic replacement, temporary cleanup, tamper/wrong/missing-key lockout and preservation of unresolved files.
- Apple stores a random AES-GCM key in Keychain with `AfterFirstUnlockThisDeviceOnly`, writes app-private Application Support files with backup exclusion, and requests `completeUntilFirstUserAuthentication` Data Protection. The signed Swift harness proved the same round-trip, isolation, envelope, nonce, tamper/key-loss and preservation cases. The simulator accepted the protection configuration but returned no observable protection-class attribute; physical-device protection semantics are therefore not claimed.

Future rotation uses a new versioned key identifier, reads old envelopes with their matching retained key, atomically rewrites each UID partition under the new key, and deletes the old key only after audited migration completion. Full production rotation is not implemented. Hardware backing, secure erase and physical-device Data Protection are not claimed.

## Verification ledger

| Gate | Final result |
|---|---|
| Node 22 `npm ci`, TypeScript build, emulator start/stop | PASS (rerun for the third corrective round) |
| Trusted-operation and Rules suite | **43/43 PASS** (third corrective round; two consecutive clean runs) |
| Common metadata + Android/iOS compilation | PASS (shared Kotlin compiled inside the third-round Xcode build; Android not rerun) |
| Android host tests | **57/57 PASS** (retained from `9e657fa`; not rerun) |
| Android connected tests | **58/58 PASS** (retained from `9e657fa`; not rerun) |
| Android debug assembly/cold launch | PASS; Foundation Home default (retained from `9e657fa`; not rerun) |
| iOS shared simulator tests | **57/57 PASS** (retained from `9e657fa`; not rerun; no shared Kotlin change) |
| Native signed Xcode Debug build | PASS (rerun for the third corrective round) |
| Swift Firebase/recovery harness | PASS; 32/32 asserted checks including the new one-shot registry lifecycle cases, real Settings background/foreground, and terminate/clear (rerun for the third corrective round) |
| Android/iOS cold launch | iOS Debug cold launch rerun for the third corrective round: Foundation Home remained the default destination without the opt-in spike flag; Android retained from `9e657fa` |
| Diff/secret/generated/machine-path audit | PASS at the acceptance commit, the second corrective commit, and the third corrective commit |

The third corrective round changed `firebase/functions` TypeScript sources/tests, `iosApp` Swift bridge/harness sources, and documentation: no shared Kotlin, Android, build configuration, dependency, or lockfile changed. The iOS shared simulator test suite was therefore not rerun (its earlier 57/57 evidence is preserved with original attribution), while the signed Xcode Debug build, the opt-in Swift harness, and the iOS cold launch were rerun; the Android matrix is preserved from `9e657fa` and was intentionally not rerun.

## Remaining decisions and deferred work

- Native resumable Storage upload/process-death recovery remains deferred to the first approved media/upload milestone, currently Milestone 9 or 11. The safe pending-upload registry remains required; this is not a Milestone 3 acceptance blocker.
- Product/security still own offline eligibility duration, locked-recovery retention/export/discard, booking and fan-out production caps, privacy/retention policy and shared-device requirements.
- Platform/security still own Firebase project/environment ownership, region/data residency, billing/budgets, service accounts, App Check, deployment, production credentials and release signing.
- Physical-device Keystore/Keychain/Data Protection behavior and production restore drills remain release evidence, not simulator claims.

## Evidence

- [Android cold launch](evidence/android-m3-cold-launch.png)
- [iOS cold launch](evidence/ios-m3-cold-launch.png)
- Generated build/emulator logs remain ignored; exact commands and sanitized results are summarized above.
