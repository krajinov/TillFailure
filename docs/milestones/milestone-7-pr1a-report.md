# Milestone 7 PR 1a — assignment contract and feasibility

Status on 2026-11-13: **isolated prerequisite evidence complete; product assignment issuance absent; PR 1b unauthorized.** Useful maximum-size single-workout copies and prospective recovery states fit the independently bounded admission. Two required production corrections were demonstrated in isolated experiments: legacy writer exclusion from protected keys and direct trainer-index read denial. Neither correction is installed in product code or Rules here. This report does not accept Milestone 6 or the end-to-end Milestone 7 slice.

## Baseline, approval and custody

The inspected baseline was `docs/milestone-7-plan` at `de8d7256a587d8548b3512ac1ffca25fa7fa9c19`, the main revision containing merged publication PR #19. The index was empty and the five planning changes were the only working-tree changes. Repository instructions, the five planning files, source/security/recovery contracts and earlier acceptance findings were reviewed. Josip approved **M7-D1–D9**, and **M7-D10 solely for PR 1a contract/feasibility work**. The exact approval is recorded in the [plan](milestone-7-plan.md#approval-and-pr-1a-authorization-2026-11-13).

Commit `21633e0656d97e3040070f8b3a8002682953a2f8`, `docs: close milestone 6 and approve bounded assignment feasibility`, contains only README, implementation-plan, open-questions, M6 closeout and M7 plan. Work then moved to **`feature/milestone-7-pr1a-assignment-contracts`**. At prerequisite handoff, all subsequent changes were uncommitted and unstaged, with no push or PR. The later finalization authorization permits one prerequisite commit, normal push and review PR, preserving that planning commit. No deployment, production enablement or PR 1b implementation is included.

M6 `PublicationProtocol.ISSUANCE_ENABLED=false` remains unchanged. Create-only synthetic version fixtures suffice for this prerequisite; there is no new Publish path, product Assign endpoint, native command issuance or Assign UI. Existing twelve command forms/hashes, ledger dispatch, Rules/indexes, dependencies and lockfile remain unchanged. Assignment-bearing membership revocation remains restricted.

The original M6 quarantined dataset and its unknown outcomes remain separate. Its identities, keys, pending partitions and provenance were not reused, reset or reconstructed. New disposable backend projects and separate task-owned native instances supplied this evidence. No native Auth accounts or protected commands were issued. These codec/storage probes do not require an Auth/export preflight for protected execution because they never execute a command. That preflight remains mandatory for a future retained end-to-end run.

## Exact inventory and fingerprints

The [source manifest](evidence/milestone-7-pr1a-source-manifest.sha256) has **17 entries**, final-review SHA-256 `97c7513dd4700a4dc74fb0142a6131fc31bc636d898e04d6ef1087c60585e60f`: the following 16 changed source/probe files plus the unchanged production Swift store dependency. The [verification record](evidence/milestone-7-pr1a-verification.json) records individual hashes, frozen production-contract fingerprints, log/checkpoint hashes, measured values and calculated bounds. It separately retains the original source-manifest fingerprint for the earlier runs. Generated payloads, logs, binaries, native data and credentials are excluded from review files.

