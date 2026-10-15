# Milestone 4 PR 4: minimal onboarding and role-aware navigation

Status: local emulator implementation; verification ledger below qualifies acceptance. Production collection policy remains unapproved. No production writes, deployment, offline grants or PR 5 work are enabled.

## Baseline and boundaries

- Started on the existing clean `feature/milestone-4-pr4-onboarding-shell` at `d4ed91a8f6e928bb5961fb270acd63f1f4c4d8fe` (PR #6).
- Switched to clean `main`, ran `git pull --ff-only` (already up to date), verified the requested merge commit is an ancestor, recorded the same HEAD, and returned to the existing PR 4 branch. No duplicate branch or PR was created.
- Read the milestone plan, PR 3 report, navigation/schema/security/offline documentation and relevant shared/native/backend code. No repository `AGENTS.md` was found in the checkout or its ancestor directories.
- Pencil's live connection returned `transport not connected to app`. Read the checked-in `design/tillfailure.pen` as a read-only fallback: `YLmfR`, `Z03aYo`, `OmHDs`, `eq0D5`, and the existing design inventory. The design file was not changed. Reused shared tokens, form fields, cards, buttons and responsive navigation. Visual DOB/phone, injury, health, body measurement, uploads, workout and trainer fixture data are excluded.
- The original implementation handoff left all 48 files uncommitted and unstaged. On 2026-10-15 the owner explicitly authorized final review, one cohesive commit, normal push of this existing branch and a PR against `main`. Merge, deployment, production writes, offline grants and PR 5 remain excluded.

## Fixture fields, ownership and trusted completion

The plan's minimum is still a proposal. `ONBOARDING_FIXTURE_POLICY` version 1 is an emulator development contract, not product/legal approval:

| Record | Fields owned by this operation |
|---|---|
| Global `users/{uid}` | `displayName` (trimmed, 1–80 UTF-16 units, valid Unicode, no control characters), `locale` (bounded BCP-47 subset, at most 35), `timeZoneId` (IANA zone/UTC, at most 64), `profileRevision` |
| `workspaces/{wid}/clientProfiles/{uid}` | `onboardingStatus=complete`, `onboardingRevision`, `onboardingPolicyVersion=1`, server `completedAt` |
| Admin-only `lifecycleCommands/{caller-bound digest}` | Kind, caller/workspace, payload hash, safe committed result and server time |

Explicit acknowledgment is required in the request. It is an onboarding completion action, not a legal-consent flow. No goals, experience, constraints or other sensitive coaching fields are collected. Account-level basics are shared by the account's workspaces; each client membership completes its own onboarding. Trainer onboarding/editing remains outside this slice.

The narrowly guarded `onboardingEmulator` repeats the demo project and loopback Auth/Firestore requirements before SDK acquisition. The shared native transport selects this endpoint only for `complete-onboarding`. Release hosts remain unconfigured for product Firebase. Deploying the export cannot bypass its emulator guards.

The callable UID comes exclusively from verified callable Auth. Each invocation, including receipt replay, reloads Admin Auth and requires a current, verified, enabled email identity; active schema-current account, workspace, matching client membership and directory; immutable UID/workspace/profile identity; recognized profile shape; valid revisions; and permitted field types/bounds. The global account's normalized email must match current Auth. Additional input keys, role/member/entitlement/counter changes, cross-workspace/cross-account paths, unsupported or malformed records are rejected. Profile fields are strictly allowlisted; unrelated account fields are preserved with an update rather than replacement.

PR 2/3 intentionally created `displayName=""` without locale/time zone/revision. That exact empty bootstrap shape (or all three absent) is an explicitly supported revision-zero starting state. Missing revision with nonempty/partial basics fails closed. Similarly, the existing PR 3 pending client-profile stub has onboarding revision zero. Neither a restore nor a membership recheck writes these records or resets completion.

Both expected global profile revision and expected workspace onboarding revision are supplied. One transaction updates the two documents and creates the receipt. Matching same-caller/same-payload retries return the original result after current authorization checks without another write. Changed payload/key reuse, stale revisions, concurrent different commands, malformed receipts or a new command against completed onboarding cannot overwrite data. Membership, workspace lifecycle, entitlement and roster/contribution counters are unchanged.

Rules permit only the active owning client's point-read of the exact minimal profile shape. All client profile writes and lists are denied, as are direct global account writes. Future trainer profile access/coaching projections require a separate approved policy. No index change was needed.

## Routing, scopes and navigation

- Fresh Auth/account/directory/workspace/membership checks precede every role root. Native adapters preserve relevant scalar type failures rather than authorizing a malformed flattened value.
- One active client membership reads its own server profile: pending enters onboarding; complete plus valid global basics enters client Home. Missing, cached, pending-write, malformed or foreign profiles stay gated.
- A verified trainer reaches minimal trainer Home and the working invitation route.
- Multiple directory entries are all path-checked and point-read before presenting workspace/role choices. Selection re-runs the fresh gate. Missing/revoked/malformed choices cannot route to a shell.
- Workspace preference is stored under the encrypted UID envelope as a sorting hint only. It never auto-selects a membership; a stale hint is ignored. Its read/modify/write uses the same ledger lock as protected commands.
- Serializable Navigation 3 keys select only UI pages; the current identity state always controls rendering. Root replacement includes an authority epoch. Home/Profile navigation is scoped by UID, workspace, role and epoch; old destination stores are disposed. Feature generation checks and immediate presentation scope checks fence late callbacks and stale frames.
- Background closes protected content and cancels identity reads/listeners; foreground re-verifies online. Cleanup/switch states cannot be bypassed by foreground retry. Root recreation starts verification again; no saved route or preference grants access. Back from Profile/Invitations returns to Home; no Auth/onboarding history remains after root transition.
- Shells expose Home, Profile/access and invitations/recovery only. Future workouts, programming, scheduling, progress and messaging have honest explanatory copy, no fabricated metrics/assignments and no future tabs.

## Protected completion and departure

The existing protected command protocol and encrypted, UID-partitioned envelope are extended with the typed completion payload. Legacy `InvitationRequest`, `PendingInvitationCommand`, `InvitationLedger` and `pendingInvitationCommands` names remain for source/on-disk compatibility; their one-command slot now also protects onboarding. This is the same command registry/transport/freeze/clean-departure mechanism, not another queue or synchronization system.

The original UID, workspace, key, revisions, fields and acknowledgment are durably prepared before network issuance. A retry reconstructs that exact request; another draft cannot replace it. Native cancellation fences presentation, while native completion can still settle the matching durable command after the old ViewModel has cleared. Settlement validates the response identity and revisions before removing work. Ambiguous, rejected, malformed or conflicting results remain protected across root/process restoration and block clean departure. A recreated root observes ledger changes and requests the ordinary server gate; a receipt alone never grants a role.

Retry/recovery is available even after access is lost. Invitation recovery remains reachable independently of onboarding; an unresolved invitation does not disappear into the new shell. No discard, forced reset, automatic conflicting-command replacement or privileged reconciliation policy is added. A permanent conflict can therefore require trusted reconciliation; this is explicitly shown, with sign-out blocked. PR 5 remains deferred.

## Verification ledger

Pre-review implementation results on 2026-10-14, retained with their original attribution. The final review fixes and fresh checks are recorded separately below:

| Check | Result / provenance |
|---|---|
| Pre-review shared Android host suite | 123 tests, 0 failures; includes routing, workspace hints/selection, fencing, completion validation/replay and recovery |
| Pre-review shared `iosSimulatorArm64Test` | 123 tests, 0 failures; Kotlin/platform persistence coverage, not Swift SDK or touch evidence |
| Android Debug and Release; native-test APK compilation | Passed with the final shared suites (`:androidApp:assembleDebug :androidApp:assembleRelease :shared:assembleAndroidDeviceTest`) |
| Full backend/Rules under Node 22.23.2 | 154 tests, 0 failures, 0 skipped, including new onboarding transaction/callable and profile Rules coverage |
| Earlier full backend/Rules host-runtime run | 152/152 under Node 25; retained separately from the subsequent Node 22 result |
| iOS Simulator signed Debug | Passed; pre-review same-source confirmation also passed on 2026-10-15 |
| iOS Simulator Release | Passed; pre-review same-source optimized confirmation also passed on 2026-10-15 |

Earlier backend/native fixture runs used separate demo ports (Auth 19099, Firestore 18080, Functions 15001, Storage 19199). The final isolated 154-test backend/Rules run used Auth 29099, Firestore 28080, Functions 25001 and Storage 29199; it did not reset the native fixture dataset. All used loopback demo emulators, avoiding the pre-existing default-port Firestore instance. Callable tests now honor configured Auth/Functions ports. Initial attempts failed because the default Firestore port was occupied, old tests hard-coded default callable ports, and a temporary config outside the repo could not resolve Functions/Rules paths. The final repo-local disposable config loaded every callable and passed the whole suite. An earlier 152-test Node 22 run itself passed before a separate fixture-seeding key collision failed; the platform-specific seed key was corrected and fresh fixture seeding then succeeded. No failed attempt is presented as a passing run.

Early shared runs exposed the unverified-email gate ordering and an older ledger test's incomplete synthetic receipt. The gate ordering was corrected, the test now supplies a structurally valid matching receipt, and final suites pass. Native compilation also caught a Kotlin declaration typo and a missing Foundation category-property import; both were corrected before the passing APK/shared runs.

The new Android native test and opt-in Swift harness use real SDKs, protected stores, the original command and blocked departure, then verify completion/replay and recreated-root routing. Compilation alone is not their execution. Fresh native execution and interaction results must be attributed separately below.

### Native execution and continuation evidence

| Date | Check | Result / limits |
|---|---|---|
| 2026-10-14 | Swift Debug native onboarding probe, iOS 26.2 simulator | 13/13 PASS: real Auth/callable, original durable command, blocked departure, cleared/recreated root, completion/replay, client Home restoration, suspend/recheck, clean sign-out. This is SDK/harness evidence, not touch evidence. |
| 2026-10-15 | Android API 34 native onboarding instrumentation | 1/1 passed in 15.177 seconds. Exercises real SDK/store, original command after cleared root, blocked departure, completion/replay, restored Home, suspend/recheck and clean sign-out. |
| 2026-10-15 | Swift actual cold-process recovery | 5/5 PASS: no new sign-in in restore phase; original Auth UID and pending key restored, departure still protected, original receipt reconciled, ordered clean sign-out. |
| 2026-10-15 | iOS Debug and optimized Release confirmation | Both `BUILD SUCCEEDED`; simulator builds, not physical-device builds. Debug recovered existing SwiftPM repository-cache warnings. |

The previous day's Firestore fixture profiles, receipts and counters survived. Only missing named PR 4 emulator Auth identities were recreated after the Auth process stopped; existing Auth identities were checked and preserved. Firestore and protected device partitions were not reset. This is local fixture maintenance, not production account provisioning.

An earlier Android execution was interrupted by the tool approval service's usage limit; the continuation executed successfully. An iOS restart-start attempt produced no readiness evidence. A subsequent start correctly failed sign-in with an already pinned identity; recovery used the existing identity instead of clearing it, and the cold-start restore assertions above passed. Failed attempts are not counted as passes.

### Actual interaction evidence, 2026-10-15

Android used the dedicated API 34 device and real emulator fixture accounts, with UI hierarchy inspection, taps, per-character typing, slow swipes and visible software keyboard. Normal text: sign-in, three onboarding inputs, keyboard Next from name to locale to time zone, Back to dismiss the keyboard, missing-acknowledgment validation, acknowledgment and completion, client Home, Profile and native Back to Home passed. A real force-stop/relaunch returned to completed Home after fresh verification. Profile clean sign-out returned to the sign-in gate.

At Android **200% system font size**, sign-in inputs and keyboard dismissal passed. The onboarding form scrolled to the lower fields and completion action; all three synthetic values were entered, the acknowledgment and completion action were tapped. The viewed screenshot confirmed wrapping and reachable controls. Final routing is recorded in the evidence file. Profile, native Back to Home, and clean sign-out also passed at 200%. The temporary `font_scale` override was removed; Android reported the restored default `1.0`. Bulk ADB text initially truncated an email, so successful input evidence uses verified per-character entry; bulk paste is not accepted by this check.

Android trainer at 200% reached trainer Home and the working invitation entry. Client-email entry, the visible software keyboard, Back dismissal, the reachable Create action’s invalid-email validation, and native Back to trainer Home passed. After restoring normal text, trainer Home and the invitation entry were checked again; the previous draft was cleared after root recreation, and Profile clean sign-out returned to SignedOut. Full invitation create/confirmation/resend/revoke interaction is still a separate open item.

iOS normal-size actual UI reached onboarding after sign-in, entered all three fields, showed the software keyboard, moved focus with keyboard Next, and correctly rejected completion without acknowledgment. Further touch completion/Profile/Back and large-text checks could not be finished: the computer-use service returned invalid or expired accessibility element IDs and remained stuck on a Simulator menu even after reconnect/reset. These steps remain open. The SDK and cold-start probes above do not close those touch gaps.

Retained PR 3 evidence remains solely in the PR 3 report. No prior invitation-process/harness/touch result is recounted as a fresh PR 4 onboarding result. In particular, the full Android trainer invitation mutation/confirmation and client invitation preview/acceptance walkthroughs remain open; the new client onboarding walkthrough closes only the corresponding sign-in/onboarding/Home/Profile items. iOS trainer/preview lower-action touch scrolling at 200%, native Back, OS Dynamic Type and VoiceOver/TalkBack remain open. Software-keyboard visibility and Next were observed only for the normal-size iOS onboarding inputs, not the complete invitation flow.

Sanitized results are in [verification.json](evidence/milestone-4-pr4/verification.json). Raw SDK logs, screenshots, emulator configuration, credentials and generated build output remain outside the source diff. Acceptance is qualified by the listed interaction and production gates.

## Final review for publication, 2026-10-15

Confirmed the expected branch and starting HEAD above; remote `main` is the same commit, the feature branch had not been pushed, and no existing PR was found. Re-read repository instructions, the plan/report and all 48 tracked/untracked changed files. No unrelated work was present. Reviewed the fresh Auth/Firestore proofs, global/workspace ownership, transaction/revision/receipt behavior under competing commands, conservative settlement, envelope compatibility, directory/selection/hints, scope disposal, listeners/lifecycle and Debug/Release separation.

Two concrete client preflight defects were fixed before publication:

- **Duplicate BCP-47 variants:** the local syntax check accepted locales such as `en-1901-1901` or case-equivalent `sl-rozaj-ROZAJ`, while server `Intl.Locale` rejects them. Preparing those requests would leave a rejected original command protected. The shared validator now rejects repeated variants case-insensitively before command preparation. The regression checks no request/pending command, and still accepts distinct supported variants.
- **Unincrementable revisions:** a currently valid record at the service revision ceiling could enter onboarding, but its completion would be rejected after local preparation. Complete now checks both expected revisions against the service's incrementable range before generating a key or preparing work. The regression covers exhausted account/onboarding revisions and a negative revision, preserving an empty command ledger.

No backend/Rules or native-adapter source changed during final review. Optional/defaulted envelope additions retain old invitation payload decoding, and preparation/settlement/preferences share the established UID-owned record and lock. Existing invitation commands keep their original fields/key and are never replaced by onboarding. No discard, migration reset or privileged reconciliation was added.

The bounded iOS touch retry reached Simulator's Window menu, which listed the dedicated TillFailure verification device. Selecting that freshly observed item returned `Computer Use server error -10005: The element ID is no longer valid`. Stopped without repeated reconnect/reset attempts or touching the unrelated simulator's app. **Still unverified:** acknowledgment → completion → client Home → Profile → native Back at normal size; the complete software-keyboard dismissal/submission sequence; large-text lower-action scrolling/taps and that same shell flow. Prior keyboard visibility/Next evidence remains attributed above. Native harness results do not substitute for these touch checks.

Fresh final-review checks after both fixes:

| Check | Fresh result |
|---|---|
| Android host shared suite | 125 tests, 0 failures/errors/skips |
| `iosSimulatorArm64Test` | 125 tests, 0 failures/errors/skips |
| Android Debug, Release and device-test APK | Passed in the same final Gradle invocation; 1m 16s |
| iOS Debug and optimized Release | Both simulator builds passed after both preflight fixes; Release unsigned |
| Whitespace, intended inventory and sensitive/generated-output review | Checked before staging and repeated on the final staged diff |

Results and limitations are also recorded in the sanitized evidence file. The earlier 123-per-platform suites, 154 backend/Rules tests, Android native test, Swift 13-label probe, cold-process five-label probe and successful touch steps remain retained evidence; they are not recounted as final-review executions. Acceptance remains qualified by the interaction, physical-device and production-policy gates.

## Reproducing builds and suites

Run the shared/platform tasks with the configured JDK/Android SDK: `./gradlew :shared:testAndroidHostTest :shared:iosSimulatorArm64Test :androidApp:assembleDebug :androidApp:assembleRelease :shared:assembleAndroidDeviceTest`. Shared XML reports supply the totals above.

The backend suite is `npm --prefix firebase/functions test` inside a Node 22 `firebase emulators:exec --project demo-tillfailure-m3 --only auth,firestore,functions,storage` session. A disposable config may copy `firebase.json` and change only loopback emulator ports; set `TF_FUNCTIONS_EMULATOR_PORT` to the matching Functions port. The final isolated run used the 29xxx/25xxx ports listed above. Do not run destructive test fixtures against the native/manual dataset.

Build iOS with `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'platform=iOS Simulator,id=<verification simulator>' -derivedDataPath <temporary directory> build`, and repeat for Release with `CODE_SIGNING_ALLOWED=NO`. The continued Debug build used the existing simulator signing configuration; Release was unsigned. Temporary directories and device IDs are intentionally not source configuration.

## Reproducing native checks

Use a disposable `demo-tillfailure-m3` emulator set with the guarded loopback environment variables and `TF_PR4_PASSWORD`, then `npm --prefix firebase/functions run seed:pr4`. Seeding refuses reused identities/workspaces; it never resets protected data. It provisions platform-specific trainers through the existing operator primitive, accepts the native/UI/large client fixtures through the existing invitation primitive, and keeps an unaccepted invitee Auth fixture for manual invitation checks. Fixture keys are platform-specific.

Android: compile `:shared:assembleAndroidDeviceTest`, install the generated test APK, then run only `com.delminiusapps.tillfailure.firebase.AndroidOnboardingTest` with instrumentation argument `pr4Password`. That probe uses Auth 19099, Firestore 18080 and Functions 15001 on the host loopback via `10.0.2.2`. The Debug host accepts `tfAuthPort`, `tfFirestorePort`, `tfFunctionsPort` intent extras for the same isolated services; Release does not initialize the product client.

iOS Debug: launch with `TF_PR4_NATIVE=1`, `TF_PR4_PASSWORD` and the three `TF_AUTH_EMULATOR_PORT`, `TF_FIRESTORE_EMULATOR_PORT`, `TF_FUNCTIONS_EMULATOR_PORT` overrides. The harness uses a separate defaults/protected-store namespace, prints only boolean PASS/FAIL diagnostics and exits after clean departure. For actual process recovery, run `TF_PR4_RESTART_PHASE=start` after native completion, wait for `RESTART_READY`, terminate the process, then launch `TF_PR4_RESTART_PHASE=restore` without signing in again. Never clear a pinned identity or protected partition to make the probe pass. Ordinary form checks omit `TF_PR4_NATIVE`. The existing Debug font-scale override can exercise Compose at 200%; it does not prove OS Dynamic Type or VoiceOver behavior.

## Remaining gates

- Product/legal approval of minimum fields, purpose/access/retention, launch Auth/provider behavior, trainer trust, membership policy, privacy and production infrastructure remains open. Production writes are disabled.
- Physical Android/iOS secure-storage, backup/shared-device behavior, real associated domains and production invitation delivery remain unverified.
- No offline grant is issued. Offline startup/recheck locks protected content.
- Interaction acceptance remains partial as detailed above: full Android invitation mutation/confirmation/acceptance walkthroughs and screen-reader traversal; iOS completion/shell touch, large-text lower actions, native Back, OS Dynamic Type and VoiceOver. Harness assertions are not interaction evidence.

## Exact changed-file inventory

48 intended files in this change. Temporary emulator configs and raw/generated output are excluded.

- `androidApp/src/main/kotlin/com/delminiusapps/tillfailure/MainActivity.kt`
- `docs/firestore-schema.md`
- `docs/firestore-security.md`
- `docs/milestones/evidence/milestone-4-pr4/verification.json`
- `docs/milestones/milestone-4-plan.md`
- `docs/milestones/milestone-4-pr4-report.md`
- `docs/navigation.md`
- `docs/offline-sync.md`
- `firebase/firestore.rules`
- `firebase/functions/package.json`
- `firebase/functions/scripts/seed-pr4.mjs`
- `firebase/functions/src/index.ts`
- `firebase/functions/src/invitations.ts`
- `firebase/functions/src/onboarding.ts`
- `firebase/functions/test/callable.test.ts`
- `firebase/functions/test/invitations.test.ts`
- `firebase/functions/test/onboarding.test.ts`
- `firebase/functions/test/rules.test.ts`
- `iosApp/iosApp/FirebaseNativeBridge.swift`
- `iosApp/iosApp/InvitationHarness.swift`
- `iosApp/iosApp/InvitationLifecycleHarness.swift`
- `iosApp/iosApp/InvitationRecoveryHarness.swift`
- `iosApp/iosApp/OnboardingHarness.swift`
- `iosApp/iosApp/iOSApp.swift`
- `shared/src/androidDeviceTest/kotlin/com/delminiusapps/tillfailure/firebase/AndroidInvitationLifecycleTest.kt`
- `shared/src/androidDeviceTest/kotlin/com/delminiusapps/tillfailure/firebase/AndroidInvitationRecoveryTest.kt`
- `shared/src/androidDeviceTest/kotlin/com/delminiusapps/tillfailure/firebase/AndroidInvitationTest.kt`
- `shared/src/androidDeviceTest/kotlin/com/delminiusapps/tillfailure/firebase/AndroidOnboardingTest.kt`
- `shared/src/androidMain/kotlin/com/delminiusapps/tillfailure/firebase/AndroidFirebaseSpikeClient.kt`
- `shared/src/androidMain/kotlin/com/delminiusapps/tillfailure/onboarding/OnboardingZone.android.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/di/DependencyInjection.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/identity/IdentityGate.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/identity/IdentityRoot.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/identity/IdentityViewModel.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/identity/RoleShell.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/invitations/InvitationClient.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/invitations/InvitationScreen.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/invitations/InvitationViewModel.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/onboarding/NativeOnboardingModels.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/onboarding/Onboarding.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/onboarding/OnboardingScreen.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/persistence/PersistencePrototype.kt`
- `shared/src/commonTest/kotlin/com/delminiusapps/tillfailure/identity/IdentityGateTest.kt`
- `shared/src/commonTest/kotlin/com/delminiusapps/tillfailure/identity/IdentityViewModelTest.kt`
- `shared/src/commonTest/kotlin/com/delminiusapps/tillfailure/invitations/InvitationTest.kt`
- `shared/src/commonTest/kotlin/com/delminiusapps/tillfailure/onboarding/OnboardingTest.kt`
- `shared/src/iosMain/kotlin/com/delminiusapps/tillfailure/firebase/IosFirebaseBridge.kt`
- `shared/src/iosMain/kotlin/com/delminiusapps/tillfailure/onboarding/OnboardingZone.ios.kt`

## Final diff review

Reviewed all tracked diffs and new source/test files. No production credentials, bearer tokens, private keys, real customer data, machine-specific source paths, generated build output, design changes or unrelated work are included. Fixture identifiers use the reserved `.invalid` domain; device-fixture passwords are supplied through runtime arguments/environment variables, and the backend callable test uses an explicitly synthetic emulator password. `git diff --check` passes. Before publication the staged inventory is checked against the exact 48-file list; normal commit hooks are allowed. The requested single commit and normal push/PR follow successful verification. Final commit/PR identity is recorded in Git and the PR; this source report does not embed its own commit hash. No history rewrite, merge or deployment is performed.
