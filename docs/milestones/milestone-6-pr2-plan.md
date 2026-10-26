# Milestone 6 PR 2: custom-exercise creation and correction

Status: **C1–C6 approved; C7 implementation authorized for the exact synthetic emulator slice, 2026-10-26.**
Review date: **2026-10-26**, Europe/Sarajevo.
Baseline: `49b97f53772a2c6afa42d7f5ed195e0b4df6b554` (`Milestone 6 PR 1: online system exercise browsing (#16)`).
Branch: `docs/milestone-6-pr2-custom-exercises-plan`.

## Baseline, authority and evidence

Read the repository README and relevant architecture/product/security/recovery/navigation documents; no applicable `AGENTS.md` was found in the repository or its ancestor directories. Initial branch was `main`, with empty working-tree status and index. Recent main commits were PR #16 (`49b97f5`), PR #15 (`bd3cc2b`), PR #14 (`51bf04d`), PR #13 (`b8bfd06`), PR #12 (`ce3bde2`) and PR #11 (`160c1a3`). The connected GitHub metadata confirms [PR #16](https://github.com/krajinov/TillFailure/pull/16) merged into `main` on 2026-10-26, with the full baseline SHA above. `git pull --ff-only origin main` reported already up to date; an ancestry check succeeded before creating this branch. The initial sandbox attempt could not write `.git/FETCH_HEAD`; the authorized elevated retry succeeded. No unrelated work, reset, amend, rebase or force-push was involved.

This task permits inspection and documentation only. All C1–C7 decisions below are **recommendations awaiting approval**, not implementation permission. No code, Rules, indexes, dependencies, design, runtime fixtures, commit, push, PR, deployment or production enablement belongs to this task. No application builds or runtime tests were run for this plan.