| File | Purpose |
|---|---|
| [assignment-feasibility-contract.ts](../../firebase/functions/src/assignment-feasibility-contract.ts) | Isolated pure descriptor, copy, hash, time, decision and client-document validators; not imported by product writers/index |
| [assignment-pr1a-fixture.ts](../../firebase/functions/test/assignment-pr1a-fixture.ts) | Independent synthetic ASCII, escaped and maximum-content fixtures |
| [assignment-pr1a-contract.test.ts](../../firebase/functions/test/assignment-pr1a-contract.test.ts) | Canonical vectors, malformed inputs, actual SDK/protobuf serialization and index-entry enumeration |
| [assignment-pr1a-probe.ts](../../firebase/functions/test/assignment-pr1a-probe.ts) | Test-only transactions, dual-namespace guard, authority, exact copy and discovery experiments |
| [assignment-pr1a-transactions.test.ts](../../firebase/functions/test/assignment-pr1a-transactions.test.ts) | Atomic commit/replay, fence orderings, legacy gap/exclusion, provenance and relationship cases |
| [assignment-pr1a-rules.test.ts](../../firebase/functions/test/assignment-pr1a-rules.test.ts) | Isolated proposed Rules and actual client SDK queries |
| [run-assignment-feasibility.mjs](../../firebase/functions/scripts/run-assignment-feasibility.mjs) | Node 22 disposable emulator supervisor; no retained import/export/reset |
| [AssignmentContract.kt](../../shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/assignments/AssignmentContract.kt) | Actual Kotlin serializers, canonical hashes, precise time checks, prospective states/admission |
| [AssignmentClientDocuments.kt](../../shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/assignments/AssignmentClientDocuments.kt) | Typed complete client-safe document validation without source reads |
| [AssignmentContractTest.kt](../../shared/src/commonTest/kotlin/com/delminiusapps/tillfailure/assignments/AssignmentContractTest.kt) | Exact vectors, type/clock rejection, prospective proof states and existing-reader locks |
| [AssignmentFixtureTest.kt](../../shared/src/androidHostTest/kotlin/com/delminiusapps/tillfailure/assignments/AssignmentFixtureTest.kt) | Actual TS-to-Kotlin comparison and checkpointable state export with retained synthetic history |
| [AndroidAtomicFilePersistence.kt](../../shared/src/androidMain/kotlin/com/delminiusapps/tillfailure/persistence/AndroidAtomicFilePersistence.kt) | Private serialization-boundary extraction for the native measurement probe |
| [AndroidAssignmentSizingTest.kt](../../shared/src/androidDeviceTest/kotlin/com/delminiusapps/tillfailure/assignments/AndroidAssignmentSizingTest.kt) | Production Android atomic encryption, decryption equality and oversize preservation |
| [AssignmentStorageMain.swift](../../iosApp/feasibility/AssignmentStorageMain.swift) | Standalone simulator probe against the unchanged production Swift encrypted store |
| [run-assignment-storage.py](../../iosApp/feasibility/run-assignment-storage.py) | Separate simulator executable packaging, input verification and cipher checkpoints |
| [milestone-7-pr1a-bounds.mjs](milestone-7-pr1a-bounds.mjs) | Executable deterministic accepted-state bounds |

The unchanged Swift dependency is [RecoveryPersistenceBridge.swift](../../iosApp/iosApp/RecoveryPersistenceBridge.swift). The Android change extracts a private `writePlaintextLocked` helper with deferred serialization; the public typed entry, serialization/error order, key, AAD, AES/container encoding, fsync/rename and failure handling remain the original path. The device test invokes that helper by reflection to measure future JSON. There is no public raw-write API or relaxed native parser.

The other four working-tree files are this report, the source manifest, the verification JSON and a local handoff paragraph in M7 plan; the bounds script itself is included in the source table. Total prerequisite inventory is **20 files**. Planning files already committed are not counted as new prerequisite changes except the plan's later handoff paragraph.

## Frozen contracts

The exact descriptor is:

```text
action = assign-program-version
workspaceId, idempotencyKey
assignment = {
  assignmentId, plannedWorkoutId, clientUid, templateId,
  versionId, versionNumber, sourceContentHash,
  copyPolicyVersion: 1, snapshotHash,
  authority: {
    trainerLifecycleRevision, trainerMembershipRevision,
    clientLifecycleRevision, clientMembershipRevision,
    workspaceMembershipRevision, clientOnboardingRevision
  },
  preparedAtMillis, scheduledInstantMillis, accessExpiresAtMillis
}
```

New kind/hash version are `program-assignment-create` / `program-assignment-v1`. Caller and key use the existing command-ID digest. The request hash uses the existing canonical `stableHash` over the descriptor plus `callerUid` and `policyVersion:1`; no equivalent legacy hash is inferred. The fixture uses the existing reserved `or1-` generation/key form. Business IDs are UUIDv4; version IDs are `v` plus ten decimal digits, version 1–2147483645. Caller/client/workspace retain the bounded path/UID domains. Caller cannot equal client. All unknown fields are rejected.

Authority revisions are positive integers through 2147483646, with workspace revision at most 2147483645 before its increment. The writer requires active global trainer/client records, active workspace, exact active trainer/client memberships with eligibility, profile/directory identity, current client-to-trainer relationship and completed onboarding. The version's immutable author must be the caller. Ownership alone provides no override. All six confirmed revisions must still match before recording a new business decision. Exact replay checks current writer authority but does not require the old workspace tuple to equal the revision advanced by its own success. Own-resolution requires eligible global identity and verified configuration, and can settle after workspace authority loss without returning content.

