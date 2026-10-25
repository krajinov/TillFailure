# Milestone 5 PR 5 — private trainer notes

Status: approved synthetic emulator slice implemented, with qualified verification and remaining acceptance gates below. Production remains disabled. Implementation verification: 2026-10-24; final review handoff: 2026-10-25 (Europe/Sarajevo).

## Baseline, authorization and scope

Started at `51bf04dbf7dc90083fb4e2be062bdb6abbf41b0d` on `docs/milestone-5-pr5-trainer-notes-plan`, with exactly the three reviewed planning files modified/new and an empty index. Read README/repository architecture and relevant contracts; no applicable AGENTS.md was found. Josip explicitly approved N1–N5 and emulator implementation, including the permanently occupied-slot restriction. Reviewed the complete planning diff, recorded that approval, and committed only those three documents as `98117dde90307c14318a9a94da0f8efde6b5f3b2` (`docs: approve milestone 5 private trainer notes plan`). Then created `feature/milestone-5-pr5-trainer-notes`. No unrelated work was reset, amended or rebased. The implementation handoff left all 35 files uncommitted/unstaged; Josip subsequently authorized the bounded final review, one implementation commit, normal push and PR against main described below.

The [approved plan](milestone-5-pr5-plan.md) remains the policy boundary: one author-owned fixed slot per client/workspace; synthetic non-sensitive coaching administration only; exact current relationship and immutable author/subject membership/global lifecycle bindings; complete client exclusion; no owner override, replacement author, transfer, rebinding, automatic historical restoration, deletion/archive/export, real personal data, offline grant or production enablement.

Fresh Pencil inspection from planning remains unavailable: pen.dev opened the repository design but did not answer the two read-only inspection attempts. Retained design references and existing shared design-system components inform the minimal editor. No design file was changed or fresh visual fidelity to an unavailable frame claimed.

## Implemented behavior

`trainerNotesEmulator` exposes only `note-get` and `save-trainer-note` behind named demo/loopback guards and fresh non-anonymous bearer verification with revoked-token checks. The operation additionally reloads enabled verified caller Auth, subject Auth existence/enabled state, active accounts/workspace, exact path-bound active memberships, author trainer profile, both discovery references and subject profile assignment. Existing slot author and four immutable membership/lifecycle revision bindings must match. Workspace ownership alone grants nothing. Onboarding may remain pending. Subject Auth and Firestore cannot be atomically checked together; trusted lifecycle denial precedes Auth deletion, and an already authorized response cannot be recalled.

The exact slot is `workspaces/{wid}/privateTrainerNotes/{clientUid}`. No list/index or client-readable projection is added. Rules source remains unchanged/default deny, including for the author. Note body is canonical LF text, nonempty under the same ECMAScript whitespace definition on server/client, bounded to 2,000 Unicode scalars and 8,000 UTF-8 bytes with invalid surrogates/control characters rejected. Schema/policy are 1; note revisions advance exactly once and creation time remains immutable. Fresh 32-byte cryptographic nonces bind each submitted body without being retained in a server receipt.

Work is bounded to ten fixed business documents, plus receipt/config for Save; no query or fan-out. The separate payload-free request counter permits 120 requests/minute per caller. Requests/responses are bounded to 32 KiB, accepted Save descriptors must also fit the existing 8,192-code-unit resolver limit, stored note payloads are capped at 16 KiB, transactions have at most three attempts and a five-second elapsed-work check before writes. This deadline does not cancel an outstanding RPC. Successful Save atomically writes note+receipt; a recognized stale revision writes only an immutable conflict receipt. Malformed state, wrong bindings, future revisions or authorization loss cannot become a durable conflict. No automatic merge/overwrite/new revision occurs.

The eighth adapter is `save-trainer-note` / canonical kind `trainer-note-save` / hash format `trainer-note-v1`. Existing caller/key identity, immutable lifecycleCommands receipts, generation/provenance guards, original-UID minimal own resolution and whole-key fences are reused. The original descriptor is retained exactly while pending, including its random nonce. Own resolution recomputes the binding transiently and returns the same seven-field projection, without live note/subject/workspace access. Receipt/fence records contain no body, nonce, previous value or free-text audit reason. Generic errors never settle work.