| Inspected contract | Existing authority and limits |
|---|---|
| [M6 plan](milestone-6-plan.md#decision-register), [PR 1 report](milestone-6-pr1-report.md#implemented-contract), [product scope](../product-scope.md) | D3 system browsing is implemented and merged; D5/D8 custom policy/recovery remain unapproved. Multiple trainers remain post-MVP, but adversarial other-trainer tests are still required. |
| [Schema](../firestore-schema.md#exercises-and-programming), [security matrix](../firestore-security.md), [actual Rules](../../firebase/firestore.rules), [indexes](../../firebase/firestore.indexes.json) | The broad schema's member reads, trainer archive, normalized-name indexes, media and queued offline edits are future proposals. Custom paths currently fall through to deny-all; templates explicitly deny all. None grants access. This plan recommends a narrower mediator boundary; reconcile those proposal rows in the later approved implementation, without treating them as permission today. |
| [System validator](../../shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/exercises/SystemExercises.kt), [MVI reader](../../shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/exercises/ExercisesViewModel.kt), [role navigation](../../shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/identity/RoleShell.kt), [navigation contracts](../navigation.md) | Reuse strict Unicode/shape validation, bounded paging, fresh details, destination/visit/generation fences and existing components. PR 1 fields/taxonomy are fixture constraints, not approved custom fields. Its global catalog entitlement is distinct from selected-workspace trainer access. |
| [Android adapter](../../shared/src/androidMain/kotlin/com/delminiusapps/tillfailure/firebase/AndroidFirebaseSpikeClient.kt), [Kotlin Apple bridge](../../shared/src/iosMain/kotlin/com/delminiusapps/tillfailure/firebase/IosFirebaseBridge.kt), [Swift implementation](../../iosApp/iosApp/FirebaseNativeBridge.swift), [ADR-001](../firebase-integration-adr.md) | Official SDK ownership and narrow typed adapters; Android protected transport uses the callable HTTP protocol with fresh official Auth, Swift uses the official Functions SDK. SDK snapshots/arbitrary endpoints must not enter feature APIs. |
| [Ledger](../../shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/invitations/InvitationClient.kt), [shared resolver/proofs](../../shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/invitations/CommandResolution.kt), [server resolver](../../firebase/functions/src/command-resolution.ts), [coverage](../../firebase/functions/src/command-identity.ts) | Eight protected variants, one encrypted pending slot, exact original retries, whole-key fences and durable proof before retirement. Resolver wrapper is bounded to 8,192 UTF-16 code units. No custom adapters exist. |
| [Own-command design](milestone-5-command-resolution-design.md), [ADR-002](../trainer-notes-settlement-adr.md), [note mediator](../../firebase/functions/src/trainer-notes.ts), [envelope](../../shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/persistence/PersistencePrototype.kt), [clean departure](../../shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/identity/CleanDeparture.kt) | Own minimal resolution may survive workspace authority loss. Note-only approval compacts retired proofs; seven older variants retain their legacy proof forms. The envelope enforces one pending command but declares no universal byte ceiling. New finite admission budgets below are proposed, not preexisting guarantees. |

Pencil's read-only `get_app_state` failed after its three transport retries to `antigravity_ide`; no fresh frame read/render succeeded and nothing was edited. The local approved `design/tillfailure.pen` exists. Retain the [design inventory](../design-inventory.md#section-to-product-map) references: exercise family `LDmjm`, `Easmv`, `PdFjC`, `GoLR8`, `qRujb`, `mr1jb`; foundations/components `V95tp`, `VJuJK`; trainer system states `BJxtv`, `JAwpk`, `Zu1sI`. These are historical references, not fresh visual evidence. Reinspect/render before claiming fidelity. Designed search, media, archive and builder controls confer no product or security approval.

## Decisions for approval

| Decision / owner | Recommended smallest coherent policy |
|---|---|
| C1 — Ownership and access; product/security (D5) | Immutable author UID in one immutable workspace. Only that author, while currently an eligible trainer there, can create/list/read/correct. Workspace ownership alone grants nothing. No other trainer, client or cross-workspace access, including existence lookup. |
| C2 — Withdrawal and restoration; product/security (D5) | Retain submitted synthetic definitions under original UID/workspace after withdrawal. Deny access while authority is absent. If the same UID is independently restored as an eligible trainer in the same workspace, permit fresh reads/corrections of those definitions. No automatic restoration or transfer operation. Bind each new command to current authority revisions to stop delayed pre-withdrawal writes; do not bind the definition permanently to those revisions. |
| C3 — Fields and taxonomy; product/content (D5) | Only a name, plain-text instructions and small fixed muscle/equipment selections; exact bounds below. Original synthetic, non-personal emulator text only. No media, client data, clinical notes, search metadata or third-party import. |
| C4 — Initial outcome and references; product (D5/D7) | Create, author-scoped browse/detail and explicit correction are sufficient for PR 2. No archive/delete/transfer/restore/duplicate action. Defer reference creation; later publications copy approved text with source identity/revision and remain immutable after correction. |
| C5 — Draft loss and submitted custody; product/privacy (D5/D8) | Unsubmitted forms are memory-only, with dirty Back/Cancel/tab-leave confirmation; background/process/scope loss clears them. Save durably protects the exact command before transport. Unknown outcomes may retain submitted text indefinitely until resolvable; server synthetic text remains after withdrawal without a purge deadline. No secure-erasure promise. |
| C6 — Mutation recovery and settled retention; product/security/engineering (D8) | Two typed create/correct variants in the existing slot/resolver, atomic effect/receipt, explicit conflicts, finite budgets. Extend content-free `compacted-v1` retirement specifically to these two kinds after durable full proof. Preserve the existing note marker and seven legacy forms. This extension needs explicit approval; ADR-002 alone does not authorize it. |
| C7 — Implementation handoff and exclusions; Josip (D9/D10) | Separately authorize only the synthetic emulator slice after C1–C6 and the technical prerequisites. Keep real-data/licensing/privacy, production, offline and inherited acceptance gates open. Approval of this plan must not be inferred from its creation. |

### Exact reader and writer matrix

“Eligible author” means fresh enabled, verified, non-anonymous Auth; matching active schema-1 global account; active schema-1 original workspace; exact active trainer membership, own trainer profile and active membership directory. See the transaction checks below. Author identity is derived from Auth, never chosen in a request.

| Caller / condition | Create | List/detail | Correct | Archive/delete/transfer |
|---|---|---|---|---|
| Eligible author, including a workspace owner who is also that author | Own new definition | Own definitions in this workspace | Own definition at exact expected revision | Unavailable |
| Workspace owner who is not the author | Own new definition only if independently an eligible trainer | Only their own definitions | Only their own definitions | No override |
| Other eligible trainer, even within the same workspace | Own new definition only | Only their own definitions | Only their own definitions | No override |
| Client, former trainer, foreign workspace/account, unauthenticated caller | Denied | Denied for the requested author's content/existence | Denied | Denied |
| Service/operator | Existing trusted maintenance authority is not a product role | No new support/export/read endpoint | No new repair/override endpoint | No new operation |

Every allowed operation in the matrix requires current eligible-trainer authority, including an owner's access to their own definitions. Direct mobile Firestore get/list/write on custom definitions and receipts remains denied for **all** callers. The narrow mediator provides the proposed permissions; Admin SDK access must implement every check explicitly. System exercises remain server-owned and read-only through PR 1's unchanged global entitlement. Selecting the Custom view never grants catalog access, and catalog entitlement never grants custom access.

### Lifecycle and why private-note bindings do not fit

| Event | Proposed effect |
|---|---|
| Author membership revoked or role changed away from trainer | All custom reads/new execution/normal replay denied; presentation clears on known loss. Retain definitions; eligible original global identity can still resolve its exact pending command with no content returned. |
| Workspace suspended/archived | Same denial and custody; another active workspace/catalog contribution gives no access here. |
| Auth disabled/deleted, invalid/revoked token, or global account disabled/missing/malformed | Deny normal operations and own resolution. Pending command remains protected/locked; no repair, forced sign-out, new identity or rekey workaround. |
| Independently approved restoration of same UID and same workspace trainer authority | Fresh reentry may read original definitions and prepare a new correction. Old command authority revisions cannot be refreshed in place. Resolve an unresolved old command first; a proof does not restore authority. |
| Client reassignment | No effect: definitions have no client subject or assignment binding. No client access is gained. |
| Workspace ownership change or replacement trainer | No content transfer. New owner/trainer cannot read/correct the former author's definitions. An original author who still independently satisfies trainer authority retains their own access. Cross-workspace movement creates no access. |

Notes occupy one subject-client path and permanently bind author/subject relationship generations to prevent a replacement trainer from inheriting private notes. A reusable exercise has no client relationship and has its own random stable ID. **Do not copy note subject IDs, four permanent relationship revisions, or one permanently occupied slot per client/author.** Ordinary immutable definition identity and never recycling an ID are useful; making every suspension permanently strand reusable definitions is not recommended. Current authority revisions belong to a submitted operation, not to the definition's lifetime. Restoration behavior is therefore a new C2 approval, not a consequence of note policy.

No reassignment/restore/transfer workflow is implemented here. No hard delete is offered, even for apparently unreferenced definitions. Later archive would block new selection while retaining copied published content; deciding its selection, historical-source visibility and cleanup rules belongs to a later approval. Future templates/versions must bind source type + workspace + ID + revision, read current source authorization/revision within their transaction and copy allowed text. Corrections never rewrite published versions or assigned snapshots. No reference counter, reference scan, assignment writer or template builder is needed now.

## Proposed document and validation contract

One document at `workspaces/{workspaceId}/exercises/{exerciseId}`. Use `exerciseId` as a client-generated lowercase UUID v4 (`xxxxxxxx-xxxx-4xxx-[89ab]xxx-xxxxxxxxxxxx`), generated once for a create form and retained in its submitted command. It is separate from the idempotency key. No ID derived from a name/client; no ID reuse after collision/deletion. Workspace IDs satisfy both existing strict-path and participant validators; UIDs retain the existing path-safe Unicode UID contract, with no normalization. No arbitrary paths or owner UID in requests.

Exact stored field set:

| Field | Contract |
|---|---|
| `schemaVersion`, `policyVersion` | Integer `1`, immutable |
| `workspaceId`, `exerciseId`, `ownerUid` | Exact path tenant/ID and Auth-derived author; immutable |
| `name` | Required trimmed, nonblank valid Unicode; 1–120 scalars, at most 480 UTF-8 bytes; no control characters |
| `instructions` | Required trimmed, nonblank valid Unicode; 1–2,000 scalars and at most 8,000 UTF-8 bytes; LF only among control characters, no CR/tab |
| `muscleGroups` | Required array, 0–3 distinct tokens, ascending ASCII order; only `arms`, `back`, `chest`, `core`, `legs`, `shoulders`, `whole-body` |
| `equipment` | Required array, 0–3 distinct tokens, ascending ASCII order; only `band`, `barbell`, `bodyweight`, `cable`, `dumbbell`, `machine`; `bodyweight` cannot coexist with another token |
| `revision` | Integer 1–2,147,483,646; create is 1, successful correction is expected + 1; refuse increment at ceiling |
| `createdAt`, `updatedAt` | Trusted server Firestore timestamps; equal on create; preserve `createdAt`, set `updatedAt` to the correction's server decision time without clock regression. Validate finite epoch millis 0–8,640,000,000,000,000 and `updatedAt >= createdAt`; equal timestamps are possible at clock resolution. Replay/conflict never rewrites either timestamp. |

Empty taxonomy arrays mean unspecified; labels are selections, not searchable free text or a production taxonomy. No default clinical classification. Reject unknown fields, null substitutes, numeric strings/fractions, duplicate/unsorted tokens, invalid surrogates and unsupported schemas rather than dropping/truncating data. UI can show a trimmed/canonical proposal before Save, but the submitted command is exact and is never silently normalized on retry. Preserve case and Unicode sequence; no locale folding or NFC rewrite. Share TypeScript/Kotlin vectors and test native serialization of non-BMP/combining characters, quotes, backslashes and LF.

Omit `nameNormalized`, `status`, media, client/relationship IDs, hidden/private text, `createdBy`/`updatedBy`, per-definition authority revisions and separate history records. `ownerUid` already identifies the only permitted writer. These are explicit PR 2 reductions from the broad schema conventions, pending C3; no archive state is necessary without archive behavior. Unknown future status/fields fail closed. Stored JSON validation projection (timestamps as epoch millis) must fit 16 KiB; request/protected-envelope budgets are additional constraints, so per-field validity alone is insufficient.

## Fresh authorization, reads and finite work

Propose one fixed emulator callable `customExercisesEmulator` with allowlisted `exercise-prepare`, `exercise-list`, `exercise-get`, `create-custom-exercise`, `correct-custom-exercise` actions. Reads use separate narrow typed repository methods; writes use the existing protected service. Do not route a read through ledger preparation or let it occupy/retire the command slot.

The mediator is justified by atomic definition/decision receipts and exact own-command resolution after authority loss, which the existing ordinary Rules-write prototype has not proved across native restarts. Author-scoped mediated reads keep the default-denied source boundary and consistent authority/data validation without introducing broader direct grants. This is a bounded feature adapter, not a policy to route all future CRUD through Functions.

Every invocation, including replay, checks the named demo/loopback environment, a fresh bearer with revoked-token verification, non-anonymous verified enabled Auth, and Auth/account email binding. In the same Firestore transaction as the query/target/receipt, read five fixed authority documents:

1. `users/{uid}`: schema 1, active, supported positive `lifecycleRevision`, current normalized email binding.
2. `workspaces/{wid}`: schema 1, active, supported positive `membershipRevision`.
3. `workspaces/{wid}/memberships/{uid}`: schema 1, path-bound `workspaceId`/`userId`, active `trainer`, supported positive `revision`, `catalogContributionActive == true`.
4. `workspaces/{wid}/trainerProfiles/{uid}`: schema 1 and exact tenant/user binding.
5. `users/{uid}/membershipRefs/{wid}`: schema 1, active; fixed own path supplies tenant identity.

No workspace-owner bypass or client-profile/assignment reads. Missing/unsupported records deny without repair. Read-only transactions give one consistent authority/data observation; mutation transactions conflict with concurrent account/workspace/membership changes. Auth-service changes cannot be atomic with Firestore: a previously authorized request may finish. Known local loss clears content; neither fresh checks nor polling promise instantaneous remote erasure.

The response carries exact `authority={authorLifecycleRevision,authorMembershipRevision,workspaceMembershipRevision}` from those three records. New form entry calls `exercise-prepare` with exactly `{action,workspaceId}`; it returns exactly `{workspaceId,authority}` and no content. Correction starts with fresh detail. Each submitted command captures that authority object; normal execution/replay requires equality with current revisions. Workspace revision changes for unrelated roster work may invalidate preparation; accept this conservative restriction in the first slice. Do not silently rebind a pending command. Resolver does not require these old revisions to remain current.

| Operation | Exact input and response | Bounded behavior |
|---|---|---|
| List | `{action:"exercise-list",workspaceId,afterId}` where `afterId` is UUID or null. Response `{workspaceId,authority,items,nextId}`; each item exactly `{exerciseId,name,muscleGroups,equipment,revision}`. | Query only this workspace collection, `ownerUid == callerUid`, document ID ascending, `limit(21)`, optional `startAfter(afterId)`; return 20 plus continuation from last displayed ID only when lookahead exists. Validate all 21 full documents/ownership before projecting; malformed lookahead fails the page. No client-selectable author/filter/page size. |
| Detail | `{action:"exercise-get",workspaceId,exerciseId}`. Success `{workspaceId,authority,exercise}` where exercise is the full allowlisted document with timestamps mapped to `createdAtMillis`/`updatedAtMillis`. | One exact point read after authority; missing/foreign/malformed target uses the same fixed unavailable response, without existence or owner disclosure. No raw snapshot. |

Cursor is only a transient position UUID under the current UID/workspace/authority/visit; no server grant or confidential roster data is encoded. A forged/copied cursor may skip rows in the caller's own query but never selects another author. Reset on scope change/Refresh; no anchor-document lookup, HMAC cursor framework, count, name search, local filtering or full scan. No traversal-wide snapshot promise: additions/corrections can race pages; deduplicate IDs and fresh-read details. Page service failure preserves only current authorized rows and the original continuation; known denial clears all. Fresh Back/Refresh must not resurrect a prior scope.

Proposed budgets: list at most 26 document reads per attempt (five authority + 21 documents); detail six; prepare five. Successful full list response at most 64 KiB UTF-8, detail at most 24 KiB, prepare at most 4 KiB. Validation may encounter a malformed Firestore document up to the store's document limit: budget fetched list data conservatively at 26 MiB per attempt, not the smaller projected response cap. At most three transaction attempts and a five-second elapsed-work check, checked again before writes/return; this does not cancel an outstanding RPC. Use the existing 20-second presentation deadline/cancellation fencing. No endless retry or background pagination.

All actions have a separate transaction at `customExerciseRequests/{stableHash(uid)}`: exact `{minute,count}`, at most 120 attempts/caller/minute, including misses and replays; one read/one write per attempt, at most three attempts. Mutation business transaction reads at most eight documents (five authority + target + receipt + coverage config), writes at most two (definition + receipt; conflict only receipt). Including admission, worst-case retried list reads are 81 and mutation reads 27; at most three admission writes plus six mutation writes attempted, while each logical successful effect commits once. No fan-out. Enforce request/response bytes before decoding/exposure where possible; deadlines, throttling and oversize failures never acknowledge a business outcome.

Inspect/prove the exact owner-equality/document-ID query and any required index during authorized implementation. Existing checked-in indexes do not establish custom-query readiness; no name/equipment index is requested. If needed, propose only the demonstrated minimal index then. No index or Rules edit is made by this plan, and no production index deployment is authorized.

## Create, correction and durable outcomes

Proposed command identity and descriptor are exact:

```text
create descriptor = {
  action: "create-custom-exercise", workspaceId, idempotencyKey,
  exerciseWrite: { exerciseId, expectedRevision: 0, authority,
                   name, instructions, muscleGroups, equipment }
}
correct descriptor = {
  action: "correct-custom-exercise", workspaceId, idempotencyKey,
  exerciseWrite: { exerciseId, expectedRevision, authority,
                   name, instructions, muscleGroups, equipment }
}
authority = { authorLifecycleRevision, authorMembershipRevision,
              workspaceMembershipRevision }
resolver request = { protocolVersion: 1, descriptor }
```

Create expected revision is exactly zero; correction accepts 1–2,147,483,645. Authority revisions are integers 1–2,147,483,646. Unknown keys/other variant payloads/null alternatives fail. `callerUid` and policy version are server hash inputs, not client-selectable descriptor fields. New keys use the existing covered `or1-<generation>-<nonce>` format and strict 128-code-unit key contract; no new generation is silently invented and no historical key is reused.

| Action | Protected kind | Request hash version |
|---|---|---|
| `create-custom-exercise` | `custom-exercise-create` | `custom-exercise-v1` |
| `correct-custom-exercise` | `custom-exercise-correct` | `custom-exercise-v1` |

Use existing `commandId(uid,key) = SHA256(canonical({callerUid:uid,idempotencyKey:key}))`. Request hash is `SHA256(canonical({...descriptor,callerUid:uid,policyVersion:1}))`; sorted object keys recursively, preserved array order, exact JSON types/strings and UTF-8 SHA-256, lowercase 64-character hex. Freeze cross-language vectors for the actual backend `stableHash` and shared canonical serializer, including escaping/Unicode; do not change any older hash. The action distinguishes create/correct. No text-only hash, raw key in receipts, mutable server-payload pointer or extra note-specific binding nonce is needed; the original random key is already a hash input. Hashes remain internal metadata, not anonymization guarantees or credentials.

Within one bounded transaction, recheck authority/preparation, read `lifecycleCommands/{commandId(uid,key)}` and the target, and perform all reads before writes:

- Any receipt/fence consumes the whole caller/key, across **all** kinds and payloads. An exact matching valid receipt replays its original minimal decision; never compare it to today's exercise revision to invent a new outcome. Foreign/unknown/corrupt/mismatched records forbid effects. Normal replay still requires fresh authority; own resolution handles authority loss.
- Create requires absent target. Atomically create definition at revision 1 and receipt. If a valid own definition already occupies the ID, record a durable create conflict without changing it. A foreign/malformed target returns fixed unavailable with no content/existence projection or conflict receipt; exact own resolution may later fence the uncommitted command. Never overwrite or silently allocate another ID/key on a retry.
- Correct requires an existing valid own target. At equal revision atomically replace only descriptive fields, advance revision once and write receipt. If actual revision is greater than expected, write only a durable correction-conflict receipt. Missing/malformed/foreign target, actual revision lower than expected, ceiling, authorization failure or service error is nonterminal and produces no manufactured conflict proof.
- Two different keys at the same revision produce one applied correction and one durable conflict. Same-key concurrent retries produce one decision. No last-write-wins, field merge or implicit retry at a newer revision. A delayed request after a resolver fence cannot apply, even with changed action/payload.

Exact schema-1 receipt fields: `{schemaVersion:1,commandKind,callerUid,workspaceId,exerciseId,requestHash,result,committedAt}`. Exact result fields: `{status,workspaceId,exerciseId,exerciseRevision}`. Status is `exercise-created` (create/revision 1), `exercise-corrected` (correct/expected + 1), or `exercise-conflict` (revision > expected, including create collision). `committedAt` is the immutable trusted decision time. Normal response is exactly result plus boolean `replayed`; no text, author profile, authority snapshot or raw key. Receipt validation checks exact shape, path identity, kind/hash, result identity/status/revision and timestamp. No receipt body/history dump.

Extend the existing resolver with only these two descriptor/receipt adapters. Its exact seven-field response stays `{protocolVersion,commandKind,commandId,requestHashVersion,requestHash,outcome,decidedAtMillis}`. Map successful receipts to `applied`, conflict to `not-applied-conflict`, and schema-2 fences to `not-applied-fenced`. No exercise/workspace/text/revision is returned. It uses the existing original global identity checks, coverage/config, request throttle and finite fence budget; no exercise/authority lookup and no role restoration. Absent history/unsupported legacy provenance is unavailable, never proof of cancellation. Existing resolver budget remains up to four fixed documents/two writes per decision attempt, three attempts/five seconds, plus its separate request counter; configured fence maximum stays finite and at most 100 per caller/generation.

Conflict UI reports that this submission did not apply. After durable settlement, require explicit Reload; if the current foreground draft is dirty, confirm replacement and replace only after a successful authorized read. No merge or automatic resubmission. A create collision offers explicit Start new only after settlement, with a freshly generated ID/key and new preparation; it must not turn a collision into a correction. Pending text can remain in the active editor until retirement, but the retired marker is not a recovery editor or payload source. Success performs a fresh authorized detail/list read; a failed refresh does not resubmit. The durable outcome is state, not a snackbar or navigation effect.

## Pending budgets and settled-marker retention

Measure actual serialization before durable preparation. Limits apply jointly, including Unicode, escaping, identifiers, field names, null/default behavior and wrappers:

| Boundary | Proposed maximum / required check |
|---|---|
| Complete `{protocolVersion:1,descriptor}` | Existing 8,192 UTF-16 code units **and** new 24 KiB UTF-8 cap; count the complete wrapper, not instructions alone |
| Every callable request, including `{data:descriptor}` and resolver wrapper | 32 KiB UTF-8 each; enforce both native and server admission |
| One serialized pending custom command | 16 KiB UTF-8, including UID, original key, action, authority, expected revision and `resolutionStarted` |
| Normal mutation reply / resolver reply | 4 KiB UTF-8 each; strict field allowlists remain mandatory |
| Full serialized plaintext UID envelope during custom admission/settlement | 64 KiB, including pending command, duplicated command in pending terminal proof, existing last-settled proof, defaults and other metadata |
| Native encrypted representation for these measured states | 96 KiB including encryption/base64/container overhead; measure Android and Swift actual output |

The current generic envelope has no universal byte cap. These are **new-custom-command admission/proof budgets**, not permission to enlarge storage or reject/discard old records on load. Before admitting a custom command, budget every possible proof/retirement state with the actual prior settled proof (including full legacy payload) and account metadata. If it cannot fit, reject before prepare/transport with bounded validation guidance; leave the original envelope untouched. Once admitted, storage/serialization failure preserves the pending command. Prove maximum-state vectors and fail closed on unknown records; do not truncate text, clear last-settled history to make room, create another store, split commands or expand caps silently. Existing nonempty journal/upload/grant state continues to require reconciliation.

Recommend a specific ADR-002 extension for **only** `custom-exercise-create` and `custom-exercise-correct`:

1. Persist exact original command in the existing encrypted UID pending slot before any transport. Do not persist Auth bearer, UI state or unsubmitted drafts.
2. Validate the normal/resolver outcome against the captured original binding, then durably write the full pending terminal proof in the same envelope. Errors/timeouts, missing receipts and a stale UI success never settle.
3. Only after that write succeeds, atomically retire the pending/proof into the existing single last-settled `compacted-v1` marker. Retain schema 1, UID, command kind/ID, request hash version/hash, source and outcome; resolver source also retains its actual validated decision time. A normal source invents no server decision time.
4. Marker contains no exercise text/taxonomy, raw key, exercise/workspace ID, authority tuple or original command. Its provenance is the verified local transition plus authenticated envelope, not a network-supplied marker or recomputation of discarded text. Same-key consumption still uses derived command ID.

Keep one bounded last marker until a later settled command replaces it or proven clean departure removes the UID partition. No TTL, marker history collection or retain-all-text fallback. An intact full pending proof can finish compaction after restart without network; a compact marker cannot serve as pending proof, coexist with same-key pending work or belong to another UID. New kinds must decode in upgraded clients; older unsupported decoders lock, never discard/reinitialize. Keep the current note marker behavior and seven legacy full proofs unchanged.

Server content custody is separate: the latest synthetic definition persists after withdrawal; corrections replace its text, without promising erasure from backups/storage. Receipts/fences contain no text and persist with coverage/config/history and budgets; no TTL or reset/reseed undermines own resolution. Unresolved submitted text may outlive access and cannot be discarded on a timeout. There is no approved real-data retention deadline, export, deletion, support recovery or secure erase. The requested C5/C6 approval would cover only this synthetic custody and compaction behavior; it has not been granted.

## Native admission, lifecycle and form behavior

Reuse the existing process-level protected-command owner and single slot; add typed `CustomExerciseWrite`/decision adapters to its current request/pending/proof/parser branches. Keep old serialized defaults/constructors/hash inputs compatible. Fixed native routing may select only the new callable or existing resolver; no generic endpoint/path API or separate journal/coordinator. New read methods do not use `InvitationLedger.prepare`.

On both Android and Swift, atomically check native UID/pin, generation, retirement/freeze, one in-flight command and readable initialized recovery state; persist preparation and reserve in-flight ownership before starting Auth/transport. Recheck UID/generation/retirement at delayed issuance and completion. Audit the existing delayed transport closures explicitly: do not assume adding an endpoint to the switch proves admission/freeze safety. A command admitted before freeze keeps departure blocked until settled; none can be newly admitted after freeze. Presentation cancellation suppresses delivery but must not suppress valid native settlement or claim to cancel an issued RPC. Settlement depends on original UID/native generation, not the old screen/workspace visit, so authority loss can still resolve account-owned work.

Restart validates the UID partition before publishing a role, resumes durable-proof retirement when present, otherwise exposes original Retry/confirmed Resolve in existing recovery. Resolve confirmation binds the exact pending identity/prompt; its durable `resolutionStarted` stops normal retries. Never edit old authority/revision/payload, substitute a new key or infer not-applied from a transport error. Disabled identity/key/storage/history uncertainty stays locked. Old UI callbacks/effects compare owner UID/workspace/role/authority epoch, top destination, visit, request generation and consumed sequence; no A result may affect B or a reopened form.

Clean departure remains freeze → prove all critical work empty → drain SDK writes → durable UID marker → fence/dispose → Auth sign-out → SDK terminate/clear and proven local cleanup → pin/marker completion → replacement generation. Pending command, failed proof/retirement, unreadable storage or other critical work blocks it. Leaving the editor is not sign-out and does not discard a pending command. No hot switch, independent queue, hidden background autosave, cache grant or recovery via another account.

| User/lifecycle event | Unsubmitted form | Submitted work |
|---|---|---|
| Create/correct entry | Fresh prepare/detail and current authority; values only in destination MVI memory | Existing pending slot blocks another Save; recovery remains available |
| Save | Validate all fields/budgets; freeze one exact payload/key/revision before transport; prevent duplicate Save/IME submission | Preserve original command until matching durable proof and retirement |
| Cancel/header Back/system Back/tab departure | Dirty form prompts Leave/Stay; Stay preserves; confirmed Leave clears. Clean form leaves directly. Keyboard-dismiss Back alone must not accidentally discard/navigate. | Explain that leaving does not cancel submission; no destructive cancel operation. Native settlement continues, editor can retire |
| Background, known connectivity/authority loss, UID/workspace/role/epoch retirement, process death | Clear without attempting an unreliable background prompt; no SavedStateHandle/rememberSaveable/disk/autosave copy. Warn near editor: unsaved changes are lost when leaving/backgrounding. | Retain encrypted original command; clear content presentation, recover only under original identity |
| Foreground/reentry/restart | Reverify scope; fresh read/preparation, no draft resurrection | Show minimal recovery state; no pending text in generic recovery UI or marker |
| Conflict/reload | Block Save until explicit authorized Reload (confirm dirty replacement); read failure does not overwrite a still-authorized foreground draft | Terminal conflict settles first; a revised submission is a new explicit command |

The app does not auto-copy fields to clipboard, logs, analytics, screenshots or diagnostics. No offline reading/editing grant: temporary form interaction between online entry and detected loss does not authorize a queued offline write. Android configuration/root recreation may lose transient form state; verify and explain actual behavior rather than claiming persistent drafts.

## Verification and acceptance plan

Run these only during separately authorized implementation; documentation checks are not runtime evidence. Report exact source/build, device/OS, fixture/provenance, stimulus, outcome and fresh versus retained attribution. Preserve failed attempts. No fixed pass-count target or blanket waiver.

| Layer | Required cases |
|---|---|
| Security and Rules | Prove direct custom/receipt/config/rate-budget reads/writes remain denied; trusted actions enforce author/path/workspace boundaries for owner, other trainer, client, role change and foreign UID. Reject forged `ownerUid`, IDs, unknown fields/schema, missing profile/directory, malformed authority, unverified/anonymous/disabled Auth, mismatched email/global account. Custom access cannot follow catalog entitlement alone. Existing catalog published-only read/write denials and all template/assignment-source denials remain unchanged. |
| List/detail and budgets | Exact owner query, first/empty/terminal/21-row pages, dedup, forged/cross-scope/stale cursor, malformed lookahead, foreign/missing detail equivalence, fresh refresh/Back, no cache fallback. Demonstrate query/index requirements, read/byte/deadline/rate limits and maximum Unicode/escaped payloads through full serializers/encryption. Over-limit admission leaves original storage byte-identical. |
| Mutation and lifecycle races | Own create collision, foreign collision without existence leak, concurrent same-ID creates, two corrections at same revision, same-key retries, changed-key/payload/kind replay, revision ceiling/future revision, malformed timestamp, rollback/no partial writes. Deterministic barriers race execution/replay with revocation, workspace suspend/restore, global disable and authority revision changes; compare complete definition and receipts. Restoration does not rebind delayed commands. |
| Resolver and provenance | Both commit-first and resolver-first, original paused after receipt read, delayed original after fence, lost response after commit/fence, contradictory/extra-field response, foreign UID, wrong hash/version, quota/deadline errors, missing legacy history and damaged coverage. Exactly one effect/decision, no error-based settlement; original global identity can resolve after workspace loss without content access. Audit every shared-namespace writer/consumed-key guard before enabling new issuance/absence coverage. |
| Shared UI/state | Field validation/error focus, canonical taxonomy, duplicate Save/IME, full exact command capture, dirty confirmations including queued stale dialog actions, explicit conflict Reload/new-create behavior, no success from snackbar alone, fresh detail after save, late/out-of-order page/detail/mutation callbacks, top-destination/visit/effect-consumption fences, background/scope clearing. |
| Storage and departure | Crash/fail before prepare acknowledgement, after prepare before send, after server commit before response, before/after proof write, before/after retirement, and after compact marker. Replay/fence/conflict variants; never erase unknown work. Lost/corrupt keys, unsupported fields/version/UID, proof-marker contradiction and same-key coexistence lock. Unresolved departure blocks; settled clean A→B proves no content/callback/proof leakage. |

### Compatibility with all eight existing protected variants

For each row retain original key/hash/receipt shapes and supported legacy decode, normal settlement and exact own-resolution behavior. Test pending → full proof → retired states, process restoration, delayed/duplicate callbacks and same-key contradictions whenever shared protocol code changes; do not claim eight-kind compatibility from custom-only tests.

| Existing action / kind | Special invariant |
|---|---|
| Invitation create / `invitation-create` | Derived invitation ID, delivery-token exclusion, unchanged canonical email/token-null hash |
| Invitation resend / `invitation-resend` | Exact invitation/version and no duplicate delivery from replay |
| Invitation revoke / `invitation-revoke` | Exact revoked result and original expected-version semantics |
| Invitation accept / `invitation-accept` | Digest-bound covered keys; preserve legacy original-bearer/binding-required lock |
| Complete onboarding / `onboarding-complete` | Exact account/onboarding revisions, no altered basics/policy hash |
| Update profile / `profile-update` | Applied and durable conflict; retained legacy full proof |
| Revoke client / `client-membership-revoke` | Applied/conflict, no restored assignment/authority or altered revocation bindings |
| Save trainer note / `trainer-note-save` | Existing nonce/four relationship bindings, occupied-slot denial and note `compacted-v1` retention unchanged |

### Native Android and Swift restart evidence

Use actual official native SDKs, protected storage and real app-process termination/relaunch on both hosts; Kotlin/Native tests alone do not exercise Swift. Cover both new kinds for prepared-before-send, server-applied/lost-response, durable conflict/lost-response, resolve-before-send/fenced, proof-written-before-retirement and retired-marker cold restart. Verify original bytes/key/UID/revisions, exactly one server effect and content-free settled storage. Test revoked/suspended scope with original global resolution, disabled identity remaining locked, delayed transport/completion across freeze/root recreation/background and clean A→B departure. Do not reset/reseed or change identity/key to manufacture successful recovery.

Use isolated create-only synthetic fixtures, distinct new keys/IDs and intact receipt/fence/config provenance. Reset-oriented suites must finish before starting a preserved native supervisor; preserve original PR 1/M5 exports, device partitions and pending commands. Export/check complete Auth/Firestore/Storage history before shutdown. Record emulator ports/versions and failed-run preservation without committing credentials, exports or generated logs. Production/history uncertainty disables absence terminalization.

During implementation run affected backend/Rules, shared Android/iOS tests and appropriate Android Debug/Release/device-test and native iOS Debug/unsigned-simulator Release builds. Inspect final Release artifacts for test hooks, fixture switches, credential paths and harness symbols; keep harnesses/test callbacks in Debug/test sources. Release hosts remain without operational Firebase product initialization. Simulator Release builds do not prove signing/distribution readiness. No dependency upgrades; test failures do not authorize broad cleanup.

### Actual interaction evidence required

On Android and iOS separately, exercise the final product UI with actual touches/keyboard/gestures and retain evidence for:

- System/custom distinction, author list/detail, Create, enter name/instructions, taxonomy selection, real Save and reopened current definition; edit and Save a correction.
- Software keyboard/IME Next/Done, multiline newline, focus/caret/selection, long text and field scrolling, page scrolling to Save/Cancel with keyboard shown; duplicate taps cannot submit twice. Do not substitute harness-created input for actual editor entry.
- Clean and dirty Cancel, toolbar Back, system Back/keyboard dismissal, tab leave, Leave/Stay dialog, actual background/foreground and process-loss draft behavior; submitted leave retains recovery.
- Two-session conflict through real UI: Save blocked, explicit Reload/dirty confirmation, unavailable reload, fresh successful reload and separate corrected submission. Exercise validation/service/denied/unknown-outcome states and recovery confirmation.
- Normal and 200% Android text, actual iOS OS Dynamic Type, gesture reachability of bottom controls/list pagination/long form, layout with keyboard and safe areas, localization expansion and reduced motion. Controlled Compose scaling is separately labeled and does not establish full OS Dynamic Type.
- TalkBack and VoiceOver traversal, labels/roles, logical focus, error/live-region announcements, conflict/reload/confirmation focus restoration, disabled/busy Save and selected taxonomy; preserve 44-visible/48-effective target decision and existing fonts/icons qualifications.

SDK network toggles/model lifecycle injection are not OS connectivity/background evidence. AX inspection/semantic code is not a screen-reader run. Builds and harness passes cannot substitute for keyboard, Save, Cancel, Back, conflict or accessibility interaction evidence. Missing tooling/actions remain explicit acceptance gaps.

Preserve [PR 1's actual interaction gaps](milestone-6-pr1-report.md#actual-interaction-and-remaining-acceptance): iOS gesture scrolling/lower actions/full Dynamic Type, Android large-text adjacent navigation reachability, TalkBack/VoiceOver, localization/reduced motion, controlled OS connectivity/foreground/revocation and physical/shared-device checks. Preserve [M5 H1–H11](milestone-5-closeout.md#remaining-gates-and-dependencies), including inherited M2/M4 acceptance, identity/storage/history locks, privacy/retention, assignment-index limitation, production/signing and offline/media gates. A new test closes only the exact overlapping action actually exercised on attributable final source; merged PR #16 is not full milestone acceptance.

## Technical prerequisites and implementation sequence

C1–C6 approval and a separate C7 implementation instruction are required before any implementation. No remaining proposal is accepted by silence. Recommended sequence within the later authorized PR:

1. **Freeze the approved contract.** Record decisions and reconcile only the affected custom schema/security/navigation/offline proposal text; explicitly extend ADR-002 for new-kind compaction. Freeze taxonomy, descriptor/hash/receipt/marker vectors, authority-restoration semantics and worst-case byte/read/write budgets. Resolve any cap mismatch by reducing the slice or returning for decision, not widening the recovery system.
2. **Implement trusted boundary and prove races first.** Fixed prepare/list/get/create/correct mediator, exact validators, authority transaction, bounded query/admission, immutable receipts and resolver adapters. Audit all lifecycle receipt writers and coverage/provenance; retain direct denials. Demonstrate any minimal index requirement before adding it. Do not enable issuance until writer/fence coverage is proved.
3. **Extend the existing shared/native protocol.** Typed payload/decision, pending validation/serialization, parser/hash/resolver/marker branches; Android and Swift fixed routing, admission/freeze and proof/retirement fences. Prove all eight existing variants and maximum serialized states; preserve backward read-or-lock behavior. No new framework, store or dependency.
4. **Add the bounded UI.** Reinspect available exercise design references; retain system browsing, add My custom exercises list/detail plus Create/correct form and truthful pending/conflict/reload states. Use destination MVI and existing navigation/dirty-leave ownership; no search, media, Programs, assignment or archive controls.
5. **Verify the complete slice.** Backend/security/shared checks, both native restart matrices, Debug/Release isolation and real final-build interaction/accessibility/OS checks. Record fresh/retained/failed/gap attribution and source fingerprints; do not waive inherited gates or reset pending fixtures.
6. **Review and report.** Review the whole implementation diff and evidence against C1–C7, security/privacy exclusions, links and whitespace. Leave implementation/publication actions governed by the future instruction; this plan authorizes none. Rollback may hide the new UI, but must retain consumed-key guards, receipts/fences, provenance and readable-or-locked pending envelopes; never delete barriers to make an older build appear clean.

Create/correct is sufficient to prove reusable authored definitions and reliable correction without committing to archive or program references. Real exercise content/medical suitability, rights/attribution/localization, personal-data purpose/consent/retention/export/deletion, production environments/indexes/signing, broad member access, program builder/assignment, media/search and offline grants remain excluded. Stop after this documentation handoff for the explicit decisions above.


## Approval and implementation authorization (2026-10-26)

Josip explicitly approved C1–C6 and separately authorized C7 for the exact synthetic, emulator-only slice. This approves immutable author/workspace ownership and current author-only access; fresh same-author access after independently authorized restoration without rebinding old commands; the exact minimal fields/taxonomy/limits; create/browse/detail/correct only; memory-only unsubmitted drafts and encrypted unresolved submitted retention; and the two protected command kinds, exact conflicts/replay, finite budgets and content-free settled markers. Historical proposal/awaiting-approval and documentation-only language above describes the original planning handoff and is superseded only within this approval. Future reference/publication policy remains unapproved and no reference implementation is authorized.

The approved implementation must first freeze and prove complete serialized budgets, including a full legacy last-settled proof and duplicated pending proof. Valid field combinations exceeding a total budget require explicit pre-submission validation; no truncation, history clearing, cap expansion, command splitting or second store. Report substantive contract contradictions before broadening scope. Preserve system browsing, all eight existing protected variants, whole-key fencing, original datasets/partitions/keys/provenance and existing locked cases.

Authorization includes recording approval and committing only the three planning files with `docs: approve milestone 6 custom exercise plan`, then creating `feature/milestone-6-pr2-custom-exercises`; the approved implementation/verification/report follows and remains uncommitted and unstaged. Run isolated Node 22 with the existing lockfile, affected backend/shared tests and Android/iOS Debug/Release builds, real native restart/storage/departure scenarios and actual UI interactions where supported. Unavailable interaction/accessibility/OS/physical checks remain qualified; never reset or rekey unresolved work for a pass. Create `milestone-6-pr2-report.md` with exact inventory, source fingerprint and fresh/retained/failed/gap attribution; related contract and ADR-002 updates are permitted only within this scope.

No push, PR, merge, deployment, production enablement, dependency upgrade or PR 3 implementation is authorized. No owner override, other-author/client grant, automatic restoration, archive/delete/transfer, search, media, template references, offline grant or new recovery framework. Real-data, production, offline and later program-building policies remain unapproved. Existing PR 1 and inherited acceptance gaps remain open.