These experiments receive an explicit trusted test UID. They do **not** implement the fresh Auth/token HTTP boundary, disabled-user check or residual Auth-to-Firestore race handling. Those remain endpoint prerequisites; database checks alone do not establish caller authentication.

Times are UTC **integral milliseconds**, inclusive range 0–253402300799999 (Firestore year 9999). Fractions, rounded/coerced values and overflow are rejected. `prepared < scheduled <= prepared + 86400000`; `expiry = scheduled + 86400000` exactly. Execution must be at least preparation and strictly before schedule. Equality at schedule produces a schedule conflict; a regressed/invalid clock is nonterminal unavailable. Timestamp conversion uses exact `Timestamp.fromMillis`; hashing/DTOs retain millisecond integers. This freezes precision and boundary arithmetic without adding local-zone/DST policy.

The selected exact `workspace/template/version ID + version number + source content hash` is read and validated inside the transaction. Complete production version schema, original author/workspace/path, canonical content/draft hashes and exactly one workout with 1–12 items are required. Copies retain exact title/description, ordered workout/items, names/instructions, taxonomy and typed prescriptions with provenance. There is no mutable draft/latest lookup or later exercise-source lookup. The copy digest is canonical `program-assignment-copy-v1` with copy-policy version 1 and the complete allowlisted content. V1 assignment after later draft/latest/v2 creation passed, and v1 remained unchanged.

The minimal normal decision has exactly `status, workspaceId, clientUid, assignmentId, assignmentRevision, plannedWorkoutId, snapshotHash`. Statuses are `assignment-created`, `assignment-schedule-conflict`, `assignment-source-conflict`, `assignment-copy-conflict`, `assignment-id-conflict`. Success has revision 1 and exact plan/hash; conflicts have revision 0 and null plan/hash. `replayed` is transport metadata. Current eligible exact business authority and a valid readable source are prerequisites even for a negative business receipt. Missing/malformed/foreign source, authority loss, corrupt occupancy and missing provenance are nonterminal; denial must not fabricate a conflict receipt. The own-resolver's `applied`, `not-applied-conflict` and `not-applied-fenced` outcomes remain content-free.

Client documents comprise a safe header; `snapshots/content`; one workout and its 1–12 exercises; one planned workout; and a relative-path download inventory. The trainer index is separate. Client validators reconstruct and hash the whole copy solely from these account-owned documents, checking identities, exact fields, order, source/copy hashes, prescription types, plan, inventory and missing/extra/private fields. They never receive or fetch a trainer source DTO. The inventory confers no offline/download permission.

### Canonical vectors

TS and Kotlin serializers agreed on all seven exported fixtures, including escaped text and maximum-length boundaries. Representative exact digests are:

| Value | ASCII fixture | Escaped fixture |
|---|---|---|
| Command ID | `3943402b94dbed13b0fe51354b9b81fb7f6ba11433b421189b46cec565fcd705` | same caller/key |
| Request hash | `5252e6120cfb19f03f30c955c5e510bdec5e9f30f51db126ef8f11b93d0d5949` | `2c251bd11e3383315ac1343f4552ed46eca7ab9ca921a405955f37666c9bcf57` |
| Source content hash | `4f9654a02f80d757330183becea5820127d1f346d1ca03f8d2f8424f652afcbe` | `657021fe8d7f4b0eebfdb65903be78c727003a63ecd7bd0fb77b8271cab937ce` |
| Copy hash | `7a829022c9cb65899501c5e2da3f1286e97d65bcccd26395636290734cf84b78` | `f2d67f348f953bdf48a4170c3b42e3da30299a1892984c19f0ff1972630da42c` |

## Namespace and transaction findings

Source/import/reference inspection found two legacy `assignmentCommands` writers, `publishAssignment` and `closeAssignment` in `assigned-program.ts`; their consumers are tests, not callable exports. Their lifecycle/replacement capabilities are not authorized product scope. Both remain unchanged here. Neither checks `lifecycleCommands`: isolated calls proved **both can execute a covered product key after a lifecycle fence**. A new writer checking two namespaces alone cannot close that gap.

The tested later correction is to reject **all `or1-` keys at both legacy entry points**, before references or transactions, while preserving historical non-product forms/hashes and receipts. Test-only guarded wrappers exercise this exact exclusion before calling the real functions. Legacy-first occupancy makes the new resolver unavailable without creating a fence; a fence before or after a guarded legacy attempt prevents covered legacy execution. New writer and own-resolver must transactionally inspect **both** `lifecycleCommands/{existing caller/key digest}` and `assignmentCommands/{same digest}`. Any legacy occupancy, dual occupancy, wrong kind/hash, malformed receipt/fence or missing coverage/provenance fails closed without overwrite. There is no migration, equivalent-hash inference or synthetic history.

