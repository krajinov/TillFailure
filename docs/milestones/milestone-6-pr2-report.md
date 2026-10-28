# Milestone 6 PR 2 — synthetic custom exercises

Status: **implemented for review; interaction/accessibility acceptance remains open.**
Implementation/verification: **2026-10-26–28**, Europe/Sarajevo; final handoff **2026-10-28**.
Branch: `feature/milestone-6-pr2-custom-exercises`.
Planning commit: `b986247980994e7c92c27a5578da8b5cd9fab1c2` (`docs: approve milestone 6 custom exercise plan`).

## Baseline and authorization

Initial branch/HEAD matched the instruction: `docs/milestone-6-pr2-custom-exercises-plan` at `49b97f53772a2c6afa42d7f5ed195e0b4df6b554`. The index was empty and the working tree contained exactly the three intended planning files. Read the complete three-document diff, README/relevant contracts and repository-required Compose guidance; no applicable AGENTS.md was found. Recorded Josip's explicit [C1–C6 approval and separate C7 authorization](milestone-6-pr2-plan.md#approval-and-implementation-authorization-2026-10-26), committed only `docs/milestones/milestone-6-pr2-plan.md`, `docs/milestones/milestone-6-plan.md` and `docs/open-questions.md` with the required message, then created this feature branch. No unrelated work, history rewrite, reset, rebase or force-push.

Only the exact original-synthetic, emulator-only author-scoped create/browse/detail/correct slice is implemented. No archive/delete/transfer, owner override, other-author/client grant, automatic restoration, search/media, template references, program builder/assignment, offline grant, second store/recovery framework or dependency upgrade. Rules/index/lockfile sources are unchanged. The initial implementation handoff was uncommitted and unstaged. Josip separately authorized final review, necessary corrections, one implementation commit, normal push and a PR against main on 2026-10-28. No merge, deployment, production/offline enablement, history rewrite, dependency upgrade or PR 3 work is authorized.

## Implemented contract

The trainer Exercises list retains system browsing and adds My custom exercises. The new list/detail/editor use destination-owned MVI and typed owner/visit/UUID navigation; pages, cursors, form values and taxonomy are transient. A create opens fresh preparation; detail/correction opens a fresh authorized get. Dirty Cancel/Back/tab exit requires confirmation; background/process/scope loss clears unsubmitted drafts. An applied Save navigates only after durable settlement and performs a fresh detail read. Explicit correction-conflict Reload confirms dirty replacement and preserves a still-authorized draft if the read fails. Create collision offers explicit Start new after settlement. Retry is shown only for a matching original custom command that has not entered Resolve; other pending variants remain in generic recovery.

`customExercisesEmulator` has only fixed prepare/list/get/create/correct actions. It verifies revoked-token-checked non-anonymous verified enabled Auth, current global account/email binding, active workspace, exact active trainer membership, own trainer profile and membership directory. Five fixed authority documents are read with the query/effect transaction. Author UID comes from Auth. Workspace ownership alone confers nothing. All direct mobile definition/receipt/admission access remains denied by existing Rules. System catalog authority stays its independent existing predicate.