Notes do not enter PR 4's assignment-artifact probes or change its five-write revocation transaction. Save reads the authority documents changed by revocation/lifecycle in its own transaction. Save-first commits before later denial; withdrawal-first prevents a delayed Save from using an old active relationship. There is no empty-query lock and no added workspace-revision write on note edits. Retained notes remain in place with inaccessible custody; occupied slots never become replacement-author slots. No reassignment writer is added: a future trusted reassignment must advance the subject relationship revision atomically with assignment, as the test fixture does, so assignment-back cannot reopen old notes. Arbitrary Admin edits that bypass lifecycle revisions are outside this protocol. Existing assignment-index Rules limitations and assignment-bearing revocation restrictions are unchanged.

## Settlement prerequisite and compatibility

Implemented [ADR-002](../trainer-notes-settlement-adr.md) before exercising note writes. Initial prerequisite suites passed **Android 205/205 and iOS 207/207**, zero failures/skips. They added content-free marker/legacy compatibility, restart duplicate-key and foreign UID checks to the previous 202/204 baseline.

Pending proof still contains the original command and is durably validated/written before retirement. Note retirement then uses the same bounded last-settled slot with `source=compacted-v1` and a versioned content-free marker. It retains UID, derived command ID, kind, hash format/hash, source/outcome and resolver time only when actually received. It contains no command/body/nonce/raw key/client/workspace data. It is not accepted as pending proof, in a foreign UID partition, or beside pending work consuming the same key. Normal acknowledgment markers invent no server decision time. Restart completes retirement from full pending proof; a failed write remains protected.

The seven previous variants retain their full forms, hashes and outcome behavior. New optional fields are omitted from serialized legacy forms; upgraded readers accept prior records. Unsupported older readers lock on actual new forms instead of erasing/replacing them. Shared tests cover legacy round trips, exact note wire parsing, original bindings, normal success/conflict/resolver settlement, both proof-write failures, restart compaction, newer-pending callback protection and UID isolation. No second queue/store/coordinator, destructive discard, identity repair or account-switch shortcut is added.

## Editor and navigation

The trainer details entry opens a typed owner/client/visit destination. A destination-owned Koin MVI model uses the existing root command service and native transports. Text never enters saved navigation/state, preferences, a Firestore note cache or a local draft database. Scope/current-top-destination/visit/generation checks fence stale actions and callbacks. Navigation effects are consumed once, and accepted departure immediately fences queued Save/Confirm actions before disposal. Background and known authorization loss clear note presentation and unsent drafts. Submitted commands remain in the existing encrypted UID envelope and block clean departure until validated settlement; cancellation affects presentation only.

Dirty Back/Cancel/tab navigation confirms abandonment. Conflict preserves the authorized foreground draft, blocks another Save, and requires an explicit confirmed fresh reload; failed reload cannot replace the draft. Native recovery remains accessible through Invitations/Locked Recovery. Foreground/entry/reload/Save recheck the subject; there is no new target listener or claim of instant remote-change notification.

Actual long-text inspection exposed an unbounded multiline field growing below the viewport. The final implementation limits it to eight visible lines with internal text scrolling, retaining surrounding page scrolling. Save now shows explicit success after its authorized reload. The initial final-layout recheck was blocked when the Mac locked; after Josip unlocked it, the final Debug build passed the bounded long-draft/software-keyboard/Save/Cancel/Back checks described below. Direct gesture scrolling and full Dynamic Type acceptance remain unverified.

## Verification attribution

Final-tree results and retained results must be read separately. No earlier suite total is added to a later count. The final validation table below identifies the final source checks.

### Backend and Rules