New absence fencing also requires explicit proven dual-namespace coverage. The disposable configuration's `assignmentDualNamespaceVerified:true` is experiment setup, **not a real-history attestation**. Before enabling product execution/absence, engineering must install the legacy barrier, account for/drain relevant in-flight writers, audit retained history and establish coverage under existing provenance. Unknown old history remains locked; a new generation or empty namespace must not be used to bypass it.

The test-only writer atomically creates all safe copies, plan, inventory, trainer index and receipt and advances the shared workspace membership revision. At 12 items this is **20 writes**; no copy is published piecemeal. Exact retries create no second assignment. Writer-first/resolver-first held transactions, cross-kind/hash collisions, occupied/dual/corrupt records, provenance denial and current-relationship discovery were executed. This establishes isolated transaction feasibility. A complete product race matrix for two fresh Assign commands, revocation/reassignment, source tampering during transaction retry and external Auth changes remains for PR 1b; the tests here do not claim that matrix.

## Discovery and client-read findings

Only an isolated test Rules string replaces the trainer index block with:

```text
match /workspaces/{wid}/assignedPrograms/{aid} {
  allow read, write: if false;
}
```

This is the exact later product narrowing required. Product `firebase/firestore.rules` is unchanged, so its known direct trainer-index exposure still exists. Denial passed actual get/list attempts for trainer and both clients. Client source version/descendant reads and mobile assignment writes remained denied.

Proposed mediated discovery first validates current exact trainer/client/workspace authority, then queries that known client's headers for the caller's `trainerId`, `accessStatus == active` and `accessExpiresAt > now`, ordered by expiry/document ID, with limit 6 and at most 5 returned identities. Withdrawal/reassignment/malformed membership denied, and expiry equality returned no active row. No workspace-wide trainer index or former-trainer history grant is needed. This is a test-only DB adapter, not a complete discovery endpoint; cursor binding, complete response validation, rate admission and current-authority detail reads must be integrated later.

Actual client SDK queries/readback passed for own headers, workouts, exercises, one scheduled plan and inventory, followed by complete copied-content validation without source access. Cross-client/workspace, signed-out/unverified, malformed/terminal/expired parent content and arbitrary source inventory paths were denied/rejected. Existing safe terminal-header visibility remains, without a new history grant.

The later index patch must support the header collection query on `trainerId ASC, accessStatus ASC, accessExpiresAt ASC, __name__ ASC`, and validate actual client workout/item/plan query indexes. Firestore emulator execution does not prove production composite-index deployment readiness. No product index file changed. Assignment-bearing membership revocation remains blocked until separately approved lifecycle policy handles its artifacts/history.

## Actual measurements and deterministic bounds

Byte values below are UTF-8 unless explicitly UTF-16 units. Measurements use installed pinned serializers and real stores, not random ciphertext samples as a universal argument. The deterministic [bounds script](milestone-7-pr1a-bounds.mjs) and per-record admission cover accepted states; larger retained histories are refused before issuance with original encrypted bytes preserved.

| Quantity | Actual maximum exercised | Admission/bound |
|---|---:|---:|
| Single-workout source version | 136741 | 163840 |
| Assignment callable request model | 2533 | 32768 |
| Resolver descriptor | 2559 UTF-16 units | 8192 units / 24576 bytes |
| Largest materialized document | 13205 | Item 24576; header/workout/plan/index 4096; snapshot/inventory 8192 |
| SDK modeled CommitRequest, including wider UTF-8 paths | 177638 / 20 writes | 461312 calculated; 524288 reserved ceiling |
| Actual intercepted emulator transaction CommitRequest | 157767 / 20 writes | Same reserved ceiling |
| Kotlin serialized pending command | 3468 | 16384 |
| Prospective envelope plaintext | 36500 | New-kind admission 36500 |
| Android admitted encrypted container | 48796 | Deterministic 48796 at ceiling; existing encrypted ceiling 98304 |
| Swift admitted encrypted container | 49625 | Deterministic wrapper upper bound 97517 at ceiling; existing ceiling 98304 |

