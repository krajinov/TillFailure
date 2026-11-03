# Milestone 6 PR 4 implementation and feasibility report

Reviewed **2026-11-03**. The candidate passed all 14 independent Android and Swift publication restart cases and the synthetic iOS publication UI flow on loopback emulators.

## Approval, baseline and Git custody

The initial branch was `docs/milestone-6-pr4-publication-plan`, HEAD `de301d3a7668975ca6becff31dad71aea827c946`. The four reviewed planning files were the only initial changes; the index was empty. Repository/ancestor instructions and all four files, including the sizing script, were read. The repository-required Compose skill was applied to the destination-owned MVI integration.

[Approval](milestone-6-pr4-plan.md#approval-and-implementation-authorization-2026-11-02) records **U1–U8** and the separate **U9** synthetic implementation authorization. No additional substantive policy choice was adopted. Only those four planning files were committed as `d37d4466a73b608379f3f068079d7f2099702531`, `docs: approve milestone 6 publication plan`. Implementation is on `feature/milestone-6-pr4-program-publication`, uncommitted and unstaged. No push, PR, merge, deployment or dependency upgrade occurred.

The [source manifest](evidence/milestone-6-pr4-source-manifest.sha256) is the exact final implementation/contract inventory. Its SHA-256 is recorded in the [verification summary](evidence/milestone-6-pr4-verification.json). The report, manifest and summary exclude themselves from that source digest to avoid self-reference. The planning commit identifies the four approved inputs separately.

## Implemented candidate

The emulator mediator implements fresh `publication-prepare`, `publish-program-template`, `version-list` and `version-get`. Auth is freshly verified, including revocation; anonymous identities and caller-supplied author selectors are denied. The same five authority documents and original author/workspace are checked transactionally.

Publication requires a saved complete draft: nonblank template/workout titles, 1–3 nonempty workouts, at most 12 items, required sets/reps/rest and nullable load. It copies exact authorized names, instructions, taxonomy, prescriptions and source provenance. Every selected source is rechecked, including unchanged selections that draft Save may retain. Stale, missing, withdrawn, foreign or differently fingerprinted sources cannot silently refresh publication content.

One transaction creates the immutable inline version, advances root schema/pointer/count/common revision and creates the immutable receipt. Both Save/Publish orderings and Publish/Publish share that revision. Exact replay returns the original decision without another version. A new identical-content publication requires another fresh preview, explicit confirmation and command. Save preserves schema-2 metadata from the stored root, accepts no publication metadata from the client, and never rewrites prior versions.

List returns five summaries plus validated lookahead, with version ID/number, title, draft revision, content hash and publication time. **Technical adjustment:** the pinned emulator rejects descending document-ID scans. Six deterministic point reads from the validated consecutive root counter preserve newest-first order and the approved maximum read budget. No history scan, offset, new index or permission was added. Point reads validate every selected version; missing/malformed lookahead rejects the whole response.

Shared code implements actual canonical hashing, strict typed response parsing, the twelfth protected command, own-resolution adapter and content-free retirement. Android and Swift expose fixed publication operations. Only preview/version-get responses receive the 192 KiB allowance. Mutation, generic transport, draft reads, resolver, pending, envelope and encrypted-store limits remain unchanged.

The existing Programs destination owns publication preview, explicit confirmation, cancellation/back, conflicts, version pages/detail and continued draft editing. Dirty or never-saved foreground drafts cannot preview/publish. Background/scope retirement clears presentation; submitted work remains protected. The candidate uses `PublicationProtocol.ISSUANCE_ENABLED=false`. The separate UI verification builds described below used an enabled Debug flag after both native matrices passed.

## Exact protected-command inventory

| Actions | Kind | Hash version | Retirement form |
| --- | --- | --- | --- |
| `create`, `resend`, `revoke`, `accept` | `invitation-create`, `invitation-resend`, `invitation-revoke`, `invitation-accept` | `invitation-v1` | Original full proof |
| `complete-onboarding` | `onboarding-complete` | `onboarding-v1` | Original full proof |
| `update-profile` | `profile-update` | `profile-v1` | Original full proof |
| `revoke-client` | `client-membership-revoke` | `client-revocation-v1` | Original full proof |
| `save-trainer-note` | `trainer-note-save` | `trainer-note-v1` | Existing marker |
| `create-custom-exercise`, `correct-custom-exercise` | `custom-exercise-create`, `custom-exercise-correct` | `custom-exercise-v1` | Existing marker |
| `save-program-draft` | `program-draft-save` | `program-draft-v1` | Existing marker |
| `publish-program-template` | `program-template-publish` | `program-publication-v1` | New compatible marker |

These are twelve business variants. The first eleven descriptors, hash versions and serialized forms retain their original semantics; new optional fields are omitted from old forms. Whole-key identity remains `lifecycleCommands/{hash(callerUid,idempotencyKey)}`. Own resolution returns the original seven content-free fields, without reading current root/version/source/workspace content. Four publication conflicts map to `not-applied-conflict`; the existing whole-key fence maps to `not-applied-fenced`. Full proof must be durably written before retirement. No new store, archive/delete/transfer, client access, assignment, media or export permission exists.

## Canonical/source binding proof

`B` contains content policy, original workspace/template/owner, title/description and the saved ordered workout/item arrays. `draftHash = SHA256(canonical({hashVersion:"program-draft-content-v1", ...B}))`. Expected root revision and the exact authority tuple are separately in the confirmed command. `C` adds the matched instructions/muscle groups/equipment to each item; `contentHash` uses `program-version-content-v1` over C.

The system fingerprint includes source kind/ID and the exact validated source definition, including instructions, taxonomy, normalized name and published status. The custom fingerprint includes source kind/workspace/ID and schema/policy/owner/name/instructions/taxonomy/revision. Custom timestamps are intentionally excluded; revision/content changes conflict. Native parsing strips C to B and recomputes both hashes, and binds preparation to the requested saved draft hash/revision. Server execution reads all matching sources in the same transaction before copying. A draft hash alone is explicitly insufficient without this fresh source check.

TypeScript and Kotlin agree on the fixed command ID `f3305d4ae3cd01f3ff117fb22b54b078b2bda261d9632501087de6552b883f6c` and request hash `9f2f8f049c555ec488c4e01388432921094a3250b348175081a665338cb0584c`. ASCII content vectors agree at draft `ec1e7cd0a5bd1f64576b0ecab0cf458792a55ea9170962faa283254a546ec51d` / content `dcfc19cf298f31055c1946bb143df1bae8b5e7cc9800dae7391719e795666811`. Quotes, slash/backslash, newline, U+2028, accented/CJK text and emoji agree at draft `d4e65118252352d8332e2302fb4f039b9b265402c286ad3b39737764c0135de1` / content `3435dade581cc2a0148fe948ca298a81b7bdd3bb0edef9defc3e5f408c9b40da`.

The host native-parser integration also consumes actual TypeScript mediator responses: 12 maximum-size source copies, 135,543 preview-result bytes and 135,422 version-result bytes including its workspace wrapper. It recomputes both hashes and rejects changed text/extra wrapper fields. These are executed checks, not modeled Kotlin DTO sizes.

## Actual measurements versus calculated bounds

Tools: isolated Node **22.23.4**, existing npm lockfile/TypeScript/Admin SDK, actual Admin SDK commit protobuf serialization, actual Kotlin persistence serialization, production Android atomic encrypted store, and production Swift encoder/atomic encrypted store. No library or encoding was upgraded.

| State | Actual bytes | Calculated allowance/bound |
| --- | ---: | ---: |
| Boundary immutable version DTO | 135,372 | 153,456 calculated; 163,840 admitted |
| Complete preview / version-get callable packets | 135,554 / 135,433 | 161,648 calculated; 196,608 admitted |
| Three-write Admin SDK commit | 146,344 (earlier run 146,343) | 220,528 payload/reserve model; 262,144 reserve |
| Boundary mutation / resolver callable requests | 1,296 / 1,331 | Existing 32,768 packet cap; resolver also 8,192 UTF-16 /24,576 bytes |
| Boundary pending command | 2,232 | 16,384 existing cap |
| Pending envelope with retained full prior note proof | 17,720 | Actual complete envelope is evaluated |
| Largest full publication proof field | 3,711 | Actual complete envelope is evaluated |
| Complete retired envelope / departure envelope | 3,038 / 3,897 | Actual retirement/departure is admitted before issuance |
| Largest measured prospective envelope, retaining full prior note proof | 21,427 | 36,500 prospective new-kind ceiling; 65,536 legacy generic cap |
| Swift production encoder sample maximum | 29,154 | 57,325 at 21,427 plaintext |
| Swift real atomic-store maximum, 40 fixtures | 29,192 | Same deterministic bound; 98,304 existing cap |
| Android real atomic-store maximum, 40 fixtures | 28,742 | Deterministic production bound below; existing 98,304 cap |

The planning model's largest retained-note envelope was 21,423 bytes; the actual Kotlin form is **4 bytes larger**, still comfortably inside the approved new-kind ceiling. State fixtures include pending, every normal/replayed/resolver proof, content-free retirement and departure-marker forms. Actual admission also checks the maximum-epoch departure marker after each retirement, including records filled to the new-kind ceiling. A retained full prior proof is included, rather than assuming an empty account record. The new marker contains no template/source/content; the retained earlier full note is not misreported as new publication retirement content.

[Deterministic calculation](milestone-6-pr4-implementation-bounds.mjs): a 16 KiB root projection plus twelve bounded instruction/taxonomy additions yields content ≤145,264 bytes; fixed metadata/wrappers yield the version/response bounds above. For actual prospective plaintext n, the unchanged Swift representation is bounded by `181 + 8*ceil(n/3)` even if every base64 symbol needs escaping. At n=36,500 this is **97,517 ≤98,304**. The fixed production Android representation is bounded by `108 + 4*ceil((n+16)/3)` = **48,796**. Random ciphertext samples support these results but do not establish the universal guarantee; the prospective-state byte checks and deterministic formulas do.

The Admin model allows ≤1,200 fixed-shape nodes, 32 bytes extra protobuf overhead per node and an 8 KiB document-path/commit-metadata reserve. The current emulator's token bound is deterministic: in Firestore emulator **1.22.0**, `FirestoreEmulatorConverters.toTransactionBytes` delegates to `LocalTransactionConverter.toTransactionProto`, which sets only the handle on a fresh internal Transaction builder. Its generated `writeTo` uses field 2 fixed64, so the token is at most **9 bytes**, including its tag. Fixed demo-project paths, three bounded document names, write/precondition wrappers and that token fit the 8 KiB reserve. This closes the bound for the reviewed emulator/SDK/inline layout, without a production-server claim. Jar SHA-256: `9b6498b7f62714d67f48f59b3818883cd682dbcd46b9f59511de81c97bb5166c`. The actual complete protobuf was also measured; its boundary document protobufs were version **136,986**, root **8,510** and receipt **759** bytes. A different emulator/SDK/layout requires re-verification, not larger generic limits.

No measured state exceeds an approved size limit. Actual native oversize-admission tests on both platforms rejected before preparation and preserved existing encrypted bytes exactly. No truncation, history clearing or new recovery store was used. Business work is bounded by at most 12 unique source reads, six version point reads, three transaction attempts and five-second elapsed checks; the latter do not cancel an in-flight SDK RPC. The shared 120/minute program admission counter is reused.

## Fresh verification and attribution

**Fresh final backend:** **289/289** tests, zero failures, under isolated Node 22 and locked dependencies. The expanded publication suite covers canonical/actual callable binding, v1 → Save → v2, byte-identical v1, metadata injection denial, old pending Save replay/conflict, explicit Save-first and Publish-first ordering, concurrent Publish/Publish, identical-content new confirmation versus replay, source changes before preview/commit and transaction ordering, custom revision/fingerprint changes, authority loss/restoration, resolver-first/writer-first races, atomic failure/version collision, strict inputs, counters/deadlines, paging and malformed lookahead. Rules tests deny direct reads/writes to roots, versions and descendants for author/foreign author/client/owner; the whole existing Rules suite also passes.

**Fresh final shared:** **259 Android-host /258 iOS-simulator** tests, no failures/errors/skips. Includes existing eleven-variant regressions, actual TypeScript response parsing, escaped vectors, typed malformed/oversize rejection, schema-2 root read/write projection, all publication proof/outcome forms, proof-write/retirement failures, simulated restart/A–B isolation, dirty-preview refusal, owned/stale confirmation, canceled/stale preview callbacks and authority-loss clearing. These simulated state tests are not claimed as actual native process-restart acceptance.

**Fresh builds:** Android Debug/Release and current device-test APK; iOS Debug/Release simulator hosts and frameworks passed. Missing temporary SwiftPM manifests/compiler modules initially broke a repeat build; an intact local checkout was verified against the exact locked Firebase revision `8c29ca981990a32e626c8617544fa65d22b1834f` and fresh build output was used. Dependencies were not upgraded. Release audits are recorded in the verification summary.

**Fresh native sizing:** Android instrumentation passed the actual store round trips and byte-preserving oversize refusal. Swift CLI used the exact production CryptoKit encoder. The unsigned iOS host initially returned `LOCKED`; read-only metadata lookup returned **-34018**, `errSecMissingEntitlement` in the installed SDK header. Rebuilding with the project's normal simulator signing restored access to the **existing** item (status 0). The harness required that existing-item lookup to succeed before any sizing write; no replacement key was created to pass. The signed real-store run passed all 40 fixtures, oversize rejection and byte-identical preservation. Native size matrices preceded the final admission-only retirement/departure tightening; serialization, store and encryption bytes did not change. The final shared ceiling/departure tests verify that additional guard; the sizing run preceded the independent native command matrix below.

The planning sizing analysis and prior PR 1–3 reports are retained context only. Their old native restart/UI/physical-device evidence is not relabeled as verification of this implementation.

## Quarantined first native dataset

**First dataset export failure.** I started separate disposable and retained-native emulator suites with the same demo project discovery identity. The new native suite seeded 18 synthetic Auth identities, authority/config data and saved drafts. During correction of an invalid Functions source path, I requested graceful shutdown with export-on-exit. Export failed with `Did not find any running emulators for project demo-tillfailure-m3`; no native exit export was produced before shutdown. A discovery-registry collision between the two hubs remains the likely cause.

Native fixture UID-inventory SHA-256: `d1be5433279ef2d5fd8df146ba986db0ae9a78ac68718f4756e6d35bc28833c3`. Provenance-ID SHA-256: `797e30bd3fe45967374f3e29f27dbdd0a16966bac621a62c1bb45d4c80605e43`. The ignored fixture record and failure logs remain retained locally, including original credentials outside the reviewed source inventory. No password/token is included here. This dataset cannot now support a claim of intact command/fence history. No substitute prefix, generation, identity, reset or rekey was used to obtain a native command pass.

**First attempt.** The iOS stage failed sign-in with `UNKNOWN` and the Android stage timed out. No successful stage marker was obtained. Absence of submitted work cannot be proved merely from those failures. Original device partitions, SDK identities, consumed-key records and keys were retained, not discarded. Do not replay/fence against recreated empty server history. Recovery requires a verified original checkpoint if one exists; reconstructing missing history is not authorized by this approval. Earlier pre-existing PR exports were not reset or rewritten; a before/after byte audit of every historical device partition was not performed and is not claimed.

**First UI attempt.** The unsigned iOS host reached the account-cleanup gate. The later signing correction established storage access. The first attempt stopped at that gate.

The first dataset stays quarantined with unknown outcomes. The independent run below used the separately authorized synthetic fixture inventory and new device instances.

## Independent native verification on 2026-11-03

One supervisor hosted Auth, Firestore and Functions. The fixtures used 18 new accounts and 16 draft roots created directly by the Admin fixture script, with zero protected command receipts. Auth sign-in and authenticated mediator reads passed for all accounts. Before either native test submitted a command, an explicit export returned success and passed checks for all 18 Auth accounts, Auth config, Firestore metadata, and a nonempty Firestore shard. Six files were checksummed. The runner used the supervisor's explicit hub address. Reset suites were outside this run.

Android used a new API 34 ARM64 AVD with a dedicated storage directory. iOS used a new iOS 26.2 iPhone 17 simulator and the normally signed Debug host. The first Android AVD creation rejected the local `google_apis` API 34 package as invalid. Selecting the installed API 34 Play Store image resolved that setup failure. Both native builds passed.

Each case ran in two actual app processes. The stage recorded the original command ID and request hash. The runner checked the server effect and verified an explicit export before stopping that process. The cold resume checked the same command binding against the compacted marker, verified clean departure, signed in as B, and confirmed B's empty recovery state and denial of A's version list. A second server audit and export followed every resume. This produced 28 verified case checkpoints in addition to the initial checkpoint.

| Case | Root revision and versions after resume | Receipt effect | Android | Swift |
| --- | --- | --- | --- | --- |
| Lost acknowledgment after commit | 2, one version | The original applied receipt exists before the process stop and survives recovery | Pass | Pass |
| Resolver-first fence | 1, zero versions | The stage has no receipt. Resume creates the fence for the original command | Pass | Pass |
| Stale confirmation conflict | 2, one version | The competitor applies. The original records a conflict | Pass | Pass |
| Interruption after durable proof and before retirement | 2, one version | Cold start retires the original proof and preserves the applied receipt | Pass | Pass |
| Injected proof-write failure | 2, one version | The original pending command survives the failed write and resolves against the applied receipt | Pass | Pass |
| Compacted cold restart | 2, one version | The persisted content-free marker matches the original applied receipt | Pass | Pass |
| Freeze and admitted-command drain | 2, one version | Freeze refuses fresh issuance. The admitted command settles with its view callback canceled | Pass | Pass |

The case evidence records command bindings, root revisions, versions, receipts and checkpoint hashes. The local scripts and logs live in the ignored run directory, which also holds the credential-bearing exports. [Verification summary](evidence/milestone-6-pr4-verification.json), [native matrix runner (local-only)](../../.firebase/m6-pr4-resume/matrix.py), [fixture and server audit (local-only)](../../.firebase/m6-pr4-resume/admin.mjs), [checkpoint verifier (local-only)](../../.firebase/m6-pr4-resume/checkpoint.py).

## Synthetic UI verification and interaction gaps

Both native matrices passed before the temporary Debug builds enabled Publish. The build script restored the candidate's original source bytes, including the disabled issuance flag. The temporary artifacts use the synthetic loopback fixture. [UI build script (local-only)](../../.firebase/m6-pr4-resume/ui-build.py).

On the signed iOS simulator, the real Programs screen opened the saved draft and displayed the publication preview with copied exercise instructions, taxonomy and prescriptions. Canceling the immutable-version confirmation left revision 1 with zero versions and receipts. Confirming the next prompt advanced the root to revision 2 and created exactly one version and one applied receipt. The versions list showed version 1. Its detail displayed the same copied content with read-only controls. Back returned to the list and then to the editable draft. Keyboard navigation also produced unsaved input, which disabled preview and version browsing. Reload discarded those edits and restored the saved draft. [Cancel audit (local-only)](../../.firebase/m6-pr4-resume/ios-ui-cancel-audit.json), [publish audit (local-only)](../../.firebase/m6-pr4-resume/ios-ui-publish-audit.json).

Simulator pointer scrolling and drag returned Computer Use error `-10005: noWindowsAvailable`. Raising and fitting the device window did not resolve it. Keyboard focus and reload let the run continue. Accessibility reads also recovered from intermittent ScreenCaptureKit errors `-3811` and `-3812`. Android interactive UI, touch and long-content scrolling, conflict presentation, large text, VoiceOver/TalkBack, OS interruption behavior and physical devices remain unverified. Both native command matrices covered the server conflict independently of its UI presentation.

After the UI audit, the runner exported the dataset again, closed the iOS test app, and took a final quiescent export. All **31** manifests and their six files per checkpoint passed byte verification before the supervisor shut down. The old dataset remains quarantined with unknown outcomes. [Final checkpoint manifest (local-only)](../../.firebase/m6-pr4-resume/checkpoint-final-quiet.json).

## Separate inherited custom-admission finding

The prior custom-admission issue is unchanged: it accepts up to 65,536 plaintext bytes without the publication/draft prospective Swift escaping bound. An admitted 51,966-byte custom state has a **138,757-byte** conservative Swift bound, exceeding 98,304; an earlier actual sample was 70,569. That is not an observed ciphertext failure and does not establish a universal custom guarantee. It remains an inherited custom/production gate. No old custom admission, encoder, pending form or generic limit was silently changed to make publication pass.

## Reproduction and final audit

Use the existing lockfile and isolated Node 22. `run-emulator-tests.mjs` now supplies matching synthetic test provenance to the worker; start disposable tests independently of retained native emulators. The final full-suite run used isolated Auth/Firestore/Storage ports and the existing callable test port. Publication fixture export is synthetic and temporary. The native fixture tests and sizing harnesses are in test/Debug-only sources; run them only with original intact custody. Never rerun the create-only seed over occupied identities or reconstruct the quarantined dataset.

Shared/build command: `./gradlew :shared:testAndroidHostTest :shared:iosSimulatorArm64Test :androidApp:assembleDebug :androidApp:assembleRelease`. Device sizing uses `AndroidPublicationSizingTest`; Swift sizing uses `ProgramPublicationsHarness` with the 40 production-serialized fixtures, requiring existing Keychain access. The standalone encoder entry is the existing `iosApp/measurement/main.swift` compiled with `PR3_SIZING`; encoding is unchanged.

The source/contract audit checks credentials, generated artifacts, user-specific machine paths, Debug hooks in Release, links, whitespace and final newlines. Evidence includes exact source and Release artifact hashes. Only the planning commit is committed; implementation, related contracts and this report remain unstaged. The candidate source retains the disabled UI gate. The independently verified outcomes and remaining interaction gaps are recorded above.