The initial complete implementation run passed **231/231 across 15 suites**, zero failures/skips; a later run passed 233/233. They cover exact fixed-slot behavior, immutable bindings, actual concurrent creates/edits, both withdrawal orderings, Save/resolver contention, immutable conflict/fence decisions, replacement-author denial, Auth/current source failures, Unicode/size/canonical hash boundaries, callable bearer enforcement and direct note/receipt/budget denial. Existing seven-command, lifecycle, invitation, assignment and profile suites remain included. The final run additionally covers real account-disable, author-withdrawal, workspace-suspension and revision-advancing reassignment-fixture transactions racing Save, with restoration/return denial for occupied notes; the exact request rate bound, direct delete denial and BOM/line-separator validation parity are also included.

Race claims use actual Firestore transactions. Deterministic Save-first holds the real transaction before writes, begins concurrent revocation, then releases the writer; revocation-first commits before delayed Save. These do not claim an impossible bypass of a held pessimistic lock. An earlier injected ABORTED prerequisite case retains its original, narrower attribution.

The native dataset's initial **76 immutable command records**, and all **77 records after the final-build interaction Save**, were inspected recursively for forbidden note body/nonce/payload fields; none were present. New runtime code emits no note payload diagnostics. Hashes are commitments, not encryption/anonymization. Raw logs and fixture exports remain excluded from the diff.

### Android and iOS SDK/process evidence

**Five scenarios passed on each platform** using native Auth/transport, encrypted storage and actual process termination/relaunch: committed completion held; prepared before transport then fenced; full proof durable before interrupted retirement; durable conflict recovery; and already-compacted retirement before restart. Each uses its original UID/key/payload, with synthetic author membership withdrawn between stage/resume. Resume verifies minimal applied/conflict/fenced outcome, no role restoration, content-free marker, clean A departure and B isolation. Pending stages test blocked premature departure/B admission. These are SDK/model/process results, not touch, screen-reader or OS network-event evidence.

The runs preceded the later editor-height/success-message, whitespace parity and legacy-null-omission refinements, and the final pending/retired same-key contradiction guard and regression assertions. Their coverage is retained core protocol/native evidence; final shared suites/builds cover those later source changes. No claim is made that every native scenario was rerun after those refinements.

The first Android attempt failed Auth before any note issuance; loopback/synthetic Auth status was checked and the same untouched fixture retried successfully. A log-clear setup step also failed before issuance and was removed in favor of increasing marker counts without clearing logs. The first iOS driver watched host tmp instead of simulator-global tmp, timed out while the app had already reached its protected boundary, then continued that same preserved original command after locating the real markers. No command was replaced or identity repaired to obtain a pass.

### Actual interaction

A supported iOS Simulator surface worked during this run. Normal product interaction passed clean departure from the retained initial root, synthetic trainer sign-in, roster → client details → private note, empty state, multiline entry, dirty Back confirmation/Cancel preserving draft, Save/current reload, real concurrent-edit conflict, Reload/Cancel retaining the losing draft, confirmed fresh reload, actual Simulator Home/background → foreground → fresh reentry with unsent draft gone and saved text intact, subject revocation via the existing handler followed by fresh reload clearing the note, normal navigation after denial and clean sign-out to blank fields. This is new iOS interaction evidence for those exact stimuli, despite the earlier reports' broken automation. It does not retroactively close unrelated earlier gates.

A distinct create-only fixture was used for the largest OS accessibility text-size setting and a long multiline entry. Screenshot inspection found the growing-field problem above. Supported scroll/drag attempts did not establish lower-action reachability. The Mac subsequently locked; the tool explicitly reported automatic unlock unavailable. OS text size was restored from accessibility-extra-extra-extra-large to its original `large`. This initial attempt did not establish corrected-layout, software-keyboard, VoiceOver or full Dynamic Type acceptance.

#### Final-build recheck after unlock

Josip subsequently confirmed the Mac was unlocked. Restored the intact exit export into the sole task supervisor, with the same provenance/generation and original `m5p5r2026102419_ios_ui` identity; no seeding, record reset or identity replacement. Reinstalled the already-verified final Debug app into the same task simulator. The first note read from the retained signed-in session was denied while roster/details worked. Bounded read-only source inspection showed eligible authority and an authorized direct mediator read. Normal product sign-out/sign-in with the existing synthetic credential restored callable note access; no Auth/authority/history repair or bypass was used. Import-time retained-token freshness is a possible explanation, not an instrumented finding.