All assignment responses retain the **65536-byte generic packet ceiling**, mutation requests 32768, and resolver limits above. Publication's **192 KiB exception is not reused**. No generic transport/recovery cap, custom admission or encoding changed. Pending contains the bound descriptor, never the copied workout payload; content is read through bounded child documents. The full-copy source-read allowance is independent of both callable and per-child response ceilings. No valid useful 1–12-item fixture failed these new bounds, so no scope reduction or allowance increase was needed. Arbitrarily large existing metadata/history is not promised to fit.

The calculation reserves 348160 bytes of logical write JSON, plus 113152 protobuf/name/transaction reserve, giving **461312 bytes**. The fixed allowlisted layout has at most 2000 modeled nodes; 32 bytes/node covers protobuf tags/wrappers/varints and worst-width integers, with 2048 bytes per document name and 8192 additional metadata reserve. Bounded path UTF-16/UTF-8 width and indexed-value truncation are included; wider BMP path fixtures exercise the actual serializer. Automatic index entry enumeration stays at most 64 entries/document. A conservative 4096 bytes/entry across 22 changed document images, including old workspace and separate admission records, gives **5767168 index bytes**; combined reserve **6228480**, below 10 MiB. This is a deterministic accepted-layout reserve, not captured production index internals or a promise for arbitrary future indexes. Product index additions must be reviewed against it.

Accepted read JSON is bounded at **348160 bytes** by authority/config/receipt/target/source guards; malformed network input itself is not thereby a transport DoS bound. The business transaction has at most 16 read observations and 20 writes; separate admission adds one read/write. Three attempts give at most **51 read observations / 63 attempted writes**, with a 5-second elapsed check and proposed 120 requests/minute. These elapsed checks do not cancel an already-running SDK RPC. The finite rate gate/admission counter is specified, not installed on a product endpoint. Discovery is finite at 9 authority documents plus at most 6 headers per page; client copy validation is 6–17 account-owned documents. Later endpoints must apply byte checks to every returned page/detail and enforce cursor/request limits.

### Retained history and native storage

Actual Kotlin serialization exports **38 legal prospective states per selected history**: pending before/after resolution start, all five normal outcomes with replay/start combinations, three resolver outcomes, and content-free retired/departure markers for their legal normal/resolver sources. Existing production pending/reply/envelope serializers are used for original fields and retained proofs, with an isolated proposed assignment field. Current twelve-kind readers still reject the prospective assignment format; these JSON states do not enable ledger dispatch or recovery.

Histories include empty, full maximum-note proof and retired-note marker; on the widest boundary they additionally retain actual serialized synthetic valid proofs and retired markers for **all twelve existing variants**. Full original retained objects remain in prospective pending/proof states. These are independently generated valid production-serializer objects, not fabricated server history or reconstructed old user receipts. A metadata-filled accepted record reaches exactly 36500 bytes; seven overlarge prior records fail pure admission. Admission checks the actual prior record, empty single pending slot/critical-work conditions, consumed key and every prospective state before a future issuance call.

The final input set contains **1748 prospective state files plus 7 oversize-prior files (1755 total)**, 18916094 input bytes. A superseded pre-tightening fixture batch is archived separately and was not used for final native evidence. Android and Swift each exercised all 1748 admitted states through their production encrypted write paths, recovered byte-identical plaintext, and proved an actual overlarge retained record remained **ciphertext byte-identical** after refusal. This covers codec/storage admission; it is **not an assignment command restart/freeze/proof/retirement/A–B matrix**. No new command can yet reach either native writer through production dispatch.

Android used a new task-owned API 34 ARM64 AVD `TillFailure_M7_PR1a_Sizing`, port 5572, and the shared test package. Each state had a separate fresh synthetic partition under its task run. The production AES-GCM/atomic-file implementation was used, with its key/AAD to verify exact decryption. One instrumentation test covers the bulk state set; it is not 1748 independent UI tests. Swift used a separate iPhone 17 / iOS 26.2 simulator, ID `5650FD71-C90A-4003-926F-961BC3C071E5`, and standalone bundle `com.delminiusapps.tillfailure.m7pr1asizing`, compiled with the unchanged production Swift store. It is outside the product Xcode target and initializes no Firebase. Both retain their new key and partitions.

Input copies were SHA-256 verified before use; each native checkpoint contains **1749 encrypted files**, including the refusal fixture. Android admitted maximum 48796 excludes the larger preexisting refusal record. Before graceful shutdown, both sets of 1749 cipher files were reverified byte-for-byte; `native-shutdown-verification.json` records all bytes verified. Both task-owned devices are stopped, with disks/keys retained. Native checkpoint/log/archive hashes are in the checked-in verification JSON; raw artifacts remain ignored under `.firebase/m7-pr1a`. No old emulator/native dataset was imported into reset suites.