Definitions at `workspaces/{wid}/exercises/{uuid}` have the [exact approved fields/taxonomy/limits](milestone-6-pr2-plan.md#proposed-document-and-validation-contract), immutable workspace/author/UUID and createdAt, explicit revision and trusted timestamps. They contain no client/relationship identity, media, normalized search name or archive state. Fresh same-author access after independently authorized restoration is allowed; pending commands retain their original lifecycle/membership/workspace revisions and cannot be rebound. Reusable definitions do not inherit note-specific permanent relationship bindings or occupied client slots.

List is author equality + document-ID ascending, limit 21 for 20 rows plus lookahead. Every complete document, including lookahead, is validated before any projection. Continuation is a transient position, never a grant or anchor lookup. Details are exact point reads, with missing/foreign/malformed targets sharing unavailable guidance. The actual mediated query passed emulator pagination/isolation tests with unchanged indexes; no production index readiness is claimed. Calls have finite response/read/attempt/deadline/admission bounds and no cache fallback.

Two typed kinds, `custom-exercise-create` and `custom-exercise-correct`, use `custom-exercise-v1` and the existing caller/key command ID. Create/correction atomically commit effect + minimal immutable receipt, or a recognized own create collision/stale correction conflict without changing content. Exact same-key replay returns its original decision; whole-key fences block changed kind/payload and all effects. The own resolver has two exact adapters and retains its seven-field content-free response and original-global-identity permission after workspace loss. Missing/disabled identity, unsupported history/storage and generic errors remain locked rather than settling or restoring access.

The [ADR-002 extension](../trainer-notes-settlement-adr.md#custom-exercise-extension-c6-approved-2026-10-26) persists full terminal proof before retiring into a content-free marker in the existing last-settled slot. No text, taxonomy, raw key, exercise/workspace identity or authority tuple survives in the new-kind marker. Unknown outcomes retain the exact encrypted submitted payload. All eight earlier variants keep their hashes/receipts/legacy forms, including note compaction and note relationship policy. Clean departure preserves freeze → prove empty → drain → marker → fence/dispose → sign-out → terminate/clear/local cleanup → marker completion.

## Frozen vectors and total budgets

Backend and Kotlin/Native agree on these `A`/`w`/`key` descriptor vectors (Unicode, escaped quote/backslash and LF instructions):

| Kind | Expected revision | SHA-256 request hash |
|---|---|---|
| Create | 0 | `ae185ccb7e0cce0507213e64cde7f195e4403c637f3c7e25cdd522fc34f78838` |
| Correct | 2 | `33630c7796954279b0783f391d484a407414e31f901ad94e58a585d0964934c6` |

Worst-case vectors use maximum revisions, escaped 128-code-unit identities/workspace, the maximum covered nonce, maximum name/instructions and taxonomy, a full legacy last-settled proof and the duplicated command in pending terminal proof. Both full legacy invitation and larger revocation proofs are validated; the larger serialized proof is retained in the maximum-envelope vector. Tests exercise non-BMP text, BMP three-byte text, repeated quotes/backslashes and interior LF. Maximum observed complete resolver is **5,585 UTF-16 code units**, pending command **10,735 UTF-8 bytes**, full plaintext envelope **30,099 bytes**. The earlier preliminary invitation-only envelope measurement was 26,943 bytes; the stronger final vector supersedes that measurement.

The implementation checks the actual complete resolver (8,192 code units and 24 KiB UTF-8), callable wrappers (32 KiB), serialized pending command (16 KiB) and every candidate pending/proof envelope state (64 KiB) with the actual prior settled record/default metadata. Native AES-GCM containers use fixed small headers/base64; 64 KiB plaintext plus authentication/base64/header allowance stays within the approved 96 KiB encrypted budget. The serializer test asserts that conservative overhead bound; native runs exercise the actual encrypted stores. No universal legacy-envelope ceiling is retroactively imposed.

Over-limit/unsupported admission is explicit before prepare/transport, leaving original history untouched. An artificial oversized legacy envelope is refused without clearing it. Invalid text/taxonomy stays in memory with validation guidance; whole oversized pastes are rejected with a message, never truncated. No cap was enlarged, command split, history removed or new store added. No substantive contract contradiction required a scope change.

## Verification ledger

Each row distinguishes automated/native/interaction evidence. Existing M5/M6 PR 1 evidence is retained with its original attribution; the counts below retain the original pre-publication verification attribution. The separate publication-review section records the checks rerun after final corrections.

| Layer | Result and attribution |
|---|---|
| Isolated backend/Rules | Pre-publication reviewed suite: **262/262** on Node **22.23.4**, with `npm ci --prefer-offline --no-audit --no-fund` using the unchanged lockfile (813 packages). Initial suite 253/253; first final suite 261/261; reviewed final includes the malformed-admission-record case. No dependency upgrade. Existing lockfile deprecation warnings remain. |
| Backend coverage | Author/owner/other-trainer/client/workspace isolation and direct Rules denial; strict fields/Unicode/bytes/taxonomy; lookahead/pagination; own/foreign collision; concurrent correction; exact replay; all three lifecycle race types at create/correction; restoration without rebinding; disabled identity; callable bearer/override rejection; both resolver orderings and whole-key fences; clock-regression conflict; quotas/deadlines/history; malformed admission records retained unchanged. Existing older protected variants/assignment/catalog denials remain in the complete suite. |
| Shared Android | Pre-publication reviewed suite: **227/227**, zero failure/error/skip. Final metadata/Android Debug, unsigned Release and device-test APK builds pass. |
| Shared iOS | Pre-publication reviewed suite: **229/229**, zero failure/error/skip; actual native Swift remains separately tested below. |
| Shared coverage | Both canonical vectors/strict parser, total budgets/full legacy proof, two-stage success/conflict/fence settlement with proof/retirement write failures and restart, content-free marker/UID/key contradictions, all inherited protected suites, form validation/duplicate Save, matching Retry, conflict explicit reload/read failure, dirty/stale navigation and source-list Back refresh. |
| iOS host | Pre-publication ad-hoc signed Debug and unsigned simulator Release builds: **passed**. Ad-hoc signing is required for this simulator's Firebase Auth Keychain; this is not distribution signing. No new operational Release Firebase initialization. |
| Android actual SDK/process | **14 stage/resume cases pass**: create and correct × applied held response, fenced before transport, durable conflict, full proof before retirement, injected proof-write failure, already settled marker, and admission/delayed issuance/cancelled presentation. Actual app-process force-stop/relaunch retains original payload/key/revision/UID; own resolution, compaction, departure and clean B isolation are asserted. No fixture reset/rekey. |
| Swift actual SDK/process | **14 stage/resume cases pass** for the same matrix, real official SDK/Keychain/CryptoKit storage and process termination/relaunch. Settled plaintext is inspected for absence of original text/ID/payload. Admission tests prove rejected frozen preparation, delayed admitted execution and cancellation of presentation without preventing settlement, then clean B admission/isolation. |
| Debug/Release audit | All Android Release DEX and six iOS Release Mach-O files exclude the new native harness names and fixture/phase markers. New harnesses/fixture arguments stay Debug/test-only. Existing generic spike hooks are inherited, not removed or claimed absent by this task. |

Native core cases ran before the admission-only harness extension. The final admission cases used the extended harnesses and unchanged native protocol/adapters. After native verification, review added fresh-Back navigation, matching-Retry presentation, a timestamp-conflict fix (conflict does not require advancing definition time), and strict malformed admission-record denial. Final affected backend/shared/build checks cover those corrections; no fresh native process matrix is claimed afterward. A limited pre-publication-source iOS touch run is separately recorded below. The later publication review does not relabel it as final-source evidence. SDK/process runs are retained for those final-source qualifications. No physical-device, OS connectivity or screen-reader pass is inferred from them.

## Actual interaction and remaining acceptance

Pencil read-only inspection again failed its three transport retries to `antigravity_ide`; no fresh frame read/render or design edit. The retained [exercise references and tokens](../design-inventory.md#section-to-product-map) informed the UI, without a new fidelity claim.

On iOS 26.2/iPhone 16e, actual product UI actions opened Exercises, My custom exercises and Create. Text entry rendered the name and multiline instructions; the software keyboard was shown and its Return action inserted a newline. Actual taxonomy selection showed arms selected. Dirty Home/tab departure opened confirmation, Cancel preserved entered values, and confirmed Leave returned Home. These are UI actions, not harness input. No actual Save, correction, conflict/reload or lower-action completion is claimed.

One direct drag produced no visible movement. Supported scrolling and a coordinate alternative returned `noWindowsAvailable`; Tab showed no change. Gesture/lower-action interaction remains unaccepted and broken input was not repeatedly retried. The computer-use service later reported the Mac locked; a manual-unlock request was made while independent verification continued. Josip unlocked the Mac and authorized continuation. Installed the pre-publication signed Debug build over the same simulator data, resumed the sole supervisor from its complete exit export, and repeated actual custom-list/Create, name/multiline input and arms-selection checks. The first imported-session custom read was unavailable although a bounded direct author read passed; normal product clean sign-out, followed by the existing Debug fixture sign-in into the same UID, restored access. No identity/pending/history reset or submitted UI command. Pre-publication-source scrolling again returned `noWindowsAvailable`; intermittent capture error -3811 also prevented verification of the final dirty-departure attempt. These attempts remain failed/partial, not additional interaction passes. Largest OS Dynamic Type was applied, but lock prevented visual/interaction verification; original `large` was restored. The normal screenshot/AX inspection is not a VoiceOver run or full keyboard/focus proof.

Android API 34/Pixel 4 UI inspection was blocked by recurring system/System UI ANR dialogs. The initial product launch preceded completed installation and reported missing Activity; installation then succeeded. Wait was selected once. A data-preserving reboot of only the task AVD did not clear the system-UI ANR, so no actual Android create/correct/keyboard/Save/Cancel/Back/conflict/large-text interaction is claimed. Native SDK/process tests passed independently of this UI failure. No app data or pending partition was cleared to obtain a pass.

Open acceptance includes actual Save/correct/reopen/conflict/Reload, lower form/list scrolling, Cancel/Back variants, long text and software keyboard reachability on both final hosts; 200% Android/OS Dynamic Type, TalkBack/VoiceOver traversal/labels/focus/error announcements, localization/reduced motion/effective targets and fresh design review; controlled OS connectivity/revocation/background stimuli and physical key/lock/backup/shared-device controls. Some iOS dirty-navigation/input checks overlap these but do not close the entire gate. [PR 1 gaps](milestone-6-pr1-report.md#actual-interaction-and-remaining-acceptance) and [M5 H1–H11](milestone-5-closeout.md#remaining-gates-and-dependencies), including M2/M4, unresolved identity/storage/history, privacy/retention, assignment-index, production/signing and offline/media gates remain. No blanket deferral or full M6 acceptance is claimed. Builds/harnesses never replace interaction evidence.

## Failed attempts and dataset preservation

- Initial sandbox Gradle/simctl/ADB probes could not access caches/services; authorized elevated checks supplied evidence. No toolchain upgrade or cache reset.
- First Kotlin build found dirty-state generic inference; explicit types corrected it. The first Android harness compile lacked the existing NativeClientsModels import. First Swift harness build used an unavailable Kotlin-exported new-key selector; the existing resolution-key helper corrected it. Subsequent builds/tests passed.
- Early native launches overlapped first installation/AVD startup. iOS unsigned Debug returned Auth error 17995 (Keychain), diagnosed from the installed SDK; normal local ad-hoc signing resolved it without credential/identity/key/data replacement. Temporary raw-code diagnostic was removed from product source before handoff.
- Android cold gRPC class verification exceeded its first ten-second server-read deadline. Bounded preflight uses at most two further fresh SERVER reads, never cache, identity repair or key replacement. First-stage retries before command admission retain their failed attribution.
- The Android runner initially misclassified a completed `OK (1 test)` resume because its PASS marker went to logcat. The actual completed JUnit result was inspected and recorded; the settled case was not restaged. Runner progress then resumed only untouched/unfinished phases. All subsequent cases preserve exact original commands.
- UI service latency, unavailable scrolling, locked Mac and Android system ANRs are recorded above. They are not successes and no repeated automation loop claims to close them.

The sole native supervisor used Auth **31699**, Firestore **31680**, Functions **31601**, Storage **31619**, hub **31640**, logging **31650**, project `demo-tillfailure-m3`, prefix `m6p2r20261028a` and provenance `m6p2-intact-20261028a`, preserving the existing coverage generation. Create-only setup created 28 core/UI trainer identities, then only four additional admission identities; none replaced prior data. Initial, final, graceful-exit and resumed post-UI exports were checked for complete Auth/Firestore/Storage metadata/history under ignored `.firebase/m6-pr2/`. The private fixture environment and supervisor config are preserved there with restricted permissions; restoring the config at repository root permits resuming its actual paths. Credentials, exports, raw logs and device data are excluded from the change set.

Reset-oriented suites completed before native startup. The sole native supervisor was exported/stopped before the final reset suites; after those suites completed, its complete exit export was imported for the bounded final UI continuation, then exported again and stopped. No same-project CLI discovery overlap. Original PR 1/M5 exports, consumed keys, identities, device partitions and pending records remain untouched. The task simulator was shut down and the task AVD was already stopped at final inspection, with data retained; original iOS text size restored. No unresolved command was reset/rekeyed or deleted for a pass. No native supervisor remains after graceful export.

## Exact inventory and source fingerprint

The planning commit contains only the three approved planning files. The implementation inventory below is relative to that commit: **36 files — 11 documentation and 25 implementation/test/fixture sources**. `new` marks additions relative to the planning commit. At final-review entry these were untracked and all implementation changes were unstaged; the approved implementation commit includes this exact inventory.

| File | State |
|---|---|
| `README.md` | modified |
| `docs/firestore-schema.md` | modified |
| `docs/firestore-security.md` | modified |
| `docs/implementation-plan.md` | modified |
| `docs/milestones/milestone-6-plan.md` | modified |
| `docs/milestones/milestone-6-pr2-report.md` | new |
| `docs/navigation.md` | modified |
| `docs/offline-sync.md` | modified |
| `docs/open-questions.md` | modified |
| `docs/trainer-notes-settlement-adr.md` | modified |
| `firebase/README.md` | modified |
| `firebase/functions/scripts/seed-m6-pr2.mjs` | new |
| `firebase/functions/src/command-resolution.ts` | modified |
| `firebase/functions/src/custom-exercise-contract.ts` | new |
| `firebase/functions/src/custom-exercises.ts` | new |
| `firebase/functions/src/index.ts` | modified |
| `firebase/functions/test/custom-exercises.test.ts` | new |
| `firebase/functions/test/rules.test.ts` | modified |
| `iosApp/iosApp/CustomExercisesHarness.swift` | new |
| `iosApp/iosApp/FirebaseNativeBridge.swift` | modified |
| `iosApp/iosApp/iOSApp.swift` | modified |
| `shared/src/androidDeviceTest/kotlin/com/delminiusapps/tillfailure/firebase/AndroidCustomExercisesTest.kt` | new |
| `shared/src/androidMain/kotlin/com/delminiusapps/tillfailure/firebase/AndroidFirebaseSpikeClient.kt` | modified |
| `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/di/DependencyInjection.kt` | modified |
| `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/exercises/CustomExercises.kt` | new |
| `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/exercises/CustomExercisesScreen.kt` | new |
| `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/exercises/CustomExercisesViewModel.kt` | new |
| `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/exercises/ExercisesScreen.kt` | modified |
| `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/identity/IdentityRoot.kt` | modified |
| `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/identity/ProductIdentityClient.kt` | modified |
| `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/identity/RoleShell.kt` | modified |
| `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/invitations/CommandResolution.kt` | modified |
| `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/invitations/InvitationClient.kt` | modified |
| `shared/src/commonTest/kotlin/com/delminiusapps/tillfailure/exercises/CustomExercisesTest.kt` | new |
| `shared/src/commonTest/kotlin/com/delminiusapps/tillfailure/exercises/CustomExercisesViewModelTest.kt` | new |
| `shared/src/iosMain/kotlin/com/delminiusapps/tillfailure/firebase/IosFirebaseBridge.kt` | modified |

Pre-publication tested 25-source manifest SHA-256: `6f78ebc49769019f171bca9fcb7696d0647e50f8d65372f0d02dc84f465487dd`. Publication-review 25-source SHA-256: `29544e9906d4aafe7a410b632f48355ccc3e70726bb0220e1597a0b3fe0d1d68`. Sort repository-relative source paths lexicographically; for each write lowercase file SHA-256 + two spaces + path + LF, then hash those UTF-8 manifest bytes. Documentation-only follow-up does not change the fingerprint. Each fingerprint identifies its own source snapshot, not a retroactive fingerprint for earlier qualified native/interaction runs.

## Final handoff audit

Reviewed the complete tracked diff and every new source/document, exact inventory and source manifest; verified unchanged Rules/index/package/lockfile sources, no credentials/private fixture values, generated outputs, machine paths or merge markers in the changes. New harness markers are absent from final Release binaries as recorded above. All 183 local Markdown file/anchor checks, tracked/untracked whitespace checks and final Git index/status checks passed. At the initial handoff only the planning commit had been created, the index was empty and all 36 implementation files were unstaged. The final-review authorization below supersedes that publication restriction.

## Publication review (2026-10-28)

Starting branch and HEAD exactly matched `feature/milestone-6-pr2-custom-exercises` at `b986247980994e7c92c27a5578da8b5cd9fab1c2`; the index was empty and the working tree contained the exact 36 files above, with no unrelated changes. Remote main remained `49b97f53772a2c6afa42d7f5ed195e0b4df6b554`, the feature branch had no remote ref and the connected GitHub lookup found no open PR. Read repository instructions, approved C1–C7, the complete implementation diff including additions, report and relevant contracts. The prior source manifest matches every unchanged source; only the following four source/test files differ due to this review's two bounded corrections.

- `custom-exercises.ts` now checks the final five-second elapsed-work deadline before returning an exact replay, matching its other business return paths. A new backend test proves expired replay returns unavailable without rewriting the definition or original receipt, and later fresh replay returns the same decision.
- `CustomExercisesViewModel.kt` now cancels the Save timeout and advances the callback generation when a matching result arrives. A durable conflict therefore stays a conflict after 20 seconds, instead of being mislabeled unknown. The shared regression test advances virtual time past the deadline. A separate queued-Save-after-confirmed-Back test proves no submission or draft resurrection.

Fresh publication verification: **263/263 backend/Rules tests, 229/229 Android shared tests and 231/231 iOS shared tests, zero failures/errors/skips**. Android Debug, unsigned Release and device-test APK builds passed; final iOS ad-hoc signed Debug and unsigned simulator Release builds passed. Fresh final Release audits passed for every Android DEX and all six iOS Mach-O files, excluding the new harness/fixture markers. The declared Functions runtime and package engine are Node 22. The default shell resolves Node 25.9.0, so the complete backend/Rules suite is explicitly rerun with the existing isolated Node 22.23.4 and unchanged installed lockfile tree; Firebase confirms its workers use Node 22. No lockfile, package, Rules, index or dependency/configuration change.

Retained verification remains the original 262 backend, 227 Android shared, 229 iOS shared tests and Android/iOS Debug/Release builds above, plus fourteen actual recovery cases per host with their documented earlier source qualifications. Final review changes neither native adapter/protocol, hashes/receipts/marker shapes nor storage/UID/freeze/departure code; no native matrix rerun is claimed or required for the presentation timeout and server replay-deadline corrections. No new interaction pass is claimed. Partial iOS checks, Android ANRs, capture/scroll failures, accessibility, OS-event, physical-device and all inherited gates remain explicit above. No automation retry, device launch, native dataset import, pending deletion or rekey occurred during publication review.

Publication audit: exact 36-file inventory and final 25-source fingerprint match; all 183 local Markdown file/anchor links and tracked/untracked whitespace checks pass. No fixture credentials, new private keys, machine paths, conflict markers or generated outputs are included. Rules/index/package/lockfile/configuration sources are unchanged. Only intended files are selected for staging; both commit parentage and normal remote publication are checked separately in Git/PR metadata. The sole reset-suite supervisor is task-owned and exits after its tests; preserved native supervisors/devices remain stopped. All original exports, fixture environment, pending partitions, consumed keys and provenance remain retained.

Josip authorized one implementation commit, `feat(exercises): add protected custom exercise creation and correction`, followed by normal push and a PR titled `Milestone 6 PR 2: custom exercise creation and correction` against main. The separate planning commit remains intact. The PR against main contains both commits and 37 distinct paths: this 36-file implementation inventory plus the planning-only `docs/milestones/milestone-6-pr2-plan.md`; the other two planning files overlap the implementation inventory. Git/PR metadata records the implementation hash and PR URL; no merge, deployment, production/offline enablement or later program-building policy/implementation is included.