At the largest OS accessibility text-size setting, entered 100 lines of synthetic administration text (1,800 characters), then tapped the actual software keyboard's Return (one additional newline). Screenshots showed an eight-line field rather than unbounded growth. Return did not submit: the empty-slot state remained until explicit Save. With the keyboard open, Profile navigation raised the dirty Leave/Cancel dialog; Cancel retained the complete draft and dismissed the keyboard. Save, Reload and Cancel/Back were then visibly reachable. Explicit Save completed and freshly reloaded the note; a subsequent keyboard show/hide exposed the visible “Note saved.” message and all three actions. Clean Back returned to client details; reentry displayed the complete long saved value with Save disabled. Clean sign-out ended at blank sign-in fields. A later ordinary verification/connect gate appeared before sign-out; no deliberately controlled network stimulus or reconnect acceptance is inferred from it.

This is final-native-source iOS Simulator interaction evidence using supported native accessibility actions and inspected screenshots. It does not establish physical touch or VoiceOver. Direct scroll/drag attempts still did not establish gesture scrolling; action reachability was demonstrated through the keyboard/dialog path above. Changing the OS text-size setting is a stimulus, not proof that every font scales correctly: full Dynamic Type acceptance remains open. The software keyboard was left hidden and OS text size restored to original `large`. No source implementation changed or build/suite was rerun for this evidence-only update. The original five-per-platform process-recovery runs remain retained, not rerun.

Android normal app interaction was attempted after SDK runs, but the task AVD displayed “Process system isn't responding” and then “System UI isn't responding” after Wait. No Android note entry/keyboard/Save/Back/200%/TalkBack touch pass is claimed. Other booted devices were not used to bypass or reset that state. The earlier profile/revocation Android touch results remain retained evidence for those features only.

## Setup failures, preservation and remaining gates

Initial shared test compilation lacked a Flow.first import; it was corrected before passing suites. An incremental compiler dirty-symbol error fell back successfully; it was not a passing incremental-compiler claim. First Swift compile required the Objective-C-exported doNew… selector; renaming the native bridge method to noteBindingNonce corrected that boundary before Debug success. Initial native supervisor configurations resolved source/Rules relative to the wrong directory; no fixtures were issued against that setup. The corrected root-level ignored config loaded the actual Rules and all Functions before seeding. An automatic approval-review usage-limit failure prevented a seeding action from executing; later normal approval review succeeded, with no bypass.

A single preserved native supervisor ran after the initial reset suite completed. New `m5p5r2026102415…` native/UI and `m5p5r2026102419…` long-text fixtures were create-only; no previous PR dataset, identity, receipt, key or pending partition was recreated. Initial/final and exit exports preserve Auth/Firestore plus stable generation/provenance configuration under ignored `.firebase` storage; the explicit final export was verified for metadata and nonempty data before shutdown. Task loopback relays and supervisor were stopped before the final reset suite; no concurrent same-project CLI discovery was used for those phases. The later unlock recheck restored the intact exit export only after that suite completed, then preserved its new Save in a separate unlock-exit export. Earlier resolver lost-history/legacy/disabled-identity/damaged-storage gaps remain open and are not repaired by this new dataset.

TalkBack/VoiceOver, direct gesture scrolling/full Dynamic Type, Android note interaction, physical-device touch/encryption/key/backup/shared-device/secure-erasure, controlled OS network/reconnect/offline cold-start, localization/reduced-motion and earlier M2/M4/PR 1–4 gates remain qualified. The actual iOS Home/foreground path and final-build keyboard/long-draft checks above are their own stimulus evidence. Production infrastructure/IAM/residency/migrations/signing, real-data purpose/retention/discovery/export/support and future offline workouts remain outside approval. No deployment, production configuration, dependency upgrade, offline grant or unrelated milestone change occurred. Push and PR creation belong only to the later explicit finalization authorization.

## Final review and Node 22 verification