## Verification attribution and failed attempts

Backend checks used isolated official **Node 22.23.4**, verified archive SHA-256 `c9e1dbc748b33ed2d14f42445191ecaf14c56259c7a9a26d0042c49ec2b66428`. The unchanged lockfile SHA-256 is `0636bb6977fb17302dd896a4c9da281dfa73f478456b60b57a7ef24d9d4f6e04`. Installed versions include TypeScript 5.9.3, Admin 14.4.0, Functions 7.4.0, Firestore 9.2.0, Firebase CLI 15.30.2, Rules testing 5.0.2 and JS Firebase 12.19.0. No install/upgrade was used to obtain a pass. JDK 21 and the existing Gradle wrapper supplied native/shared builds.

Each row below was a separate implementation-phase run and is now **retained evidence at final review**. Totals must not be summed or substituted for historical evidence. The fresh final-review checks are recorded separately below.

| Run | Result and attribution |
|---|---|
| Initial isolated prerequisite backend | 15/15 passed |
| First full backend | **303 passed / 1 failed of 304**; existing client-revocation concurrent-distinct-keys case hit `unavailable` at its 5-second transaction re-entry deadline after 6117 ms |
| Focused revocation + publication + final prerequisite | 54/54 passed; no existing test, deadline or contract was changed |
| Final complete backend | **305/305 passed**, zero failures, 185642 ms; includes final wider-path and guarded legacy-ordering cases |
| Final Android shared host tests | **263 passed**, zero errors/failures/skips |
| Final iOS simulator shared tests | **261 passed**, zero errors/failures/skips |
| Android production-store instrumentation | **1 test passed**, bulk 1748 states plus byte-identical refusal; 2421.073 seconds |
| Swift standalone production-store probe | 1748 states and byte-identical refusal passed |
| Build checks | Debug shared iOS framework, Android device-test APK and Android Release build passed; final Android helper refactor was rebuilt/tested |

The recorded older **M6 finalization backend exit-1 remains unexplained**: no identifiable original diagnostic log was located. Available old native logs do not diagnose that backend attempt. The fresh revocation deadline failure above has its own log and attribution; it is not asserted to explain the old failure. The later green complete run does not erase either record.

Other observed setup failures and remedies:

- Official Node archive extraction initially used an unsupported Python 3.9 tar filter argument; the SHA-verified archive was extracted with tar instead. System Node 25 was not used for backend checks.
- An SDK protobuf named import was unavailable; the probe used the installed SDK's exported `protos` object. An Android test `Files.writeString` compile failure was corrected to `Files.write(bytes)` for the target API. No dependency changed.
- The first standalone Swift link with iOS 17 target lacked platform symbols (`_MTLDeviceErrorDomain`, `fdclosedir`, `fdscandir`, `scandirat`). Matching the existing iOS 18.2 target and dead stripping remedied linkage.
- A later Swift launch reached production-store `LOCKED` after missing simulator entitlement packaging. A manually signed entitlement attempt was launch-denied (`FBSOpenApplicationServiceErrorDomain` code 1; underlying POSIX 163). The final probe matched the project's existing simulated XML + DER entitlement sections, `FAKETEAMID` application identifier and ad-hoc signing pattern. Fresh key lookup returned `-25300` (not found), then creation and all storage cases passed. No observed M7 `-34018` error is claimed. Existing data/key was not replaced. Resume checked occupied fixture bytes rather than clearing them.
- Two automatic review waits timed out on an emulator launch/final Android build; authorized retries succeeded. No automatic safety rejection remained. Inherited Compose/Kotlin warnings and the framework ICU deployment-version warning remain build qualifications, not new acceptance evidence.
- A final read-only simulator inventory audit failed inside the filesystem sandbox (`CoreSimulatorService` connection invalid / POSIX 61). The same read outside that sandbox succeeded and confirmed the task simulator was shut down; no device state was changed to obtain that result.

Ignored evidence is under `.firebase/m7-pr1a`: separate prerequisite/full/focused/final backend logs, serialization JSON, shared/native build logs, TS exports, fixture/checkpoint manifests, Android encrypted archive/logcat and Swift failed/successful launch diagnostics. Checked-in verification JSON fingerprints the final evidence without publishing payloads or machine paths.

### Final review and fresh verification

