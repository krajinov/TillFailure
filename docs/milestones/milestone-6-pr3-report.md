# Milestone 6 PR 3 implementation report

Status: **implemented on [PR #18](https://github.com/krajinov/TillFailure/pull/18) for review; synthetic emulator-only Save is enabled after the feasibility checks. Acceptance remains qualified.** 2026-10-29, Europe/Sarajevo.

Branch: `feature/milestone-6-pr3-program-drafts`. Starting HEAD and preserved approved planning commit: `1c0fd02dc2ebcaf169ddaa83328151d55b2bf219` (`docs: approve milestone 6 program draft plan`). Baseline main/merged PR #17: `b67c5a557314d032cd0a1130a6c3176a1124ddba`. P1–P6 and separate P7 authorization are recorded in the [approved plan](milestone-6-pr3-plan.md#approval-and-implementation-authorization-2026-10-28). The original three unstaged files were inspected and continued; the early status is condensed below into a historical chronology. No planning commit was rewritten. Josip subsequently authorized bounded final review, one implementation commit (`feat(programs): add protected explicit draft saving`), normal push and a PR against main. This report accompanies that implementation commit; the separate planning commit remains intact. Merge, deployment, production/offline enablement and PR 4 work remain excluded.

## Implemented scope and contracts

A current trainer author can prepare/list/get and explicitly Save their own draft through `programDraftsEmulator`. One root at `workspaces/{wid}/programTemplates/{templateId}` holds immutable author/workspace/creation metadata, a shared revision and ordered stable-ID workout/item arrays. Root replacement and the immutable lifecycle decision commit atomically. Omitted nested content disappears in that replacement; there are no child documents or partial tree writes. Same-author independently authorized restoration permits fresh reads/new commands; it never rebinds an old command. Foreign and missing details fail without revealing content. Direct Rules remain denied.

The destination-owned MVI editor provides the system/custom picker and current source instructions, title/description, nullable metric prescriptions, add/remove/move controls, explicit Save, dirty departure, source repair and revision-conflict Reload. Keyboard input remains raw until exact validation; kg converts to integer grams without binary rounding. All reorder operations have labeled buttons, boundary states, heading focus requests and polite status announcements. These implementation properties do not establish screen-reader acceptance.

Limits remain exactly approved: **3 workouts, 12 total items, 650 combined Unicode scalars / 2,600 raw UTF-8 bytes**, title 80 / description 240 / workout 40 / copied name 120. Sets 1–20, reps 1–1,000, rest 0–3,600 seconds and load 0–1,000,000 grams are nullable integers. Empty/incomplete drafts save; invalid types, structure or raw numbers do not. A source copy holds only its exact name and identity/revision/hash. Unchanged saved item tuples/names can be retained after source loss; new IDs or changed selections require matching currently authorized transactional source reads. Retention grants no source-detail access. There is no silent refresh/truncation.

Unsent edits live only in foreground memory and clear on known scope/access loss, background, disposal or confirmed departure. A timeout does not create a new Save opportunity: `needsRecovery` requires recovery and a fresh Reload. Explicit Reload confirms replacement and preserves foreground input if the authorized read fails. Submitted bytes stay in the existing encrypted UID partition until full proof is durable and retirement succeeds; retirement leaves a content-free consumed-key marker. No new journal, payload store, dependency, Rules/index change, publication/version/archive/delete/duplicate/transfer/search/media or Assign control exists.

## Exact protected inventory and compatibility

The code now has **eleven** protected variants: invitation `create`, `resend`, `revoke`, `accept`; `complete-onboarding`; `update-profile`; `revoke-client`; `save-trainer-note`; **both** `create-custom-exercise` and `correct-custom-exercise`; and new `save-program-draft` (`program-draft-save` / `program-draft-v1`). Booking/assignment primitives and reads are not extra protected variants.

New optional draft fields use omitted defaults for old serialized records. Existing descriptor/hash branches and terminal forms remain: seven older variants retain full legacy proofs, notes and both custom variants keep their existing compaction policy. Draft normal decisions are saved/revision-conflict/source-conflict, each with exact replay semantics; resolver outcomes remain applied/conflict/fenced. Shared whole-key IDs, generation coverage, missing-history locks, immutable fences and clean-departure/account isolation are reused. No old encoding or admission policy was silently changed.

## Executable feasibility and admission

[ProgramDraftTest](../../shared/src/commonTest/kotlin/com/delminiusapps/tillfailure/programs/ProgramDraftTest.kt), [native fixture export](../../shared/src/androidHostTest/kotlin/com/delminiusapps/tillfailure/programs/DraftNativeFixtureTest.kt) and [TypeScript vectors](../../firebase/functions/test/program-draft-contract.test.ts) exercise production DTOs, canonicalization, explicit nulls and serializers. These are actual serializer outputs, distinct from the historical documentation model. An additional escaped quote/slash/backslash/accent/emoji/LF/U+2028/CJK vector agrees at `ba1653c9e5c922c96c380336e3c980645ddfb6251c0351904563ed43a1f60772`. Canonical command SHA-256 is `9d55a246b963653fdacb83755a175dd2d9dea417d0679ba3724121c1bab17018`; system-source fingerprint is `a7b1b8560a24779cf202e9618d4083da05dcc2b725bfdbfd77ee18d430899647` on both platforms/languages.

| Object/state | Actual production serialization or encoding | Ceiling / qualification |
|---|---:|---|
| Entire resolver wrapper | 7,580 UTF-16 units / 8,880 UTF-8 bytes | 8,192 units / 24,576 bytes |
| Complete canonical descriptor | 7,545 UTF-16 units / 8,845 UTF-8 bytes | Included in resolver and callable checks |
| Complete Save / Resolve callable bodies | 8,854 / 8,889 UTF-8 bytes | 32,768 bytes each |
| Pending command | 9,790 UTF-8 bytes | 16,384 bytes |
| Full pending terminal proof | 11,133 UTF-8 bytes | Included in complete envelope |
| Largest draft envelope with supported full note history | 36,407 bytes | New-draft admission 36,500; existing plaintext 65,536 unchanged |
| Swift AES-GCM + production sorted-key JSONEncoder, that exact envelope | 49,466 bytes in recorded encoder run | Actual randomized sample; universal bound 97,269 |
| Swift deterministic bound at admission ceiling | 97,517 bytes | Existing 98,304 design budget |
| Android actual encrypted store, representative near-maximum full-proof state | 48,702 bytes for 36,396 plaintext | All 49 exported states write/read round-trip; encrypted assertions≤98,304 |
| Admin SDK boundary Save commit | 9,130 protobuf bytes /2 writes /12 unique sources | Actual intercepted SDK request, not a JSON estimate;64KiB write reserve remains conservative |

The 19 draft states are prepared plus all six normal and three resolver full-proof/retired pairs. Exports also include 20 supported prior full-resolution-proof/retired history classes, 9 post-retirement switch states and 1 separately scoped custom counterexample. The serializer test uses maximum-width numeric fields, 128-unit escaped UID/workspace/history identifiers, complete defaults/metadata and duplicated payloads. Unicode/quotes/backslashes, nulls, exact type decoding, missing/extra fields and invalid structures are checked. Emoji text maximizes bytes; quote/backslash expansion maximizes allowed text UTF-16 units. Every legal text scalar costs at most 2 JSON UTF-16 units / 4 UTF-8 bytes; disallowed controls/surrogates cannot grow this bound. Production tests also assert the actual 8,854/8,889-byte callable bodies and canonical descriptor equality through `InvitationJson`/`CommandResolution`. The historical 9,414-byte read-projection maximum remains **calculated**; production admission measures the complete callable bytes each time.

`DraftBudget` constructs the actual prospective envelope with the **actual retained record**, preferred-workspace metadata, both resolution-start flags, pending command, all normal proofs (including replay flag widths), every resolver proof and every retirement. Optional recovery grants/snapshots/journals/uploads, a switch in progress, occupied pending/proof state, consumed keys or any over-cap candidate refuse admission. It never clears history. Native account locks cover final recheck and preparation; admission precedes persistence and transport. Workspace-preference writes are fenced while a draft is pending, preventing later metadata growth from consuming the reservation. The smallest full pending proof dominates a longest retired marker plus maximum departure marker by at least 1,049 bytes in tested identifier boundaries; copied metadata cancels and payload growth only widens the margin. Actual switch envelopes also pass both native paths.

The deterministic Swift bound uses the **actual wrapper schema and key identifier**, measured empty header, 12-byte nonce, 16-byte separate tag and unchanged JSONEncoder slash escaping: header +2×(16+24+4×ceil(plaintext/3)), equivalent to 181+8×ceil(n/3) for the production identifier. Base64 symbols can add at most one escape byte each; treating padding as escapable is conservative. Random ciphertext samples do not prove this bound. Android uses the existing `AndroidEncryptedEnvelope` and `PersistenceJson` serializer, with a 12-byte nonce and 16-byte GCM tag appended to ciphertext before base64. Its production fixed-identifier byte count is 108 + 4×ceil((n+16)/3): **48,796 bytes at 36,500 plaintext**. The base64 alphabet needs no Kotlin JSON escaping; the measured test alias adds 42 ASCII header bytes (36,396 plaintext therefore produces 48,702 bytes). Actual atomic store round-trips accompany this smaller deterministic bound; no JSONObject or alternate encoder is used. Synthetic all-slash encoder probes are conservative encoding probes, never described as ciphertext measurements. No encoding/cap was changed to fit.

Oversize admission tests assert zero preparation writes and byte-identical existing encrypted storage. Native admission/restart cases include refusal/freeze/cancel handling; no record is truncated, split, rekeyed or retired to make a new Save fit. An otherwise legal draft can still be refused if its actual retained history/metadata consumes the available capacity; shortening foreground content is the only available in-slice repair.

## Inherited custom-command caveat — separate finding

A schema-valid clean envelope with UID `A`, preferredWorkspaceId containing 50,000 `x` scalars and a minimal valid custom-create command is accepted by the **existing** custom admission. Its actual full prospective proof envelope is **51,966 bytes**, below 65,536. The unchanged production Swift encryption/encoder produced **70,569 bytes** in a recorded production-encoder sample, below 98,304; Android also round-tripped that fixture. **No actual storage failure or encoded-budget overflow was observed.**

However its deterministic conservative Swift upper bound is **138,757 bytes**, above 98,304. Thus the prior compact-base64 argument is flawed, and existing admission accepts a state for which the design-budget guarantee is not established. This bound is not a claim that every ciphertext or an observed ciphertext exceeds the budget. Rare random samples are unnecessary to disprove the old universal sizing argument. The native store has no existing 96 KiB file-length rejection to report as a runtime failure.

The smallest separately scoped correction is to apply a native-wrapper bound to **new custom admissions** against actual prospective states, retaining all old records/read-or-lock/recovery paths; alternatively explicitly approve a different encoding policy after compatibility review. That decision is outside the approved new-draft ceiling. No inherited policy or Swift encoding was changed here, and draft admission is not claimed to fix it. Existing preferred-workspace validation breadth is part of the counterexample, not a newly introduced grant.

## Transaction boundaries, security and races

Business transactions use at most 3 attempts and 5 seconds elapsed checks. The separate fixed per-UID 120/minute admission counter costs 1 read / 1 write. Save reads generation config + 5 authority docs +receipt +root +at most 12 distinct source docs +optional catalog entitlement: **≤21 reads/2 writes per business attempt**,22/3 including admission. Replay ≤8 business reads/0 writes; prepare 5/0; get 6/0; list 5+21/0. All reads precede writes. Source deduplication only reduces counts. At most 66 read attempts / 9 write attempts across both bounded transactions is an attempt bound, not nine committed effect writes.

Loaded authority documents≤16KiB each, config/receipt/entitlement≤4KiB, root projection≤16KiB and source≤24KiB; maximum accepted Save document-JSON budget 396 KiB and logical writes 20 KiB remain **calculated validation bounds**, not protobuf transfer measurements. List lookahead is bounded 21 documents and≤32KiB result; get≤24KiB result. The9,130-byte actual Admin commit includes paths and transaction metadata for the measured boundary fixture; wider legal identifiers and different server transaction tokens remain covered by the conservative 64 KiB reserve, not claimed as exhaustively measured wire traffic. Malformed documents can transfer before rejection.

Fresh emulator tests prove concurrent same-revision Save yields one Save and one durable conflict; exact retries return the same decision; root replacement removes obsolete content; resolver-first fencing prevents delayed Save; commit-first resolution observes the receipt; foreign/missing custom sources yield indistinguishable minimal conflicts. Source correction/loss rejects new selections while unchanged saved copies remain usable. Explicit transaction barriers exercise Save-first and source-first/lifecycle-first lock orderings; after those changes, new selections/old authority fail. Independent restoration accepts a fresh authority tuple and rejects the old one. No source fetch grants arise from retained copies. Shared/native tests cover malformed reply/type handling, stale callbacks, raw invalid numeric preservation, timeout/recovery fences, removal confirmation and stable-ID moves.

Global Firebase Auth verification and Firestore commit are not one cross-service atomic transaction; disable/revocation between those services remains an inherited boundary. Every subsequent operation rechecks. This slice adds no production IAM/index/read/offline grant.

## Verification attribution

Fresh executable evidence on this implementation:

- Isolated **Node22.23.4**, existing lockfile: full backend/Rules suite **270/270 passed**, followed by expanded targeted draft/vector/transaction/wire suite **9/9 passed** after its final transaction/wire additions and a final **1/1** TypeScript escaped-vector run. Production backend behavior was unchanged by those later additions (one stale comment corrected). No reset suite ran against the preserved native supervisor.
- Shared suites **241 Android-host /242 iOS-simulator tests**, zero failures/errors/skips, including final departure-capacity lemma. Android Debug/Release and device-test APK build; iOS Debug/Release framework and native simulator host builds passed. Final unsigned iOS Release host rebuilt after presentation fixes. Android Release dex and iOS Release executable contain none of the new Debug UI/bootstrap/native-harness markers. No dependency/lockfile upgrade.
- Actual Android encrypted AtomicFile/Keystore test passed with all49 production-serialized exported states. Actual Swift CryptoKit + unchanged production JSONEncoder passed on the same 49 states. A final simulator run also passed all 49 through the real Swift atomic store and `IosAtomicFilePersistence` production serialization, then proved oversize admission/prepare rejection with byte-identical existing encrypted bytes (maximum sampled stored output 70,541 bytes including the separately scoped custom fixture). The largest sampled draft store output was 49,460 bytes; these random output samples remain separate from the deterministic bound. Only sizes/assertions were printed; no payload/ciphertext/key/credential output.
- **16 Android and 16 Swift** native process-terminate/relaunch cases on Android 14/API 34 arm64 and iOS 26.2/iPhone 16e: create/update × applied, resolver fence, revision conflict, source conflict, durable proof before retirement, injected proof-write failure, already-retired cold start, and admission/freeze/cancel. Exact original key/payload/partition survived; full proof preceded content-free retirement; clean A→B isolation was checked. These matrices ran before later presentation-only timeout/focus/prompt corrections and Debug Android UI bootstrap; their protocol/storage evidence is retained for the unchanged core, not relabeled as interaction evidence for later UI source.

Reproducible seams: [Android sizing](../../shared/src/androidDeviceTest/kotlin/com/delminiusapps/tillfailure/persistence/AndroidDraftSizingTest.kt), [Android restart harness](../../shared/src/androidDeviceTest/kotlin/com/delminiusapps/tillfailure/firebase/AndroidProgramDraftsTest.kt), [Swift restart harness](../../iosApp/iosApp/ProgramDraftsHarness.swift), [production Swift sizing entry](../../iosApp/measurement/main.swift), [transaction tests](../../firebase/functions/test/program-drafts.test.ts). Host tests construct/export only synthetic fixtures to a temporary directory; push them to the sizing test's documented temporary device path, then run its instrumentation class. Compile the Swift entry with `-D PR3_SIZING` alongside `RecoveryPersistenceBridge.swift`; it calls the exact production encoder. The Android Debug-only private-file authentication bootstrap signs into the create-only UI identity; it never injects editor state or submits Save. Its class and invocation are absent from Release.

### Actual interaction and remaining acceptance

Fresh iOS 26.2 / iPhone 16e simulator UI through computer use: Retry reached Trainer Home; Programs opened the own-draft empty list; Create opened the editor; real title entry and software-keyboard toggle worked; explicit Save returned idle with Save disabled; Add workout changed the form; Home produced dirty departure confirmation; Cancel preserved the unsaved workout; confirmed Leave reached Home; a fresh Programs list displayed the saved title; reopening showed that title with **no unsaved workout**, and Save disabled. These are actual UI actions and fresh reads, not model injection.

The UI scroll API and coordinate-drag attempts failed in the computer-use layer with `noWindowsAvailable`; Mac locks interrupted attempts and the user unlocked it. Lower picker/numeric/reorder/Save-with-keyboard/Cancel-bottom controls and full-form scrolling could not be completed through that tool. The fresh Android UI bootstrap authenticated, but no actual Android editor interaction pass is claimed. Native harnesses and state tests do not replace these gaps. Normal/200% Android font size, OS Dynamic Type, TalkBack/VoiceOver, actual reorder focus/announcements, two-session conflict repair through UI, source replacement through UI, software numeric keyboard/caret/selection, system Back/predictive Back and OS-event stimuli remain unverified. Pencil connection attempts failed; no fresh frame acceptance or design change occurred. Physical device/key/backup/shared-device checks remain open.

These limitations retain [PR1](milestone-6-pr1-report.md#actual-interaction-and-remaining-acceptance), [PR2](milestone-6-pr2-report.md#actual-interaction-and-remaining-acceptance) and [M5 H1–H11](milestone-5-closeout.md#remaining-gates-and-dependencies), M2/M4 accessibility and production privacy/retention/export/deletion/IAM/signing/catalog rights gates. Implementation and builds are not full milestone acceptance. Publication/complete version content still needs PR 4 feasibility and approval; assignment remains M7 with the existing assignment-index limitation.

### Failed attempts and preservation

Retained failures: initial sandbox Gradle cache denial followed by approved elevated run; backend callable tests first used the wrong Functions port, then a reset-oriented full run stalled/cancelled before a fresh isolated 270-pass run; do not attribute the stall to an unproved cause. The added wire test initially imported a non-exported SDK subpath and failed before measurement; corrected to the public protobuf export, final 9/9 passed. A sizing install used an incorrect generated APK path and failed before instrumentation; the actual APK path succeeded.

Android's retained AVD had stopped; cold restarting the same AVD used no wipe. An Android runner initially watched instrumentation stdout rather than logcat, and a temporary runner indentation error interrupted orchestration. iOS first launch used incorrect simctl stdout argument syntax; its first applied-stage harness compared NSObject.description rather than Kotlin's exported description_ getter. That exact original pending key was resumed after correction. None of these attempts was repaired by resetting/rekeying an unresolved command.

Create-only seeds used namespace `m6p3r20261029a`, generation `b91f0176ec74475e8cf7406e25b7e9d3` created-or-verified with immutable provenance `m6p3-intact-20261029a`, 36 new native/UI identities, a fresh UI AVD and fresh iOS simulator. Original M5/PR 1/PR 2 datasets, identities, consumed keys, pending partitions and coverage were retained. A preserved native supervisor was used after reset suites; complete Auth/Firestore/Storage exports were taken initially, after matrices and after the UI Save. Private environment/credential files and exports remain ignored locally; no raw exports/logs are part of the change. Native test partitions/keys were deliberately retained. No cleanup of original account state occurred. After the final export, the temporary supervisor shut down gracefully; generated root configs were moved to the ignored evidence directory. No original fixture or pending data was removed.

## Rollback and decisions still required

Rollback can hide Programs/new issuance, but must retain schema readers or explicit locks, original pending/proof recovery, generation/fence/consumed-key behavior and immutable server history. Do not install an older build by deleting unknown fields, downgrade records, reset a partition or bypass clean departure. Existing new-kind pending records must resolve/retire safely before removing support; server latest content remains in custody. No archive/delete/export/support grant is implied.

No product contradiction was found for the approved draft scope. Remaining decisions: the separately scoped inherited custom-admission correction; full interaction/accessibility and release acceptance; PR4 publication content/size policy; M7 assignment; real-data, privacy/retention, production and offline policy. These are not reasons to leave the authorized draft serializers/mediator/recovery/editor unimplemented.

## Exact file inventory and source fingerprint

Pre-staging audit: all 46 changed files were audited for credentials/generated output/machine paths, whitespace, conflict markers and final newlines; all 226 local file/anchor links resolve. The source manifest matches the exact final files. New Debug hooks are absent from final Release binaries. The Git index was empty at that audit.

All paths below are repository-relative. Runtime/test/harness/seed manifest SHA-256 (sorted `sha256  path` lines with LF, excluding documentation): **dd07cf8d563d158bcd3271e6a042b8990f3fc05d9f512dbe61057d72f18117f8**. The manifest identifies final source; native matrix and full-suite timing qualifications above remain applicable. Documentation and the historical report are excluded to avoid a self-referential hash.

```text
7bfe916ed6c35205a98aa9971a86e65243b0bf5f562f8e63233a19e0a454289e  androidApp/src/debug/kotlin/com/delminiusapps/tillfailure/ProgramDraftsUiFixture.kt
3236f1a6da84babe0709eed3aa9e45fbf93162d3a4b4cb94ac3c0c4bbfbbd962  androidApp/src/main/kotlin/com/delminiusapps/tillfailure/MainActivity.kt
19df24689493dc58683d188ebce9f0fd5abff36aadeee2db36060e496191a3bf  firebase/functions/scripts/seed-m6-pr3-ui.mjs
94fb757b2fa3180d1e03a118ef8b6b9d1139aff042b0e78eabe55167d4232858  firebase/functions/scripts/seed-m6-pr3.mjs
a5300ae826458166bfc79ed90348a15b8a570688feaf90a7a728305eb98a542a  firebase/functions/src/command-resolution.ts
3b24c0047932df4254d211fa7f401dfb7bddca7ac85effc39f19dcd3680e2a40  firebase/functions/src/index.ts
c4b237d3105e5fd9874721824e8714073c1c4fc5b67917a342495b7297c7ea4e  firebase/functions/src/program-draft-contract.ts
6736fc21d308b3d2aad6d446f29f7b5ea8916c2f294120592980142717d92493  firebase/functions/src/program-drafts.ts
14c25b6dd19dd7d01c6cb2da830c2654d54701278264dc3b120bb275af71594f  firebase/functions/test/program-draft-contract.test.ts
f8b189b060ac23745edca7c3e6d097e33c29f4a74d7e6fd62fe4de3f9da30f1a  firebase/functions/test/program-drafts.test.ts
ebb1bf0f8a53141e040ccdd888b40cf0394238042aa736b80cc158779757628b  iosApp/iosApp/FirebaseNativeBridge.swift
635b059ef1ef9d82820ed2df563483d7330efb64d6f2d27dae065c4cab439025  iosApp/iosApp/ProgramDraftsHarness.swift
21e27e77b4797eaa4c1d9ca6e86558b15ab9da13655e914701e24095032a6af3  iosApp/iosApp/RecoveryPersistenceBridge.swift
a46da39de18cf43700c518eeebd9387f3011f644244842a6670c0eb6b602ed2d  iosApp/iosApp/iOSApp.swift
e7f7330a67008fad2dd2d420305e5f817a38a1b7114772c3b66927d491cedd09  iosApp/measurement/main.swift
76ee4367f9148bd8c8e9bd4da10b5868b9ec05b581c052e6fadfd5ce9aa342b9  shared/src/androidDeviceTest/kotlin/com/delminiusapps/tillfailure/firebase/AndroidProgramDraftsTest.kt
17c711fd7aabe7f33694f301bee3681bb3bad176bb5515f3290e7ae2dab9f3f3  shared/src/androidDeviceTest/kotlin/com/delminiusapps/tillfailure/persistence/AndroidDraftSizingTest.kt
3148e4c4182ad638d4bac2fc0a020d4a5e8cf5cb245672b90c1d7a426c29e2ae  shared/src/androidHostTest/kotlin/com/delminiusapps/tillfailure/programs/DraftNativeFixtureTest.kt
7f933c4dcc994aec51066a1a57ea68f94ce0fc184ba7ede837ca44fcfe8573fb  shared/src/androidMain/kotlin/com/delminiusapps/tillfailure/firebase/AndroidFirebaseSpikeClient.kt
3e7cc0a035df21021d25bb1e7e40029d5974362d2f6aaf0d7d9720f15c4acbd9  shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/di/DependencyInjection.kt
3cd156d9a301f3bbb2fe029c9bbec5b10c90db63cb793d0bd4ad05ab8f142038  shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/identity/IdentityRoot.kt
9e047ea8c6e24ff65920c166b8ef8338ca1cfd74a31c8e0701592ab353553fd3  shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/identity/ProductIdentityClient.kt
07e3910b5a63183ab58333e54294d27fe4364246a66154beace5a6ad3a17c074  shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/identity/RoleShell.kt
8fe0a9e20e62f780945188e15764be75719189ffcd5bbcc877fc7a443a71bf9f  shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/invitations/CommandResolution.kt
b334eab6fdbaa11f8210c629fa3b6d788956a741dde97fd9bc09b759d85d9947  shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/invitations/InvitationClient.kt
7ba3e06ef2cb63c3539120cc50718e8b00228a800579313ce9d91cfb79b0ab72  shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/programs/DraftBudget.kt
b34dc4f3b326b043e09c206e72633a52ba89a4254b68d4b97060c646781c6b81  shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/programs/DraftProtocol.kt
bd36778d8b29f540d17ea89df20058b7226d1d95708694da7fe95740a0b33a8a  shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/programs/DraftRepository.kt
4b91ec37bb81801e47ad02ef10f8bb2216bcb349663fe5ee7a476dafcb3493c6  shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/programs/DraftSources.kt
5e8ab2c57c57cc58ae73cd9d07feefa726a1010ebccd3e83dfa469b5b6d3b59d  shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/programs/DraftsScreen.kt
6b724ef9c5d9fdc39b4fc852271fae69fc197c0bdb3c25914e59b80a1b0b9335  shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/programs/DraftsViewModel.kt
36448e78dc0eea5b5f9cf3ba9733dc79f47397a93f813caf8298224c26e67f5d  shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/programs/ProgramDraft.kt
93d594a1599cad1dc4e5000d51074c1fcb6f5504f06a0a39574fc866aefd95d5  shared/src/commonTest/kotlin/com/delminiusapps/tillfailure/programs/DraftsViewModelTest.kt
acf95d68c8103a354da7ca07ce74bdb918ed8d64cd2b4f3c1459d56491107250  shared/src/commonTest/kotlin/com/delminiusapps/tillfailure/programs/ProgramDraftTest.kt
65b816d11e7973fa963cc0d9bd7db1a0afbf7a45f2e35ca4f888a8a243c3a8ea  shared/src/iosMain/kotlin/com/delminiusapps/tillfailure/firebase/IosFirebaseBridge.kt
```

Complete implementation changed-file inventory:

```text
README.md
androidApp/src/debug/kotlin/com/delminiusapps/tillfailure/ProgramDraftsUiFixture.kt
androidApp/src/main/kotlin/com/delminiusapps/tillfailure/MainActivity.kt
docs/firestore-schema.md
docs/firestore-security.md
docs/milestones/milestone-6-plan.md
docs/milestones/milestone-6-pr3-plan.md
docs/milestones/milestone-6-pr3-report.md
docs/navigation.md
docs/offline-sync.md
docs/open-questions.md
docs/product-scope.md
docs/trainer-notes-settlement-adr.md
firebase/functions/scripts/seed-m6-pr3-ui.mjs
firebase/functions/scripts/seed-m6-pr3.mjs
firebase/functions/src/command-resolution.ts
firebase/functions/src/index.ts
firebase/functions/src/program-draft-contract.ts
firebase/functions/src/program-drafts.ts
firebase/functions/test/program-draft-contract.test.ts
firebase/functions/test/program-drafts.test.ts
iosApp/iosApp/FirebaseNativeBridge.swift
iosApp/iosApp/ProgramDraftsHarness.swift
iosApp/iosApp/RecoveryPersistenceBridge.swift
iosApp/iosApp/iOSApp.swift
iosApp/measurement/main.swift
shared/src/androidDeviceTest/kotlin/com/delminiusapps/tillfailure/firebase/AndroidProgramDraftsTest.kt
shared/src/androidDeviceTest/kotlin/com/delminiusapps/tillfailure/persistence/AndroidDraftSizingTest.kt
shared/src/androidHostTest/kotlin/com/delminiusapps/tillfailure/programs/DraftNativeFixtureTest.kt
shared/src/androidMain/kotlin/com/delminiusapps/tillfailure/firebase/AndroidFirebaseSpikeClient.kt
shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/di/DependencyInjection.kt
shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/identity/IdentityRoot.kt
shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/identity/ProductIdentityClient.kt
shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/identity/RoleShell.kt
shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/invitations/CommandResolution.kt
shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/invitations/InvitationClient.kt
shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/programs/DraftBudget.kt
shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/programs/DraftProtocol.kt
shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/programs/DraftRepository.kt
shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/programs/DraftSources.kt
shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/programs/DraftsScreen.kt
shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/programs/DraftsViewModel.kt
shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/programs/ProgramDraft.kt
shared/src/commonTest/kotlin/com/delminiusapps/tillfailure/programs/DraftsViewModelTest.kt
shared/src/commonTest/kotlin/com/delminiusapps/tillfailure/programs/ProgramDraftTest.kt
shared/src/iosMain/kotlin/com/delminiusapps/tillfailure/firebase/IosFirebaseBridge.kt
```

## Historical implementation chronology

- 2026-10-28: planning-only calculations found the proposed 7,580-unit resolver / 9,790-byte pending / 36,407-byte envelope. Standalone all-slash Foundation probes measured 97,517 at proposed admission and 174,949 at the old full plaintext ceiling; these were encoding probes, not ciphertext evidence. The report correctly kept Save disabled at that point.
- The first DTO-only continuation added ProgramDraft.kt and ProgramDraftTest.kt. One Android-host test measured 7,580 UTF-16 units / 8,880 UTF-8 bytes. A sandbox Gradle cache-lock failure preceded an elevated successful run. That check was incomplete feasibility evidence and did not authorize issuance alone. Historical source hashes were `8415f69b7e5f142cb94c2408c305b086217d1ad6b687b812e68c66f0193b6b57` and `d9afccf99db3d3b33b709c0dcf938a3416ccd51e6f7bd43fcf95774064073319`, respectively.
- 2026-10-29: actual serializers, prospective states, native encoders and deterministic bounds established the approved useful scope before issuance. Mediator, ledger adapter, native integration and editor followed. The detailed failed attempts and retained evidence above remain attributable to those runs.

## Final review and publication authorization (2026-10-29)

Remote main remained `b67c5a557314d032cd0a1130a6c3176a1124ddba`; no remote feature branch or existing PR was found at review start. The initial index was empty and the complete 46-file inventory matched the report. The initial source manifest matched `4efdb99b232286e2f9f2c3c942b7ae7dfdc8a47432318aafaee2f0d25d716085` before the corrections below.

Final review corrected two presentation callback boundaries: picker Back/Cancel now cancels and invalidates its read/timer while preserving foreground edits, and completed operations invalidate their callback generation so duplicate late results cannot replace later edits or guidance. Two regression tests cover both cases. Protocol, DTOs, admission caps, native storage/encoding and all eleven command variants were unchanged by final review. The separate native ownership/freeze locks contain preparation and its repeated prospective admission; the earlier UI admission is advisory, not a stale permission to write. Existing pending identity matching, resolutionStarted fencing and proof-before-retirement remain mandatory.

Fresh final-review shared checks: **243 Android-host / 244 iOS-simulator tests**, no failures/errors/skips, and Android Debug/Release builds. The original Node 22 270/270, expanded 9/9 and escaped-vector run, 49 native sizing fixtures/byte-preservation checks, sixteen restart cases per platform and exact iOS interaction sequence are retained evidence, not final-tree reruns. Final review changes presentation only; actual interaction/accessibility gaps remain unaccepted. Fresh final-review iOS Debug and Release simulator host builds also passed. Final Android dex and iOS executable audits confirm the new Debug fixture/harness/measurement hooks are absent. Native process-restart and UI interactions were not rerun after these presentation corrections.

The inherited custom-admission example remains an accepted state without an established conservative encoded-budget guarantee; its measured samples stayed below budget with no observed overflow or storage failure. Draft admission does not fix it. Recommend a separately authorized prospective native-bound check for new custom admissions while retaining old recovery. This does not prevent review of the approved draft slice, but remains an explicit acceptance/production gate; no existing custom policy or Swift encoding was changed.

## PR #18 active-tab reselection correction (2026-10-29)

Starting local and remote feature HEAD: `d8810c5ed1651456e459c792aa78d13f2afb5818`; index and working tree were clean. The [P2 review thread](https://github.com/krajinov/TillFailure/pull/18#discussion_r4413291786) identified a real shell dispatch defect: selecting the active Programs tab sent Leave before same-page navigation was rejected, permanently fencing the still-mounted ViewModel. The production shell selection dispatcher now rejects same-page, stale-destination and foreign-owner selections before invoking any departure callback. Programs, custom exercise, note and profile handlers share this boundary; selecting a parent tab from a different child page still dispatches genuine departure. No ViewModel fence, confirmation, protocol, pending recovery, storage, backend, dependency or Release hook changed.

Four regression tests exercise the same dispatcher invoked by the shell, with the real DraftsViewModel for list/editor/picker and departure flows. They check repeated selection, no dirty-edit prompt or loss, subsequent editing/Save/Back, Stay, owned confirmation, queued stale Save/edit rejection, one-shot navigation and equivalent shell handlers. Before the guard was added, three of these tests failed against the extracted original dispatch behavior (12 targeted tests, 3 failures); after the guard, all passed.

Fresh checks for this correction: **247 Android-host / 248 iOS-simulator shared tests**, zero failures/errors/skips; Android Debug assembly and iOS Debug simulator host compilation passed. Commands: `./gradlew :shared:testAndroidHostTest :shared:iosSimulatorArm64Test :androidApp:assembleDebug --console=plain` and the existing Xcode Debug simulator build. Earlier Release builds/isolation audits, backend 270/270 and targeted 9/9/vector checks, native sizing/rejection and restart matrices remain retained evidence, not reruns for this navigation fix. No backend or storage matrix was rerun.

Fresh focused native evidence: installed the corrected iOS Debug host into the preserved iOS 26.2 / iPhone 16e simulator without clearing application data. After online re-verification, Programs mounted; its draft read returned “Access is unavailable. Unsaved edits were cleared.” Two actual repeated bottom-tab selections left that list unchanged, and a subsequent Home selection successfully navigated. This proves the native mounted-list reselection/departure path only; it does **not** establish fresh native editor/picker/Save/dirty-confirmation acceptance. Shared tests cover those flows. No Android interaction rerun is claimed, and existing scrolling, accessibility, OS-event and physical-device gaps remain.

Native attempt attribution: the first emulator startup restored the full prior export but Firebase rejected the temporary absolute Functions source path. It was gracefully exported/stopped, then restarted from that export using the existing relative paths. The app's temporary offline authority gate was recovered with Retry. No identity, pending partition, consumed key, coverage or fixture was reset/rekeyed/reseeded to obtain a pass; no attempt was made to bypass the denied draft read. Complete new exports were preserved separately from the original dataset. Initial sandbox-only simctl/adb inspection was unavailable; elevated simctl and computer use enabled the bounded iOS check. No backend/storage matrix was rerun.

The correction changes only RoleShell.kt, DraftsViewModelTest.kt and this report. The manifest above is refreshed for these source changes; preceding verification remains attributed to its original tree. Existing custom-admission guarantees and their explicitly qualified finding are unchanged. The correction remains within PR 3, with no publication, assignment, offline or production enablement.