Finalization began at the expected planning HEAD `98117dde90307c14318a9a94da0f8efde6b5f3b2` on `feature/milestone-5-pr5-trainer-notes`, with an empty index and exactly the 35 implementation files below. Fresh remote inspection found main unchanged at `51bf04dbf7dc90083fb4e2be062bdb6abbf41b0d`, no remote feature branch and no existing PR for that branch. The preserved PR 5 plan is byte-identical to the planning commit; the milestone plan/open-questions changes are the previously recorded implementation status updates. No unrelated work or history rewrite is included.

Reviewed all 35 files, including untracked additions, against N1–N5 and ADR-002. Checked exact current author/assignment/authority and immutable bindings; withdrawal/read-set serialization; strict revisions and bounded point work; the eighth canonical descriptor, receipts and whole-key resolver; tagged proof/marker consumers and interrupted retirement; the seven legacy variants and clean departure; transient drafts, conflicts, dirty exits, queued actions and stale callbacks. No functional correction or policy expansion was required. The shared field's old callers retain `singleLine=true` and effective one-line behavior; only the new note editor opts into multiline/eight-line behavior. No other multiline input was changed. Documentation corrections distinguish the original uncommitted handoff from finalization and the previously unverified Node 22 runtime from the new result.

Used official **Node 22.23.4** for Darwin arm64 in a temporary isolated runtime directory, verified against the official release SHA-256 manifest (`c9e1dbc748b33ed2d14f42445191ecaf14c56259c7a9a26d0042c49ec2b66428` for its archive). Prepended only that runtime's bin directory to PATH for locked `npm ci --no-audit --no-fund`, standalone `npm run build`, and `npm run test:emulators`, with Java 21. The declared engine remains 22, and package.json/package-lock.json are byte-identical to HEAD. The Functions emulator explicitly reports `Using node@22 from host`; this refers to the isolated PATH runtime. The fresh suite result is recorded in the table below. Existing locked-package deprecation and emulator diagnostic warnings were retained; no dependency upgrade was made.

Reset-oriented tests used their own fresh disposable emulator processes at the checked-in test ports, with no native supervisor running and no preserved export imported. All initial/final/exit/unlock-exit native Auth/Firestore exports remain intact and ignored, with their original provenance; no native identity, consumed key or pending partition was recreated. Prior recursive inspection of the preserved 77 receipts remains retained evidence; final review does not claim a new live native receipt audit. Only test-owned emulator processes are stopped by the suite.

Fresh final-review evidence is Node 22 installation/TypeScript/backend/Rules verification plus full diff, credential, artifact, inventory, local-link/anchor and whitespace audits. The unchanged shared/native source retains the earlier **211/211 Android**, **213/213 iOS**, Android/iOS Debug/Release builds and final-build iOS interaction checks; these were not rerun during final review. The five native process-restart scenarios per platform still predate the documented final refinements and are not final-tree reruns. Simulator Release success is not distribution signing. No interaction, accessibility, OS-event, physical-device or full Milestone 5 gate is closed by this review.

Final inventory audit excludes raw logs, screenshots, fixture credentials/exports, generated outputs and machine-specific home paths. Rules, indexes, Firebase/build configuration, dependency declarations/lockfiles and design files are unchanged. Stage only the exact 35-file inventory for the single implementation commit `feat(clients): add private trainer notes and protected recovery`; preserve the planning parent. The separately authorized normal push and PR target main, with no merge or deployment.

## Final validation and exact implementation inventory