The finalization review began on the expected branch at planning HEAD `21633e0656d97e3040070f8b3a8002682953a2f8`, with an empty index and exactly the 20 prerequisite files above. Remote main was still `de8d7256a587d8548b3512ac1ffca25fa7fa9c19`; no remote task branch or existing PR was found. The approved decisions, complete report/evidence/manifest and all 20 files were inspected. Finalization authorizes the separate `test(assignments): prove bounded assignment contract feasibility` commit and normal publication for review; it does not authorize PR 1b or a merge.

Two bounded validation corrections were necessary:

- A one-element array containing a valid key was observed to pass the TypeScript regex through implicit string coercion. The descriptor now requires a string before matching. TS and Kotlin regression assertions reject array keys.
- Kotlin's isolated source/copy validation relied on the broader recovery UID contract and its client-document validator omitted the client's path check. These now enforce the same path-safe author/client/trainer/workspace domains as TypeScript, plus the explicit existing 160 KiB source ceiling. Regression fixtures reject invalid path identities, including reserved IDs and malformed UTF-16. Valid descriptor/copy/hash/envelope serialization is unchanged. No existing product validator or command form was altered.

After those corrections, the isolated Node **22.23.4** prerequisite suite passed **16/16**. Fresh Android-host tests passed **263/263** and fresh iOS shared tests **261/261**, all with zero errors/failures/skips. The bounds script and probe Python syntax check passed. All **1755 freshly regenerated Kotlin fixture files** matched the retained native input checkpoint **byte-for-byte**. This validates continued applicability of the unchanged accepted-state sizing; the prior 1748-state native encryption/refusal runs were **not rerun**. The complete 305/305 backend run, native binaries/builds and Android Release artifact remain accurately attributed to their earlier source snapshot, before the stricter input guards. No iOS product Release build is claimed.

Fresh setup first hit sandbox `listen EPERM` on loopback port 46799, and the Gradle wrapper cache lock was initially `Operation not permitted`. Authorized retries outside the filesystem sandbox passed. These are final-review setup attempts, separate from both the observed revocation deadline failure and the unexplained older M6 exit-1. Fresh logs and the retained pre-correction source manifest are ignored local reproduction artifacts under `.firebase/m7-pr1a/final-review`; their relevant hashes and this attribution are in the verification JSON.

Final review rechecked sanitized inventory, source and frozen-contract hashes, local links, whitespace/conflict markers and unchanged Release hook/target boundaries. Product exports, Rules/indexes, native ledger dispatch, all twelve existing forms/hashes and disabled Publish remain unchanged. The exact PR 1b blockers below remain open. Final commit and PR identities are recorded by Git/GitHub rather than embedding a self-referential commit hash in this document.

## Release isolation and inherited findings

Android Release APK SHA-256 is `4a91bcd0adb9261a72c4b471ecd114b1020e25fc73512436a45ae68669ce1476`. Its DEX audit found none of `AndroidAssignmentSizingTest`, `M7_ANDROID_NATIVE`, `TF_M7_PR1A` or `AssignmentStorageMain`. Pure contract classes are ordinary shared code and may be present; this is not a claim that every assignment string is absent. Existing Debug-only hook boundaries were not widened. Swift sizing sources are outside the product target; `iOSApp.swift` and Xcode project are unchanged. The standalone simulator signature is no physical-device or distribution evidence. No product iOS application Release build is claimed for this prerequisite.

Credential, generated-output, absolute machine-path, Release-hook, link and whitespace audits were performed on the review files. The simulated signing identifier is a fixture, not a credential. Product Rules/index/package/lock, legacy writers, exported callables, existing command identity/resolution, native dispatch and Publish flag fingerprints remain unchanged.

The [M6 closeout](milestone-6-closeout.md) remains authoritative for retained M6 evidence: independent 14 native cases / 31 verified checkpoints; first quarantined dataset unknown; temporary enabled Debug iOS interactions versus committed disabled behavior; missing Android interaction/accessibility/OS/physical evidence. None was rerun or aggregated into this report's totals. The inherited custom-admission finding also stays separate: a valid accepted 51966-byte plaintext had conservative Swift bound 138757 above 98304, while its sampled encrypted output 70569 fit. That is an unresolved universal-bound finding, not an observed overflow, and existing custom admission/encoding was not altered.

## Remaining gates and ordered handoff

