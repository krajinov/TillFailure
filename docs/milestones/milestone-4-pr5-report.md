# Milestone 4 PR 5: departure, revocation and recovery hardening

Status: local emulator implementation with qualified verification. **Milestone 4 is not fully accepted.** Required interaction, product/privacy, physical-device and production gates remain open. No production operation, offline grant or Milestone 5 work is enabled.

## Baseline and scope

On 2026-10-16 the working tree was clean on `main`. Read repository/ancestor instructions (no applicable `AGENTS.md`; only a dependency's unrelated file), the Milestone 4 plan, PR 1–4 reports, offline/navigation/security documentation and shared/native identity, command, departure and persistence implementations. `git switch main` and `git pull --ff-only` succeeded; main was already current. HEAD was `b12b46797a63cf8c58842f284b02481435a2c8c4`, the merged PR #7 onboarding/shell change. No PR 5/recovery branch existed. Created the requested `feature/milestone-4-pr5-recovery-hardening`. Unrelated work was absent and no existing changes were overwritten.

The Android CLI skill supported native verification. No new recovery database, queue, account switch coordinator, receipt reader or privileged reconciliation mechanism was added. Backend/Rules product sources are unchanged.

## Existing mechanisms reused and concrete gaps closed

| Existing mechanism | Gap closed in PR 5 |
|---|---|
| Encrypted atomic UID envelope, native keys and pin | A pinned UID could verify server membership without proving that its protected registry remained readable. Role admission now inspects it first. Missing initialized records, unreadable/partial committed state and incompatible versions fail closed. Persisted schema tags are required; prior native envelopes already encoded defaults. Existing plaintext is validated before iOS replacement/deletion, matching Android's existing replacement protection. |
| Original invitation/onboarding command ledger | Pending reads could collapse a missing partition to an empty slot, and the ledger did not validate stored command shape before reuse. It now rejects malformed/unknown actions, identities, keys and payload shapes without replacement. Rejection/absence of a receipt still cannot settle a command. Settlement-storage failures are distinguished from transport failure. Ambiguous or invalid success cannot expose a reply that consumes a recovery link or signals completion. |
| Server identity gate and callback epochs | Connectivity restoration had no explicit trigger after a failed listener was removed. Android default-network callbacks and Apple `NWPathMonitor` now request fresh verification; loss closes presentation. Background/foreground fencing remains in the established lifecycle path. Connectivity supplies no authority. |
| Workspace selection and role stack disposal | Revalidation could silently pick the only remaining workspace after the selected one disappeared. The selected scope now stays denied until an explicit new choice verifies online. Cached preferences remain sorting hints. |
| Clean-departure coordinator, durable marker and process owner | Markerless cleanup retry now waits for observed Auth instead of briefly presenting sign-in; both native sign-in adapters refuse to replace any existing Auth user even before a pin is established. Reused unchanged ordering and pending-work blocking; recovery additionally rejects a nonpositive marker epoch. No command is dispatched while departure owns a frozen account. Pending preflight returns to same-UID recovery, then the user reattempts clean departure after reconciliation. |
| Shared gate and feature recovery UI | Added Locked Recovery admission/reasons and reconciliation feedback. Authority denial, service/connectivity failure and storage/reconciliation failure have different explanations. Original onboarding/invitation retries remain reachable without a verified role. Permanently unresolved work explicitly has no approved discard/automatic repair policy. |

No cached membership, profile, selection or receipt can authorize a shell. There is no eligibility duration, offline grant issuance, downloaded-workout access, destructive discard, forced cleanup, support export, automatic repair or production enablement. Journal/upload/download records outside this implemented command slice remain locked for an approved feature/reconciliation policy.

## State transitions

- **Online admission:** restore Auth/pin → inspect initialized protected registry → force fresh Auth → server account/directory/workspace/exact membership/profile checks → verified role root. Unreadable/incompatible/malformed storage goes to Locked Recovery before server admission. A damaged marker still goes to CleanupRequired before observing Auth.
- **Authority loss:** listener denial/disabled account/revoked membership/suspended workspace → cancel identity operations and advance epoch → remove role/workspace/profile presentation. With unresolved work, show Locked Recovery and retain the original UID/command. A late callback cannot reopen a role root.
- **Network/lifecycle:** background or connectivity loss removes protected presentation. Foreground/reconnect repeats fresh server verification. Unavailable service with clean state shows ConnectToVerify; unresolved work shows Locked Recovery with the connectivity reason. No offline fallback exists.
- **Pending departure:** freeze → inspect → unresolved work blocks preflight and unfreezes under the same Auth UID. Retry uses the original key/payload/revisions; any failed/ambiguous invocation keeps it. Only matching validated success plus a successful protected write removes the command. A receipt can request fresh verification, never grant access.
- **Clean departure:** freeze → prove empty critical work → drain SDK writes → persist marker → dispose/fence callbacks → Auth sign-out → terminate/clear SDK → prove/delete only empty local partition → clear pin/complete marker → replacement generation. Another account waits for this durable boundary. Any post-marker interruption/failure retains CleanupRequired; root recreation joins the existing process owner and process restart resumes its marker.
- **Permanent uncertainty:** retained unsupported work or unresolvable rejected commands stay locked. Retention duration, export/discard, trusted resolution authority and privacy obligations need owner approval. Repeated retry and an absent receipt are not proof that deletion is safe.

## Integrated scenario matrix

Fresh means this PR 5 working tree. Retained means the original report's dated run, not a PR 5 rerun.

| Scenario | Verification and limits |
|---|---|
| Clean sign-out and A → B | Fresh shared coordinator/root tests; fresh iOS native main harness confirms clean departure, B's server-verified trainer root and denial of A's account read. Android result recorded below. |
| Pending/ambiguous mutation blocks departure | Fresh ledger/root tests preserve original commands over repeated service/Auth/authority errors and failed settlement writes. Native main and cold-process probes retain original command identity; no receipt-at-one-instant cleanup. |
| Revocation while mutation is in flight | Fresh common tests fence old callbacks and keep recovery locked; fresh iOS main harness holds transport, observes live revocation, denies the original invocation, recreates the root and later resolves the original command after explicit fixture authority restoration. Android fixture-watch limitations and final result are below. |
| Account disablement and workspace suspension | Fresh common tests cover account/workspace/membership denial paths and clear role presentation. Fresh iOS live account-disablement probe passes. Separate native workspace results are recorded below. |
| Invalidated selected workspace | Fresh shared regression proves the remaining workspace is not selected automatically; explicit choice requires new server proof. |
| Background/foreground and network loss/recovery | Fresh common tests close presentation, suppress background reconnect admission and repeat fresh verification. Fresh iOS main harness proves suspend/foreground. Android's SDK network cycle and injected shared connectivity events are test stimuli, distinct from OS network-toggle evidence. |
| Root recreation | Fresh common tests retain pre-marker ownership behavior; new native root-store recreation retains the original pending command after loss. |
| Actual process restart during ambiguous mutation | Fresh iOS start/terminate/restore runs: original Auth UID and key restored without sign-in, departure remains blocked, original receipt reconciles, then clean departure. Android process results are below. |
| Interrupted departure at durable boundaries | Retained PR 1 Android four stage/resume pairs and iOS four stage/resume pairs cover marker, Auth sign-out, SDK teardown and local cleanup; these are not fresh PR 5 executions. Fresh shared tests cover each post-marker failure, unreadable/invalid-epoch markers and pre-marker owner adoption. |
| Late callbacks and B isolation | Fresh common identity/invitation/onboarding suites retain callback, disposed-root and foreign-UID regressions. Fresh iOS B server-denial check passes. PR 1–3 native late-completion/process evidence retains original attribution. |
| Damaged protected storage without silent deletion | Fresh common tests reject unknown versions, partial payloads and malformed commands with unchanged records. Fresh iOS platform-wrapper test prevents malformed plaintext replacement/deletion. Fresh Swift CryptoKit-backed incompatible-storage probe proves cleanup refusal and byte preservation. Android damage result is below. |
| Offline startup and fresh server revalidation | Fresh common tests require server origin, reject cached account/directory/profile evidence and route unavailable restoration to ConnectToVerify or Locked Recovery. Fresh native cold-process restores use server verification; they are online restart evidence, not a physical offline-startup acceptance claim. No grant writer was added. |

## Final review and publication

The requested finalization started on the expected branch at `b12b46797a63cf8c58842f284b02481435a2c8c4`, with the original 30 intended files unstaged and an empty index. Fetch and remote inspection confirmed `origin/main` at the same commit, no existing remote PR 5 branch and no existing PR for this branch. Reviewed the entire tracked diff and all five new files, the plan/report and repository instructions. No unrelated work was present; history was not reset or rewritten.

Concrete final-review fixes:

- Final role/selection publication now uses the existing departure-owner lock and a departure generation captured before verification. A lease during registry inspection prevents admission; even a departure that settles before publication invalidates the earlier proof. A root joins the existing owner or restarts ordinary server verification. The owner-lock callback only publishes state, avoiding native/storage lock inversion.
- Host foreground is an explicit lifecycle event. Command completion/retry and late Auth callbacks cannot reopen a background root. Replaced Auth/connectivity observers are cancelled and fenced by observation generation, including synchronous registration callbacks; duplicate connectivity events do not cancel a newer proof. Native monitors refuse registration on retired clients and dispose queued callbacks.
- iOS preference read-modify-write and protected-registry initialization now run under the bridge's existing issuance/freeze lock, shared by recreated Kotlin wrappers. Both native adapters require the current Auth UID and a live, unfrozen account for initialization/preference writes, preventing foreign-owner or post-cleanup resurrection. No second coordinator or persistence system was introduced.
- Native reply decoding preserves an ambiguous outcome even with a syntactically valid success. Mutation preparation refuses a missing idempotency key. Existing settlement-storage failures continue to preserve the original command. Prior positive invitation versions remain readable, including the highest representable matching receipt; arbitrary new upper bounds cannot lock a valid older command.

Literal PR 1/2, PR 3 and PR 4 envelope shapes now have compatibility regressions without migration or writes. New identity regressions cover background completion/Auth, replaced and disposed observers, duplicate network events, a departure leased during final inspection, and one that settles during inspection. Existing damaged/partial/schema, failed-settlement, pending/rejected-command, selection and A/B fencing tests remain active.

The integrated Android native test still asserts original key/payload retention, role closure on current denial, unchanged Auth on blocked departure, successful matching settlement and isolated B reads whether its retained server profile starts pending or complete. It now also asserts the denied attempt returns an error, ambiguity and no reply, and that recovery of an already-complete fixture reports replay. A pending-profile snapshot permits either valid replay flag because a prior invocation can commit after that read. These assertion additions are reviewed/compiled; the earlier integrated pass below retains its original run attribution. No profile, receipt or pending command was reset to create a fresh-first-write claim. Android integrated revocation/suspension evidence is explicit server revalidation; the earlier Swift main revocation/disablement evidence is live notification. They are not interchangeable.

Fresh final-review results, separate from the implementation-stage runs below:

- Shared Android **144/144** and iOS simulator **145/145**, zero failures/errors/skips. Android Debug/Release/device-test assembly passed after the final source changes. Commands: `./gradlew :shared:testAndroidHostTest :shared:iosSimulatorArm64Test :shared:assembleAndroidDeviceTest :androidApp:assembleDebug :androidApp:assembleRelease` with JDK 21.
- Final signed iOS simulator Debug build passed. Native checks from that final app reported **12 guard/disposal PASS and 8 frozen-account PASS**, zero FAIL. The frozen rerun restored its existing dedicated Auth owner, so it does not count a new sign-in label; all storage/freeze/command assertions executed. The preceding first run had 9 PASS including its initial sign-in. These are native SDK/CryptoKit-backed checks, separate from the old main harness and touch acceptance.
- Final Android API 34 focused native checks: **2/2 passed** from the final device-test APK. No-Auth/retired initialization and preferences are refused; queued connectivity callbacks are fenced after double disposal; freeze preserves encrypted bytes; unfreezing and saving a preference preserves the exact pending command and continues to block departure.
- Native freeze probes issued no remote command: transport was withheld after durable preparation. Only two new dedicated `.invalid` Auth fixtures were created in an Auth-only emulator imported from the prior snapshot; older identities, Firestore state and device partitions were untouched. The new fixtures intentionally retain their original protected command and Auth owner.
- Final iOS **Release built successfully** (unsigned simulator), sequentially after Debug. The Release executable contains no PR 5 Swift harness name/labels, and Release Android dex contains no instrumentation test class; host entry points remain `#if DEBUG` / `BuildConfig.DEBUG`. The isolated Auth emulator was gracefully exported and stopped.

Final-review verification results are also recorded separately in the verification ledger. Backend/Rules sources remain unchanged, with no new backend concern identified; those suites were not rerun. The broken iOS interaction surface was not retried. All interaction, OS connectivity, physical-device, product/privacy and production gates below remain open. A clean source review does not accept Milestone 4 and issues no offline grant.

## Implementation-stage verification (original attribution)

The following implementation-stage runs were performed on 2026-10-16 before the final review above. Their original results and qualifications are retained; they are not final-review reruns. Raw logs, fixture passwords, bearer payloads, screenshots, simulator IDs and build output are excluded from this diff.

- Initial final shared/build invocation passed **135 Android host tests and 136 iOS simulator tests**, zero failures/errors/skips, plus Android Debug/Release and instrumentation APK assembly. The extra iOS test covers incompatible/partial native plaintext. Later marker-epoch, required-schema-tag, markerless cleanup/active-Auth admission regressions and copy changes receive the final confirmation below.
- iOS Debug simulator build with the new Swift harness passed. The fresh main harness reported **21 PASS / 0 FAIL**, including real Swift SDKs/protected storage, in-flight revocation, original recovery, live disablement, foreground verification, A/B isolation and incompatible ciphertext preservation.
- iOS actual cold-process check reported two staging PASS markers plus `RESTART_READY`; after explicit termination, a separate launch reported **5 PASS / 0 FAIL** for Auth/key restoration, blocked departure, original receipt and ordered cleanup. No second sign-in or protected reset occurred.
- Backend/Rules product sources did not change; their suites were not rerun for client-only changes. PR 4's **154/154 backend/Rules** and PR 3's **144/144** retain their own original attribution. No current native fixture run substitutes for those suites.

### Final confirmation and native continuation

- Final shared suites: **137 Android host tests, 138 iOS simulator tests**, zero failures/errors/skips. This run includes the required schema-tag and markerless-cleanup admission regressions. Android Debug/Release and device-test assembly passed. The subsequent native-only redacted diagnostic assertion also compiled successfully.
- Final iOS Simulator **Debug and Release both built successfully**, sequentially after the final shared/admission changes. Debug used the simulator signing configuration; Release was unsigned. Product emulator operations remain disabled in Release.
- Android API 34 integrated native probe: **1/1 passed** with all demo services live. It covers in-flight original-command replay, confirmed revocation/reconnect denial, blocked departure, disposed/recreated root, SDK network cycle with shared connectivity stimuli, original recovery, foreground verification, account disablement, ordered clean departure and server-isolated B. Prior partial attempts had already committed the original completion and disabled its account fixture. The probe now accepts its actual server pending/complete starting state and preserves the same key/receipt; only the explicit account-status test precondition was re-established. This is recovery against retained state, not a fresh first-write or OS network-toggle claim.
- Android API 34 damaged-storage probe: **1/1 passed**; encrypted bytes and Auth owner remain intact, the root is Locked Recovery and local cleanup refuses deletion.
- Android actual process stage/force-stop/restore: **1/1 stage and 1/1 final restore passed**. The original Auth UID and protected key restored without sign-in, the matching receipt reconciled and clean departure completed. Unavailable intervening restore attempts retained the original command and are not counted as successful replay.
- Android API 34 workspace-suspension probe: **1/1 passed**; open root → confirmed fixture suspension → explicit SDK network/revalidation cycle → current server denial → ordinary clean departure.
- iOS workspace-suspension continuation: **3 PASS / 0 FAIL**. A separate process restored the retained Auth owner, received current server denial for the already committed suspension and performed ordinary proven-clean departure. This is fresh cold-process/server-revalidation evidence; the earlier live-notification attempt failed and is not counted as a live suspension pass.

### Failed attempts and tooling limits

The initial Gradle/SDK/simulator calls were restricted by the filesystem sandbox; authorized native/build calls then ran with the required cache/device access. An old shared test expected another UID's missing registry to mean empty; it now expects a failed read. The passing suites include this correction.

A temporary config outside the repository misresolved the Functions source. The isolated demo dataset was exported and imported with a corrected disposable repository-local config; later fixture mutations/receipts were exported again before restarting the owned services. No other emulator project or prior native dataset was reset. Native Functions used host Node 24 (the emulator warned about the requested Node 22 mismatch); this is not a new Node 22 backend-suite result.

The first iOS combined lifecycle run passed in-flight recovery and disablement, then timed out in Loading after restoring the fixture account. Android's first fixture revocation writes returned HTTP 500. Firestore logged `Stream is already completed, no further calls are allowed` during listener notification; writes could already be committed. The native fixture helpers now check the actual server field rather than treating an absent response as non-commit. Lifecycle probes are separated, and Android reconnect stimuli explicitly repeat server verification. Failed runs are not counted as passes; the original failed-probe partitions and server records were preserved in their original namespaces. No app repair/discard path was added.

Later service inspection found Auth, Functions and the hub had stopped while the owned Firestore emulator remained alive. This explains the later `unavailable` Android replay and iOS ConnectToVerify continuations; those failures are separate from the earlier closed-stream/HTTP-500 watch error. With the hub unavailable, the latest Firestore dataset was exported directly through the local emulator export API before stopping its exact owned process. Restart imported that current dataset and the prior Auth snapshot. Only the ten missing fixed `.invalid` PR 5 Auth identities were recreated; existing identities, Firestore receipts/profiles/counters and all device partitions were preserved. Both Android's original replay and iOS's suspended-workspace revalidation then passed without new device sign-in. This is synthetic fixture maintenance, not a product account-recovery or privileged reconciliation policy.

A later Android integrated rerun initially expected Onboarding even though the retained original command had already committed and its earlier test step had disabled the synthetic account. The final probe resumes pending/complete server state with the original key and receipt; only the named account-status fixture precondition was re-established. It does not reset revisions, replace a pending payload or alter a receipt.

An overlapping iOS Release invocation was cancelled so builds sharing Gradle outputs could be confirmed sequentially. It is not recorded as a successful Release build.

The outstanding PR 4 iOS completion → Home → Profile → Back, keyboard submission/dismissal and large-text reachability checks were attempted through the available Simulator automation surface. Its initial app inspection took approximately 20 minutes to return; it eventually identified the dedicated TillFailure simulator but supplied no completed app interaction evidence. Stopped further UI automation attempts against the slow surface. These touch/accessibility gaps remain open; SDK/harness results do not close them. The earlier PR 4 normal-size keyboard visibility/Next and Android normal/200% interaction evidence remain attributed solely to the PR 4 report.

## Retained evidence and remaining acceptance gates

PR 1's native departure-boundary/late-callback and SDK teardown checks, PR 2 operator verification, PR 3 invitation process/replay/entry-isolation evidence and PR 4 onboarding/process/native interaction evidence are retained in their original reports without changed attribution. Earlier counts are not added to fresh PR 5 totals.

Native OS network-toggle/offline-startup acceptance and reliable live Android revocation-notification execution remain qualified by shared, explicit reconnect and retained native evidence; no new physical-radio or full offline-startup claim is made.

Still required: iOS full completion/shell/Back touch and keyboard sequence, large-text lower actions, OS Dynamic Type and VoiceOver; full Android invitation mutation/confirmation/acceptance walkthroughs and TalkBack; physical Android/iOS key/storage/backup/shared-device behavior; product/privacy approval of data purpose/access/retention and permanently unresolved work; production Auth/provider recovery, trainer trust/operator authority, invitation delivery/domain/abuse/expiry/caps, infrastructure ownership, residency, monitoring and release controls. No production acceptance or full Milestone 4 acceptance is claimed.

## Exact changed files

33 intended changed files: the original 30 plus the departure-owner implementation and the Android/Swift PR 4 lifecycle probes updated to use the explicit foreground event. The finalization prepares one cohesive commit and normal branch push/PR against main at the user’s request. No merge, deployment, production enablement or Milestone 5 work is authorized or performed.

- `androidApp/src/main/AndroidManifest.xml`
- `docs/firestore-security.md`
- `docs/milestones/evidence/milestone-4-pr5/verification.json`
- `docs/milestones/milestone-4-plan.md`
- `docs/milestones/milestone-4-pr5-report.md`
- `docs/navigation.md`
- `docs/offline-sync.md`
- `iosApp/iosApp/FirebaseNativeBridge.swift`
- `iosApp/iosApp/OnboardingHarness.swift`
- `iosApp/iosApp/RecoveryHardeningHarness.swift`
- `iosApp/iosApp/iOSApp.swift`
- `shared/src/androidDeviceTest/AndroidManifest.xml`
- `shared/src/androidDeviceTest/kotlin/com/delminiusapps/tillfailure/firebase/AndroidOnboardingTest.kt`
- `shared/src/androidDeviceTest/kotlin/com/delminiusapps/tillfailure/firebase/AndroidRecoveryHardeningTest.kt`
- `shared/src/androidMain/kotlin/com/delminiusapps/tillfailure/firebase/AndroidFirebaseSpikeClient.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/identity/CleanDeparture.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/identity/CleanDepartureOwnership.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/identity/IdentityRoot.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/identity/IdentityViewModel.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/identity/ProductIdentityClient.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/invitations/InvitationClient.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/invitations/InvitationScreen.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/invitations/InvitationViewModel.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/onboarding/Onboarding.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/onboarding/OnboardingScreen.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/persistence/PersistencePrototype.kt`
- `shared/src/commonTest/kotlin/com/delminiusapps/tillfailure/identity/CleanDepartureTest.kt`
- `shared/src/commonTest/kotlin/com/delminiusapps/tillfailure/identity/IdentityViewModelTest.kt`
- `shared/src/commonTest/kotlin/com/delminiusapps/tillfailure/invitations/InvitationTest.kt`
- `shared/src/commonTest/kotlin/com/delminiusapps/tillfailure/invitations/RecoveryHardeningTest.kt`
- `shared/src/iosMain/kotlin/com/delminiusapps/tillfailure/firebase/IosFirebaseBridge.kt`
- `shared/src/iosMain/kotlin/com/delminiusapps/tillfailure/persistence/IosAtomicFilePersistence.kt`
- `shared/src/iosTest/kotlin/com/delminiusapps/tillfailure/persistence/IosAtomicFilePersistenceTest.kt`

## Reproducing the focused native checks

Use only dedicated disposable `demo-tillfailure-m3` services: Auth 39099, Firestore 38080, Functions 35001 and Storage 39199 on loopback. Supply fixture passwords at runtime. A disposable copy of the existing create-only `seed-pr4.mjs` with fixture prefixes `pr4`/`PR4` changed to `pr5`/`PR5` produces the PR 5 native/UI/large/trainer identities and workspace using the existing operator/invitation primitives. It refuses reused fixtures; do not reset retained protected data to rerun a failed check. A test namespace isolates independent probes and preserves failed probes in their original namespaces.

Android: assemble/install the device-test APK and select `AndroidRecoveryHardeningTest` methods explicitly. The main method is `revocationInFlightRevalidationRetainsOriginalUntilAuthorizedReplayThenIsolatesB`; storage is `damagedStorageLocksTheRootAndPreservesBytes`; process is `processBoundary` with `pr5Phase=start` then actual force-stop and a separate `pr5Phase=restore` instrumentation process; suspension is `workspaceSuspensionClosesAnOpenRoot`. Supply `pr5Password`. Each fixture mutation confirms the actual server field, including after an emulator post-commit notification error. Reconnect includes an actual Firestore SDK network cycle plus explicit shared connectivity events; it is not an OS radio-toggle assertion.

iOS Debug: use `TF_PR5_NATIVE=main`, `start`, `restore`, `suspension` or `suspension-restore`, `TF_PR5_PASSWORD`, and matching `TF_AUTH_EMULATOR_PORT`, `TF_FIRESTORE_EMULATOR_PORT`, `TF_FUNCTIONS_EMULATOR_PORT`. Wait for `RESTART_READY`, terminate the process and launch `restore` without signing in. `suspension-restore` verifies the same retained Auth owner against the already committed fixture suspension after a failed notification attempt; it does not write or repair authority. Harness code is Debug-only and reports booleans. No native diagnostics implement a product resolution/discard API.

Final-review native controls use only Auth on the same isolated demo port. Android methods are `unauthenticatedAndRetiredClientsCannotCreateProtectedStateOrDeliverDisposedConnectivity` and `frozenAccountCannotRewritePreferenceOrReinitializeProtectedState`; iOS phases are `TF_PR5_NATIVE=guards` (no password needed) and `frozen` (runtime password). Create only the dedicated `pr5_final_review_android` / `pr5_final_review_ios` synthetic Auth fixtures with their matching `.invalid` emails. The frozen probes withhold transport entirely, compare encrypted bytes while frozen, refuse reinitialization and retain the exact command after unfreezing/preference update. iOS uses a recreated Kotlin wrapper over the same native bridge. These namespaces intentionally keep their unresolved test command and Auth owner. They do not run backend mutations, reconcile a receipt, prove OS network switching or erase older test partitions.

## Final diff audit

Reviewed every changed source/test/document and the exact inventory, including new files. `git diff --check` and the new-file trailing-whitespace/final-newline scan passed. The diff contains no production credentials, private keys, bearer links, real customer data, raw native logs/screenshots, machine-specific paths or generated build output. The only fixed Admin bearer literal is the Firestore Emulator's test-only `owner` value in opt-in fixture injection; it is not a production credential or product recovery route. Temporary emulator configuration and fixture-maintenance scripts were removed from the source tree after exporting the complete test dataset. Existing reports retain their original evidence and attribution. The implementation-stage handoff left HEAD at the baseline with an empty index. Final review preserves that evidence and prepares only the inventoried changes for the requested cohesive commit and normal push; the resulting commit and PR are identified by Git/PR metadata.