| Final source check | Result and attribution |
|---|---|
| Backend + Rules full emulator suite | **Fresh final review under isolated Node 22.23.4: 236/236 across 15 suites**, zero failures/cancellations/skips, with standalone TypeScript build also passed. Includes all eight protected variants, real note/lifecycle races and default-deny note Rules probes. Earlier Node 25.9.0 run also passed 236/236 and remains separately attributed. |
| Shared Android host tests | Retained implementation run: 211/211; zero failures/errors/skips. Final-source XML results, including marker compatibility, contradiction and note editor tests; unchanged source, not rerun in final review. |
| Shared iOS simulator tests | Retained implementation run: 213/213; zero failures/errors/skips. Final-source XML results; unchanged source, not rerun in final review, and no touch/accessibility inference. |
| Android builds | Retained Debug, Release and shared Android device-test APK success with the unchanged final shared/native source. |
| iOS host builds | Retained Xcode Debug and Release `BUILD SUCCEEDED` with unchanged final shared/native source; simulator only, Release signing disabled. |
| Release harness exclusion | Android Release DEX contains no PR 5 instrumentation class/selectors; iOS Release executable contains no PR 5 harness selectors or executable symbols. |
| Diff and documentation | Fresh final review of all 35 files; tracked/untracked whitespace and 133 local links/anchors pass. No embedded/transient fixture credential, generated artifact, home path or conflict marker. Exact implementation inventory only; planning commit preserved. |

Final implementation Gradle validation used `:shared:testAndroidHostTest :shared:iosSimulatorArm64Test :androidApp:assembleDebug :androidApp:assembleRelease :shared:assembleAndroidDeviceTest` (one successful invocation, 176 actionable tasks). Xcode used the existing iosApp scheme/project and task simulator, configurations Debug and Release, derived data under `/tmp/tillfailure-pr5-xcode`; Release set `CODE_SIGNING_ALLOWED=NO`. The earlier backend run used Java 21 and installed Node **25.9.0** and did not establish Node 22 compatibility; the separate fresh Node 22 review run above supplies that attribution. Xcode is **26.2 (17C52)**. None is a distribution-signing pass.

Build success retains existing compiler/platform diagnostics: Kotlin framework bundle-ID inference, Swift capture/concurrency warnings (including the old spike harness), linked ICU's newer simulator deployment target and skipped AppIntents metadata extraction. No dependency/toolchain upgrade or unrelated warning cleanup was made. Native process and actual interaction evidence remains separately attributed above. The final source additions after the native builds are backend race tests and documentation only; no native source changed afterward.

The exact implementation-commit inventory is **35 files** (the approved PR 5 plan itself remains in the separate preserved planning commit):

- `README.md`
- `docs/firestore-schema.md`
- `docs/firestore-security.md`
- `docs/milestones/milestone-5-plan.md`
- `docs/milestones/milestone-5-pr5-report.md`
- `docs/navigation.md`
- `docs/offline-sync.md`
- `docs/open-questions.md`
- `docs/trainer-notes-settlement-adr.md`
- `firebase/README.md`
- `firebase/functions/scripts/seed-m5-pr5.mjs`
- `firebase/functions/src/command-resolution.ts`
- `firebase/functions/src/index.ts`
- `firebase/functions/src/trainer-notes.ts`
- `firebase/functions/test/rules.test.ts`
- `firebase/functions/test/trainer-notes.test.ts`
- `iosApp/iosApp/FirebaseNativeBridge.swift`
- `iosApp/iosApp/TrainerNotesHarness.swift`
- `iosApp/iosApp/iOSApp.swift`
- `shared/src/androidDeviceTest/kotlin/com/delminiusapps/tillfailure/firebase/AndroidTrainerNotesTest.kt`
- `shared/src/androidMain/kotlin/com/delminiusapps/tillfailure/firebase/AndroidFirebaseSpikeClient.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/clients/ClientsScreen.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/clients/TrainerNoteScreen.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/clients/TrainerNoteViewModel.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/clients/TrainerNotes.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/core/designsystem/TillFailureComponents.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/di/DependencyInjection.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/identity/RoleShell.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/invitations/CommandResolution.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/invitations/InvitationClient.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/invitations/InvitationViewModel.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/persistence/PersistencePrototype.kt`
- `shared/src/commonTest/kotlin/com/delminiusapps/tillfailure/clients/TrainerNotesTest.kt`
- `shared/src/commonTest/kotlin/com/delminiusapps/tillfailure/invitations/SettledCommandMarkerTest.kt`
- `shared/src/iosMain/kotlin/com/delminiusapps/tillfailure/firebase/IosFirebaseBridge.kt`