PR 1a demonstrates useful bounded contracts, exact serialization and isolated namespace/Rules/storage feasibility. **PR 1b still requires Josip's separate authorization.** No additional scheduling, history, access, cancellation/replacement, retention duration, cleanup, real-data, offline or production policy is inferred.

| Gate | Classification / responsible work |
|---|---|
| Both legacy prefix guards, new writer/resolver dual-namespace checks and genuine coverage/provenance | **A** before first issuance. Engineering/security must install and verify barriers and all entry points, preserve existing receipts, fail closed on unknown history and prove deployment/in-flight ordering. |
| Trainer-index Rules denial and actual query indexes | **A**. Engineering/security must apply the exact reviewed narrowing, complete mediated current-authority discovery/detail and bounded cursors, and verify production-shaped index configuration. Revocation restriction remains. |
| Fresh Auth boundary, source preparation/confirmation, rate admission, strict responses and races | **A**. Integrate current identity/eligibility, exact descriptor/copy/time/IDs, all-or-none writer and minimal receipt; prove authority/source/clock changes at transaction barriers, two writers, occupied IDs, resolver races and no duplicate effects. |
| New-kind protected dispatch and ADR-002-compatible settlement | **A** for issuance. Preserve twelve existing variants/hashes, UID ownership, freeze/generation fences, persist-before-send, full proof before retirement and clean departure. Native codec probes here do not establish integration. |
| Actual Android/Swift restart and two-role interaction | **A** for enabled Assign UI/acceptance; **C** for release. First run each native lost-ack/resolver/conflict/proof-write/retirement/cold-compaction/freeze/A–B matrix with verified effects/checkpoints; then actual version choice, preview/Assign/Cancel/Back, conflicts, client browse/refresh, scrolling/large text, accessibility and observed OS events. No such new assignment interaction was exercised here. |
| Disabled M6 Publish/incomplete M6 interaction; quarantined old dataset | **B** for independent verified synthetic assignment fixtures; **C** for affected acceptance/release. Unknown old outcomes remain unknown. |
| Inherited custom-admission sizing | **B** with this independently bounded new-kind admission; **C** for affected release. Requires separate authorized correction, not cap/encoder drift here. |
| Physical security, retention/privacy, production operations and real content | **B** for synthetic emulator prerequisite; **C** for real-data/production/release. No distribution, OS key-loss/power-loss/backup/shared-device or physical evidence supplied. |

After authorization, order the work: (1) install namespace/Rules/index/auth/admission prerequisites while issuance stays disabled; (2) integrate protected assignment preparation/write/own-resolution and both native adapters with complete race/restart evidence; (3) enable only the bounded synthetic Assign flow after those gates pass and exercise both trainer/client journeys; (4) return for acceptance review with interaction/physical/inherited gaps explicitly open. Do not remove assignment-bearing revocation restrictions or expose legacy replacement/close operations as a shortcut.

### Reproduction boundaries

Use an isolated Node 22 executable (`NODE22_BIN` below), the existing lockfile/install and free task ports. Reset suites are disposable and must never import retained native datasets:

```sh
"$NODE22_BIN" firebase/functions/scripts/run-assignment-feasibility.mjs
"$NODE22_BIN" firebase/functions/scripts/run-assignment-feasibility.mjs --focused-regression
"$NODE22_BIN" firebase/functions/scripts/run-assignment-feasibility.mjs --regression
"$NODE22_BIN" docs/milestones/milestone-7-pr1a-bounds.mjs
./gradlew :shared:testAndroidHostTest :shared:iosSimulatorArm64Test :shared:linkDebugFrameworkIosSimulatorArm64 :shared:assembleAndroidDeviceTest :androidApp:assembleRelease --console=plain
```

The runner checks Auth 46799, Firestore 46780, Functions 5001, Storage 46719, Hub 46700 and Logging 46720 are free before starting one supervisor. Rules experiments use their separate test project. TS evidence export precedes Kotlin host fixture export. Native tests require a **new explicitly task-owned instance**, verified input SHA checkpoint and separate fixture copies. Android runs `com.delminiusapps.tillfailure.assignments.AndroidAssignmentSizingTest` from the shared instrumentation APK after pushing those verified files to its task fixture directory. Swift uses `python3 iosApp/feasibility/run-assignment-storage.py --simulator <TASK_UDID> --framework <FRESH_SHARED_FRAMEWORK>`; `--resume` accepts only identical occupied inputs. Capture and reverify encrypted checkpoints before shutdown. Do not rerun either tool against old users or reuse unknown keys/history to recreate evidence.
