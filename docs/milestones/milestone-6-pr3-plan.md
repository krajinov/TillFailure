# Milestone 6 PR 3: explicitly saved program-template drafts

Status: **P1–P6 approved; P7 separately authorized on 2026-10-28. Synthetic emulator implementation is locally complete for review, with acceptance gaps.**
Prepared 2026-10-28, Europe/Sarajevo. The proposal and original planning evidence below are historical; the [implementation report](milestone-6-pr3-report.md) records actual measurements and verification. The approval section supersedes approval-pending language only within its exact scope.

## Baseline and evidence

The connected GitHub metadata confirms [PR #17](https://github.com/krajinov/TillFailure/pull/17) merged into `main` on 2026-10-28 at **`b67c5a557314d032cd0a1130a6c3176a1124ddba`**. Local main contains that commit. Initial working tree and index were empty; `git pull --ff-only` reported already up to date. Created **`docs/milestone-6-pr3-program-drafts-plan`** from that baseline. No reset, amend, rebase or force-push. No applicable AGENTS.md was found in the repository or ancestor directories. Read README development instructions and its required Compose guidance; this task changes no Compose code.

Inspected the [M6 plan](milestone-6-plan.md), [PR 1 report](milestone-6-pr1-report.md), [approved PR 2 plan](milestone-6-pr2-plan.md), [PR 2 report](milestone-6-pr2-report.md), [product scope](../product-scope.md), [schema](../firestore-schema.md#exercises-and-programming), [security](../firestore-security.md), actual [Rules](../../firebase/firestore.rules) and [indexes](../../firebase/firestore.indexes.json). Also inspected [system exercise contracts](../../shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/exercises/SystemExercises.kt), [custom contracts](../../firebase/functions/src/custom-exercise-contract.ts), [custom mediator](../../firebase/functions/src/custom-exercises.ts), [ledger](../../shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/invitations/InvitationClient.kt), [shared resolver/proofs](../../shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/invitations/CommandResolution.kt), [server resolver](../../firebase/functions/src/command-resolution.ts), [coverage guard](../../firebase/functions/src/command-identity.ts), [envelope serializer](../../shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/persistence/PersistencePrototype.kt), both native encrypted stores, [ADR-002](../trainer-notes-settlement-adr.md), [offline/departure contracts](../offline-sync.md) and [assignment primitive](../../firebase/functions/src/assigned-program.ts).

Implemented versus proposed matters:

- PR 1 is online published system browsing; its exact schema has **no revision field**. Fixed account catalog entitlement is distinct from selected-workspace trainer authority.
- PR 2 implements synthetic, author-only custom create/read/correct with immutable owner/workspace, numeric revision, exact original authority bindings, finite admission and content-free retirement. Other authors, workspace owners acting as an override and clients cannot read custom sources.
- There is **no draft or template publication endpoint, adapter or product flow**. Rules explicitly deny all template descendants. Broad template fields, offline editing, archive and trainer visibility in older schema/security tables are future intent, not existing permissions. This proposal narrows them; approval must precede reconciliation in an implementation PR.
- Assignment is an isolated immutable-copy primitive using `assignmentCommands`, supplied snapshot content and string prescriptions. It is not an authenticated template publisher or one of the protected command variants. M7 must reconcile that primitive and the security document's future `lifecycleCommands` terminology; PR 3 must not invoke it.
- Existing indexes cover memberships, planned workouts and assignment discovery. There is no checked-in template composite index. A bounded author-equality/document-ID query must be demonstrated in emulators; production index readiness remains separate.

### Actual protected-command inventory

The action switches in `validPendingCommand`, `CommandResolution.kind/version`, `resolutionBinding` and `terminal` agree on **ten** variants. Historical “eight” counts describe the pre-PR-2 baseline.

| Action | Protected kind / hash version | Retained behavior |
|---|---|---|
| `create` | `invitation-create` / `invitation-v1` | Derived invitation ID; no delivery token in proof; full legacy settlement |
| `resend` | `invitation-resend` / `invitation-v1` | Exact version, no repeated delivery effect on replay; full proof |
| `revoke` | `invitation-revoke` / `invitation-v1` | Exact original version/result; full proof |
| `accept` | `invitation-accept` / `invitation-v1` | Original digest binding; unsupported legacy bearer/absence stays locked; full proof |
| `complete-onboarding` | `onboarding-complete` / `onboarding-v1` | Original account/onboarding revisions; full proof |
| `update-profile` | `profile-update` / `profile-v1` | Applied or durable conflict; full proof |
| `revoke-client` | `client-membership-revoke` / `client-revocation-v1` | Original four bindings; assignment exclusion; full proof |
| `save-trainer-note` | `trainer-note-save` / `trainer-note-v1` | Permanent relationship binding, occupied slots, full pending proof then note marker |
| `create-custom-exercise` | `custom-exercise-create` / `custom-exercise-v1` | Create collision, current author authority, full pending proof then marker |
| `correct-custom-exercise` | `custom-exercise-correct` / `custom-exercise-v1` | Expected revision, current author authority, full pending proof then marker |

All share `lifecycleCommands/{SHA256(canonical({callerUid,idempotencyKey}))}` and its immutable whole-key fence. Booking/assignment transactions, ordinary reads and prototype journals are not extra protected variants. PR 3 proposes one additional kind, not a replacement inventory.

## Decisions for approval

Recommend a synthetic, emulator-only, online author workspace with explicitly saved reusable drafts. A trainer can create, browse, reopen and replace a saved draft; add/remove/move workouts and exercise items; and enter a small prescription. No autosave or offline editing. **Publication/version browsing stays PR 4; assignment stays M7. No Publish or Assign controls.**

| ID | Concrete recommendation | Approval needed |
|---|---|---|
| P1 Ownership/access | Immutable Auth-derived author and selected workspace; only the currently authorized same author can read/create/Save. No owner override, colleague/client visibility or transfer. | Product/security |
| P2 Withdrawal/restoration | Retain inaccessible saved content after withdrawal/suspension. Independently authorized same-author restoration permits fresh reads/new commands; old pending commands keep their original authority revisions. No restoration operation here. | Product/security/privacy |
| P3 Saveable content | Three workouts, twelve total items, exact fields/units below; blank title/description/workouts and null prescription values can be saved as incomplete. Invalid structure/numbers never save. | Product |
| P4 Source copies | Copy **name only**, plus source identity/revision/fingerprint. No instruction/taxonomy copy in PR 3. Preserve saved copies when sources change/disappear; fresh additions require current authorized matching sources. | Product/content/security; essential feasibility tradeoff |
| P5 Explicit Save/loss/conflict | Memory-only unsubmitted edits, visible loss notice, dirty Leave/Stay, clearing on background/process/scope loss. Exact Save and explicit Reload after conflict; no merge/overwrite/rebase. | Product/privacy |
| P6 Custody/settlement | Full submitted payload in existing encrypted slot until durable proof; content-free draft marker afterward. Server retains latest draft and minimal immutable decisions without new cleanup/retention grants. | Product/privacy/security; explicit ADR-002 extension |
| P7 Handoff | Approve exact P1–P6, then separately authorize implementation and its verification. Finite budget/source/serializer prerequisites below are mandatory. | Josip |

No archive, delete (including hard delete), duplicate/copy-template, transfer, coauthor, restore button, media, search, per-item coaching notes, client data, calendar/week schedule, automatic source refresh or version browser. Removing nested draft content is a Save edit, not a resource-deletion API. Repeating the same exercise through the picker is allowed with a new item ID; it is not a template-duplication operation. Blank create opens no server document until explicit Save.

### Exact reader and writer matrix

All feature access uses a fixed guarded mediator; direct mobile get/list/write of the template tree, receipts, admission records and coverage configuration remains denied. Auth must be non-anonymous, verified, enabled and revoked-token checked, with active schema-1 global account/email binding. Within the transaction use PR 2's five documents: `users/{uid}`, `workspaces/{wid}`, `workspaces/{wid}/memberships/{uid}`, `workspaces/{wid}/trainerProfiles/{uid}`, `users/{uid}/membershipRefs/{wid}`. Require PR 2's exact path/schema/role/status/contribution/revision predicates. Body-supplied author IDs are rejected.

| Caller/state | Draft list/get | Create/Save | Own original outcome |
|---|---|---|---|
| Active author with exact active trainer membership/profile/directory in active workspace | Own only | Own only, current authority plus submitted tuple | Existing resolver permission |
| Workspace owner who is not that author, other trainer, other workspace, client | Denied; foreign/missing detail indistinguishable | Denied, no override | Only their own commands, never this author's |
| Author withdrawn, trainer role removed or workspace suspended | Denied | Denied, even ordinary replay | Minimal outcome allowed if original global identity remains eligible |
| Same author after independently authorized restoration | Fresh current access to retained drafts | New commands with fresh tuple; no rebinding old pending work | Original key/hash resolution still independent |
| Disabled/deleted/unavailable Auth/global account or damaged protected storage | Denied/locked | Denied/locked | Existing identity/storage lock; no inferred settlement or support override |
| Trusted operator/service | No new product read/export/support grant | Only guarded feature writes and existing separately authorized lifecycle operations | Existing trusted receipt machinery |

The immutable draft owner is not a permanent membership-revision binding like private notes. Account lifecycle, author membership and workspace membership revisions bind each **command**. These distinctions must survive restoration races. Saved server content stays in custody with no new expiry/deletion policy. Unknown remote revocation cannot erase already rendered memory; known denial/background/scope retirement clears it and every new request rechecks authority.

## Exact proposed data contract

### One bounded document

Use **`workspaces/{wid}/programTemplates/{templateId}`** for both immutable identity metadata and the entire current mutable draft. No `drafts/current`, workouts subcollection, item documents, summary duplicate, source snapshot store or payload pointer. UUIDs are lowercase RFC-4122 version-4 strings, matching the existing custom ID validator. Workout and item IDs are stable across edits, unique across their respective entire template namespace; array order alone defines position. No redundant `position` field.

Exact document fields (all present; no unknown fields):

```text
schemaVersion: 1
policyVersion: 1
workspaceId: strict path identifier, existing <=128 UTF-16-unit domain
templateId: UUID; equals path
ownerUid: path-safe Auth UID; equals verified caller on creation, immutable
revision: integer 1..2147483646
createdAt, updatedAt: server Firestore Timestamp, 0..8640000000000000 ms
title: string
description: string
workouts: [{id: UUID, title: string, items: [Item]}]

Item = {
  id: UUID,
  sourceKind: "system" | "custom",
  sourceId: system [A-Za-z0-9_-]{1,128}, or custom UUID,
  sourceRevision: null for system, integer 1..2147483646 for custom,
  sourceHash: lowercase SHA-256 hex, 64 characters,
  name: nonblank copied source name,
  prescription: {sets: integer|null, reps: integer|null,
                 restSeconds: integer|null, loadGrams: integer|null}
}
```

Read DTOs replace `createdAt`/`updatedAt` with `createdAtMillis`/`updatedAtMillis`; otherwise preserve the shape. Validate timestamp ordering and exact schema/ownership before any projection. There is no status/currentVersion field in PR 3. PR 4 may add a separately approved version pointer/counter through a deliberate schema transition; it must use this same root `revision` for Save/Publish serialization.

Why this is the smallest adequate layout: everything is loaded/edited/saved together, source authorization is mediated, no child is independently queried or shared, and the projected maximum is about 10 KiB. Bounded arrays do not violate the schema's prohibition on **unbounded** sequences. Full `set` replacement, without merge, atomically removes every omitted workout/item; there are zero obsolete child documents to scan/delete and no partially saved tree. A separate header/draft adds an invariant and read/write without a present requirement. Publication's future immutable layout is not a reason to copy its tree now. Unexpected schema/legacy child-layout data must fail closed; no migration/cleanup is authorized by this proposal.

### Limits and incomplete drafts

| Field | Finite proposed limit / interpretation |
|---|---|
| Workouts / items | 0–3 workouts; 0–12 total items; each workout at most 12. Repeated sources allowed, duplicate workout/item IDs forbidden. |
| Template title / description | 0–80 / 0–240 Unicode scalars; description may contain interior LF. |
| Workout title | 0–40 Unicode scalars. |
| Copied source name | 1–120 scalars; exact source name, never shortened or author-edited. |
| Combined text | **At most 650 scalars across template title, description, all workout titles and all copied names**, counting every repeated copy. Also at most 2,600 raw UTF-8 bytes. Individual maxima are not independently additive. |
| Text validity | Empty is allowed only where stated. Otherwise PR 2 ECMAScript-trimmed scalar validation: no isolated surrogates, C0/C1 controls except interior LF in description, CR/tab rejected. No Unicode normalization. Quotes/backslashes and non-BMP text allowed. No silent trimming/truncation; form shows explicit validation. |
| Sets | null (unspecified) or integer 1–20; one uniform count, no per-set arrays. |
| Reps | null or integer 1–1,000 per set; no ranges or failure/AMRAP token. |
| Rest | null or integer 0–3,600 seconds between sets; zero explicitly means no prescribed rest. |
| Load | null or integer 0–1,000,000 grams; UI displays kg with at most three decimal places (0–1,000 kg), exact decimal-to-integer conversion, no binary-float rounding. Zero explicitly means zero external load; null means unspecified. |

These are representation limits, not training/safety recommendations. No imperial conversion, duration/distance, percentages, RPE/RIR, tempo, supersets or progression vocabulary in this slice. Raw partially entered numbers stay in form state; only blank maps to null. Fractions of reps/sets, negative/overflow/NaN/infinite numbers, `"12."` or other invalid raw input cannot silently become null or round on Save.

A blank template, empty workout, missing workout title, or all-null prescription is incomplete but saveable. A selected item always has a complete authorized source tuple/name; there is no dangling placeholder item on the server. Add empty workout is valid; picker cancellation creates no item. Invalid IDs/types/unknown fields/duplicates/source tuple, exceeded scalar/count/serialization budgets or malformed numeric input is invalid. Derived “Incomplete draft” guidance is not a stored publication-ready flag. PR 4 must separately approve completeness and source-copy rules; saving never proves readiness to publish or assign.

Add/remove/reorder operates on the full in-memory aggregate. Removing a nonempty workout confirms removal of its items; item removal is an explicit labeled action. Moves preserve IDs and prescription/source tuple; item moves between workouts respect the total cap. Supply **Move up/Move down** for workouts/items and **Move to workout** for items, with boundary-disabled states and focus restoration. Drag is optional, never the only operation. A canceled edit leaves the saved document unchanged.

### Source identity and change policy

For system items use the global source path `systemExercises/{sourceId}`, `sourceRevision:null`, and `sourceHash = stableHash` of the exact object `{sourceKind:"system", sourceId, definition:{schemaVersion,name,nameNormalized,muscleGroups,equipment,instructions,status}}`. Array order is included. Existing system schema has no numeric revision; the canonical content hash is the revision token. No catalog schema modification is proposed.

For custom items use the same draft workspace's `exercises/{sourceId}`; workspace is inherited, never selectable per item. `sourceRevision` is the existing custom revision. Hash the exact object `{sourceKind:"custom",workspaceId,sourceId,definition:{schemaVersion,policyVersion,ownerUid,name,instructions,muscleGroups,equipment,revision}}`. Exclude timestamps; ownership/tenant/source identity are included. Canonicalization uses the existing sorted-object-key SHA-256 function, preserving array order and Unicode bytes. No mutable lookup pointer substitutes for the submitted name or full draft payload.

The picker reuses bounded system/custom browse/detail behavior, with a fresh source detail supplying the exact name, revision/hash and current authorization. The new mediator must compute/verify the same fingerprint independently. System additions require the existing active schema-1 account/catalog entitlement (integer contribution count 1–20, active status) and published source. Custom additions require exact same-author/workspace PR 2 definition validation; catalog entitlement alone grants nothing.

On Save compare each item to the previous saved template by item ID:

- An unchanged source tuple **and name** on an existing ID is retained even if the source was corrected, removed, archived or is no longer authorized. Author's draft access permits reading the previously copied name. Prescriptions/order may change. There is no live instruction/taxonomy copy and no new right to fetch that source.
- Every new ID or changed source tuple/name must match a freshly read currently authorized source exactly within the Save transaction. No foreign custom-source probing/details. A source changed since selection, missing source or disallowed source returns the same minimal `draft-source-conflict` decision, without identifying existence/content or refreshing the submitted payload.
- Source correction never silently rewrites a saved draft. Offer explicit fresh picker replacement; replacing an item uses a new item ID and can preserve its prescription only visibly in the foreground. New selection of a now-unavailable source is disallowed; an already saved item can be removed or kept.
- Optional fresh source-detail inspection is online/authorized, labeled as current source instructions, not saved draft content. If unavailable, show the saved name and neutral unavailable guidance. Do not claim every source was freshly checked when reopening a draft; status requires an actual read.

This policy lets an author repair/reorder an incomplete draft without an unavailable source trapping all Saves. It explicitly approves custody of old copied names after source loss, not access to historical source documents. PR 4 must decide how to handle stale sources and copy complete published content; no assumption that this draft alone is a complete immutable version.

## Protected Save and revision semantics

Propose fixed `programDraftsEmulator` actions `draft-prepare`, `draft-list`, `draft-get`, `save-program-draft`; no arbitrary path/method dispatch. Prepare takes `{action,workspaceId}`, list `{action,workspaceId,afterId}` with null or UUID, get `{action,workspaceId,templateId}`. Prepare returns `{workspaceId,authority}`; get adds `draft`; list adds `items:[{templateId,title,revision}],nextId` (20 rows plus one lookahead). Fresh server-only calls validate all documents before projection. Author equality plus document-ID ascending ordering; transient visit-bound continuation is a position, not a credential. No name search/filter/updatedAt index requirement or global template scan.

Exact Save descriptor and resolver wrapper:

```text
descriptor = {
  action: "save-program-draft", workspaceId, idempotencyKey,
  draftSave: {templateId, expectedRevision,
    authority:{authorLifecycleRevision,authorMembershipRevision,workspaceMembershipRevision},
    title, description, workouts}
}
callable request = {data: descriptor}
resolver = {protocolVersion:1, descriptor}
resolver callable request = {data: resolver}
```

`draftSave` in pending/request DTOs and `draftDecision` in replies use absent-null `EncodeDefault.NEVER`, preserving old bytes. Nested prescription fields and `sourceRevision` are **required explicit null or value** in both wire and persistence serializers, avoiding the existing default `explicitNulls=false` ambiguity. Capture immutable full payload before native prepare; no caller UID or timestamps in descriptor. New issuance uses the existing covered `or1-{generation}-{nonce}` key, nonce 1–80 ASCII `[A-Za-z0-9_-]`, with intact configured provenance. No new namespace or generation/history assumption.

Kind **`program-draft-save`**, request hash version **`program-draft-v1`**. Command ID is existing caller/key canonical SHA-256. Request hash is `stableHash({...descriptor,callerUid:uid,policyVersion:1})`. The hash includes expected revision, authority, all content, source copies and array order. Freeze backend/shared Unicode/null/hash vectors before implementation. Omitted/extra nested fields are invalid, not equivalent encodings.

Create uses expectedRevision **0**, absent target, revision 1. Update requires an existing same-author document and expectedRevision 1–2147483645. Applied Save advances the single root revision by exactly one, preserves `createdAt`, and stamps `updatedAt` without clock regression. A content-identical explicitly submitted command still advances once; UI may disable Save for a clean existing draft. The revision never wraps. A missing update target, foreign target or actual revision below expected is unavailable, not an upsert/conflict disclosure.

Read current authority/coverage, receipt, target and required sources **before any effect writes**. Validate current authority and the three exact original authority revisions on execution and business replay. Check existing receipt before source revalidation/effect calculation: valid exact replay returns the original decision even if the target/source later changes, provided ordinary replay authority still holds. Otherwise use original-global-identity resolver. Never rebind pending work to a new workspace/revision.

Minimal decision: `{status,workspaceId,templateId,templateRevision}`; normal response adds `replayed:Boolean` and maps to `InvitationReply.draftDecision`. Status is:

- `draft-saved`: templateRevision = expectedRevision + 1.
- `draft-conflict`: own target already exists at revision > expectedRevision, including create collision. No effect; commit decision receipt. Check this before sources.
- `draft-source-conflict`: target revision equals expectedRevision (0 for absent create); new/changed source validation failed. No effect; commit decision receipt. No source names/IDs/reasons in result.

Receipt at the existing whole-key path has exactly `{schemaVersion:1,commandKind:"program-draft-save",callerUid,workspaceId,templateId,requestHash,result,committedAt}`. Both conflict statuses map to resolver `not-applied-conflict`; saved maps to `applied`. The existing seven-field resolver response and schema-2 fence shape remain unchanged. Do not require revision advancement for a source conflict. Auth/config/read errors are not source conflicts and never constitute terminal proof.

Same caller/key + exact hash replays one decision; different payload, target or kind cannot reuse the key. A schema-2 whole-key fence forbids Save even after restoration. Concurrent distinct keys at one expected revision yield one saved effect and durable draft conflict(s); a retry cannot overwrite the winner. Resolver-first fences prevent delayed original execution; commit-first returns applied/conflict. Timeouts and absence outside verified history never imply not applied. Resolve requires explicit confirmation and durable `resolutionStarted` to stop local business retries. Audit **all ten existing writers and new Save** for consumed-key coverage before issuance/absence is enabled.

Future Save/Publish must read and update this **same root revision** in their atomic effect/receipt transaction. Distinct concurrent Save/Publish or Publish/Publish at one expected revision must have one winner. Publish must advance it even if draft content is unchanged, preventing a stale Save from silently overwriting a post-publication draft. Version counters/pointers, immutable contents and publication validation are PR 4 decisions; no second draft-only revision may bypass this serialization.

### Submitted custody and clean departure

Pending stores the exact `draftSave`, UID, original action/key/workspace and existing metadata in the single encrypted envelope; draft content is duplicated in full pending terminal proof. Unknown outcomes retain it, potentially indefinitely under existing identity/storage/history locks. Generic recovery displays minimal status, never draft text. Server receipts/fences contain identity/hash/result only, not draft text; latest server draft remains retained separately. A hash is not a claim of anonymization or deletion.

After validating normal acknowledgment/resolution, durably write **full** proof with pending command, then retire to `compacted-v1` in existing last-settled slot. Marker fields are existing schemaVersion, UID, kind, commandId, hash version/hash, source/outcome and resolver decision time when present. No title, descriptions, workouts, item/source IDs, workspace, authority or raw key survives in this new-kind marker. Normal acknowledgment invents no decision time. Approval P6 must explicitly extend ADR-002; its existing notes/custom approval does not cover drafts automatically.

Proof-write or retirement failure retains original recoverable state. Restart may finish retirement only from valid matching durable full proof. A marker cannot replace pending proof, coexist under the consumed key, cross UID boundaries or grant source access. Preserve seven legacy full-proof forms, note/custom markers and supported full note/custom proofs. No history clearing to make space.

Use unchanged clean departure: freeze → prove critical work empty → drain SDK writes → durable UID marker → fence/dispose → sign out → terminate/clear/proven local cleanup → pin/marker completion → next account. Native UID/pin/generation/freeze admission must persist before transport, recheck delayed issuance/completion, and prevent new preparation after freeze. Presentation cancellation does not cancel issued RPCs or suppress valid account-owned settlement. Unresolved commands, failed proof writes or ambiguous storage block departure. No account B work/renderer can observe A payload, source copies or callbacks.

## Calculated feasibility and admission

Run `node docs/milestones/milestone-6-pr3-budget-analysis.mjs` from the repository root to reproduce the calculation. The [documentation-only analysis script](milestone-6-pr3-budget-analysis.mjs) was run with Node, without application imports, builds, backend calls or stored-user-data access. It mirrors inspected compact JSON shapes: PersistenceJson explicit defaults/nulls, omitted absent note/exercise/draft/marker fields, all envelope metadata, duplicate pending payload, full acknowledgment fields and existing settled forms. **These are calculated JSON sizes, not measurements from an implemented draft Kotlin serializer or native SDK.** Native/Firestore wire measurements remain prerequisites.

Worst structural case uses three workouts/twelve items, system IDs of 128 ASCII characters (larger than custom UUID plus numeric revision), UUIDs, 650 total text scalars, maximum numeric widths and longest null representation (`sets:null` is longer than `20`). Workspace/UID use 128 allowed control code units that JSON escapes to 768 bytes each; key uses maximum covered nonce; revisions/times have maximum widths. Emoji maximizes text bytes; quotes/backslashes maximize escaping; BMP three-byte text is also exercised. Interior LF costs two escaped units, no more than quotes; disallowed controls/surrogates cannot expand text further. Each allowed text scalar is at most two JSON UTF-16 units/four UTF-8 bytes. The exact 650-scalar distribution among fields does not change that bound. Arrays/metadata are not omitted in the calculation.

| Serialized object | Calculated maximum for proposed new content | Existing unchanged ceiling |
|---|---:|---:|
| Entire resolver wrapper | **7,580 UTF-16 units; 8,880 UTF-8 bytes** | 8,192 units; 24,576 bytes |
| Save callable `{data:descriptor}` | **8,854 bytes** | 32,768 bytes |
| Resolve callable `{data:resolver}` | **8,889 bytes** | 32,768 bytes |
| Pending command, including UID/defaults | **9,790 bytes** | 16,384 bytes |
| Full pending terminal proof, including complete command/acknowledgment | **11,133 bytes** | Must fit full envelope; no separate enlarged allowance |
| Full envelope with lastSettled null | **22,735 bytes** | 65,536 bytes |
| Full envelope with full legacy revocation proof | **29,148 bytes** | 65,536 bytes |
| Full envelope with supported full note proof | **36,407 bytes** | 65,536 bytes |
| Saved document read projection | **9,414 bytes** | Proposed 16,384-byte validated projection cap |

Calculated envelopes with seven representative full legacy settled records: invitation create 26,769; resend/revoke 26,595 each; accept 25,892; onboarding 26,889; profile 27,584; revocation 29,148 bytes. With settled note/custom-create/custom-correct markers: 23,943 / 23,951 / 23,952. With supported full custom-create/correct proofs: 34,788 / 34,809. These **history fixtures are not a proof of a universal maximum legacy envelope**; existing optional metadata/prototype arrays are not universally bounded by this proposal. Legacy receipt timestamps in fixtures are sizing-width examples, not newly executed valid server history.

Exact model formula with its other maximum metadata and a real serialized settled record of size S: **22,731 + S bytes** (replace four-byte `null`). This leaves 13,769 bytes for that record under the proposed new-draft 36,500-byte admission ceiling when other fields match the model. Additional actual metadata reduces that room. For **every Save**, serialize the actual existing envelope unchanged with the prospective pending command, each legal normal proof and all three resolver proof outcomes; admit only if every candidate fits both the existing 64 KiB ceiling and the stricter **36,500-byte new-draft admission ceiling** needed for worst-case Swift encryption serialization. Include restoration/prepared/proof states, actual existing settled record, optional fields and later switch-state compatibility. New Save requires Clean recovery state; do not reserve a guessed amount or retroactively reject/read-rewrite old envelopes. Oversize refuses before prepare/transport and leaves stored bytes untouched. User can shorten the foreground draft; no clearing history, truncation, rekeying or automatic command splitting.

Calculated compact AES-GCM/base64 wrappers for 36,407 bytes plaintext: Android 48,672; Swift 48,685 bytes **before Swift slash escaping**. Inspection found Swift's existing `JSONEncoder` sets only `sortedKeys`, so `/` in base64 can expand to `\/`. A standalone Foundation encoder probe with the same `sortedKeys` setting confirmed slash escaping; it did not build or run an application. A conservative bound allowing every base64 symbol (including nonce/tag) to expand gives **97,269 bytes** at this plaintext size. At the proposed 36,500-byte admission ceiling it gives **97,517**, below the existing **98,304-byte (96 KiB)** budget. Padding makes this conservative bound slightly larger than attainable all-slash base64. Header sizes include current fixed keyIdentifier and fields; no ciphertext/data is printed by the analysis.

At the old full 65,536-byte plaintext ceiling, compact Android/Swift are 87,512/87,525 bytes, but the conservative Swift escaped bound is **174,949 bytes**, which does not fit 96 KiB. Thus PR 2's compact-base64-plus-header argument is not a universal worst-case Swift proof. The 96 KiB figure is an approved admission/design budget; the inspected native stores do not currently enforce a per-file 96 KiB length guard. Do not describe it as an existing runtime guarantee. This planning task changes no native serializer or legacy record. **P6 includes stricter admission for new draft Saves only**, with actual prior history retained and rejection before preparation if it cannot fit. Preserve legacy read-or-lock behavior, require actual native encoder checks during implementation, and separately investigate the inherited custom-admission guarantee; do not silently change Swift encoding or enlarge a ceiling. Reducing new content to 650 scalars makes the largest modeled supported full-note history fit this stricter bound. Additional actual metadata can still reject admission.

The 612-unit resolver headroom is intentional but finite: any new field or changed spelling/default/null semantics requires rerunning the calculation and actual serializer tests. Max field/count validation plus complete serialized admission are both required; UI must explain combined text and storage-budget rejection. Native storage lock is not reported as an ordinary text error.

### Alternatives if P4 or limits are not useful enough

Full instruction copies at current source limits cannot support this useful twelve-item draft: twelve 2,000-scalar non-BMP instruction strings alone cost **48,000 UTF-16 units / 96,000 UTF-8 bytes**, before names, identifiers, wrapper and duplicated proofs. Even two maximum instruction copies consume 8,000 UTF-16 units before the remaining structure. A multi-document tree does not shrink the complete original command or resolver.

If complete instructions must be saved in PR 3, return for a decision: (a) reduce to a much smaller explicitly approved item/content subset and recalculate; (b) select a separate short source-content contract with explicit rights/selection rules, never silently truncate current sources; or (c) undertake a separately authorized recovery/protocol redesign. The recommendation is name-only copies for drafts and a separate PR 4 publication investigation. This task does not approve cap enlargement, chunking, blob storage, mutable payload pointers, another journal, or an assumed solution for PR 4's larger immutable content. PR 4 feasibility remains a real gate.

### Bounded reads, writes and bytes

Proposed per-UID fixed-window admission at `programDraftRequests/{stableHash(uid)}`: exact `{minute,count}`, maximum 120 calls/minute, one read/one write, at most three attempts. This is a server-only rate counter, not a journal. Existing resolver rate and per-generation absence-fence budgets remain unchanged (120 attempts/minute; configured fence ceiling no greater than 100).

| Operation | Maximum transaction reads / writes per attempt | Byte/deadline bounds |
|---|---|---|
| Prepare | 5 authority / 0 | Each authority serialized document <=16 KiB; result <=4 KiB |
| List | 5 authority + 21 full template docs / 0 | Validated template projection <=16 KiB each; total document JSON <=416 KiB; result including lookahead projection <=32 KiB |
| Get | 5 authority + 1 target / 0 | <=96 KiB document JSON; result <=24 KiB |
| New Save | 1 generation config + 5 authority + 1 receipt + 1 target + 1 catalog entitlement (if needed) + <=12 unique new/changed sources = **21** / **2** | Config/receipt/entitlement <=4 KiB each, authority <=16 KiB each, template <=16 KiB, source <=24 KiB each: **396 KiB** document JSON; target <=16 KiB + receipt <=4 KiB = **20 KiB** logical write JSON |
| Exact business replay | Config + 5 authority + receipt + target <=8 / 0 | Check final elapsed deadline too; no source reads/effect writes |
| Own resolution | Existing account/receipt, plus config/budget on absence <=4 / <=2 | Existing mediator limits and minimal reply unchanged |

All feature operations additionally pay the separate admission transaction (<=1 KiB read/write), including replays/errors; do not hide that cost. Save maximum is 22 reads and three committed writes across admission plus business transactions, not an unbounded loop. At most three attempts for each transaction means up to 66 document-read attempts and nine write attempts, with at most one admission/effect commit each; source path deduplication can only reduce reads. Deadline checks at transaction entry, before writes and before replies: five seconds elapsed business work, at most three attempts, with native presentation timeout/cancellation treated as unknown outcome rather than RPC cancellation. Source/target/authority contention retries cannot turn into unconditional Save.

Document JSON is a proposed **application validation budget**, not a measured Firestore protobuf/storage/index byte count. Validate complete loaded documents, including unexpected baggage, before use; over-cap or malformed infrastructure fails closed. Network/protobuf/index overhead is **estimated**, reserve at least 64 KiB for serialized Save writes including paths/metadata and measure actual Admin SDK requests during implementation. Reads may transfer bytes before rejection; this is not a transport-layer denial-of-service guarantee. No source array scan beyond twelve unique point reads, no fan-out on removal, and no unbounded query. Prove authority document sizes against existing fixtures before adopting these additional checks; mismatch requires review, not silently changing recovery ceilings.

## Save, conflict and navigation behavior

Use destination-owned MVI, existing stateless Screen/Route split, stable item keys and narrow feature repository methods through official Android/Swift adapters. Navigation carries only scope/epoch/visit/template ID. Picker is a child state/route under the editor with explicit completion/cancel; temporary picker navigation must preserve the parent foreground form without disk persistence. Reentering from outside performs fresh authorization/read. Programs list opens saved current drafts, not published versions.

| Event | Required behavior |
|---|---|
| Save/IME/duplicate taps | Validate raw fields, aggregate budgets and actual-envelope admission; capture one exact payload/key/expected revision/authority and persist before issuance. Freeze editor mutation while submitted; show pending, never “Saved” from local state/snackbar alone. |
| Applied result | Durable full proof then retirement; fresh authorized get before showing current saved content. A later revision is labeled current, not falsely attributed to this Save. |
| Draft conflict | Settle the original conflict, retain still-authorized foreground edits, disable resubmission until explicit Reload; confirm dirty replacement. Reload failure preserves foreground edits; successful reload establishes a new revision/authority, next Save uses a new key. No force overwrite or auto-merge. |
| Source conflict | Settle before another command; retain foreground edits and require explicit fresh picker repair/removal or Reload. No automatic refreshing or resubmitting the old captured payload. Show generic source-unavailable/changed guidance. |
| Cancel/toolbar Back/system Back/tab leave | Clean form leaves; dirty form prompts Leave/Stay. Keyboard-dismiss Back alone does not navigate. Confirmed Leave clears unsubmitted state. Stale queued Save/dialog effects after leave cannot submit. |
| Leave while submitted | Explain that leaving cannot cancel a submitted Save; allow presentation departure without discarding recovery. Account departure remains blocked until durable settlement. |
| Background, process/root loss, known connectivity/authority loss or scope retirement | Clear unsubmitted editor/picker/list/detail state. Visible notice explains unsaved-edit loss in advance. Saved server draft survives; submitted encrypted command survives independently. No unreliable background confirmation or hidden autosave. |
| Foreground/restart | Fresh identity/authority then server reads. Unknown command uses original Retry/confirmed Resolve in existing recovery; resolution does not resurrect form text or grant access. |

Fence every callback/dialog/effect with owner UID/workspace/role/epoch, current destination, visit, request generation and one-time effect consumption. A late error/timer cannot turn a completed conflict into unknown. Same-source names are synthetic-only; no client notes or hidden personal content. No payloads in logs/analytics/crash reports, clipboard automation or navigation persistence.

## Implementation and verification sequence

Only run these after P1–P6 approval and separate P7 implementation authorization:

1. **Freeze contracts and proofs.** Reconcile affected future schema/security/offline/navigation rows and extend ADR-002 only within approved policy. Implement canonical JSON/hash/null vectors and actual Kotlin serializers first. Reproduce the analysis maxima and actual existing-envelope admission on both platforms; resolve any mismatch before issuance. Prove all ten existing variants remain compatible.
2. **Implement trusted Save/read boundary.** Exact validators, source projection/fingerprints, five-document authority, fixed queries/admission, same-root revision transaction, minimal receipt/whole-key fence adapter. Keep direct Rules denials; demonstrate query/index needs without broad grants. Audit coverage/provenance and every writer before enabling the new kind. No production fixture/import assumptions.
3. **Integrate existing ledger/native boundary.** Add one typed payload/decision/kind/marker adapter and fixed emulator routing; preserve old serialization/hashes. Native protected admission must check actual record and persist under UID/generation ownership before transport. Verify delayed issuance, freeze, proof/retirement and startup validation through actual Android and Swift storage.
4. **Build bounded product UI.** Programs list/current draft/form, existing system/custom picker, add/remove/move controls, minimal prescription fields, explicit Save/Cancel/Back, truthful pending/source/draft conflict states. Follow current design tokens/MVI/navigation; no Publish/Assign/version/archive controls. Obtain fresh frames if the connector becomes available.
5. **Verify and attribute evidence.** Appropriate backend/Rules/shared checks, native process/restart/departure matrix, Android/iOS Debug/Release builds and Release harness isolation in the implementation task. Actual UI interaction/accessibility is a separate acceptance track. Preserve failed attempts and source fingerprints; no planning-time application builds.
6. **Review/rollback report.** Complete diff/security/budget review, exact file inventory, fresh/retained/failed/unavailable evidence, inherited gates. Rollback may hide new UI/issuance while retaining safe readers or locks, consumed-key guards, receipts/fences, history and provenance. Do not downgrade a schema, strip fields or delete pending records to run an older build.

### Security, concurrency and lifecycle matrix

Test direct template/descendant/receipt/rate/config get/list/write denial, forged owner/workspace/body fields, foreign vs missing targets, own collision, other-author and owner-override attempts, client role, missing/malformed profile/directory, unverified/anonymous/disabled Auth, mismatched global email and schema. Preserve existing system/custom authorization and assignment-source client denials.

Test empty/incomplete forms, all numerical boundaries/nulls/raw invalid forms, Unicode and escape vectors, combined text/count caps, invalid/duplicate IDs, item movement across workouts, simultaneous removal/reorder, no stale child content, max saved/read/list shapes and malformed lookahead. Listing must be bounded, deduplicated, restartable, scope-fenced and never cache-backed. Exercise source fingerprints over normalized-name/array changes, custom revision changes, timestamp-only changes, missing/malformed/disallowed source and full-name copy; names must not be truncated to pass combined caps.

Use transaction barriers for two devices saving one revision, create collisions, source correction between picker and Save, source correction while Save transaction is paused, unchanged saved items after source loss, item replacement after loss and removal with zero source reads. Race account/membership/workspace revision changes and suspend/independent restore against Save/replay. Reads of source/authority documents must participate in transaction retries; current global Auth checks do not make external Auth-disable and Firestore commits one atomic system. Record that residual boundary and fail closed at subsequent checks.

Exercise exact retry, changed key/kind/hash/content/array order, old/future/ceiling revisions, wrong response type, source conflict at revision 0, timestamp regression, generic errors, quotas and final deadlines. Commit-first/resolver-first, paused original after receipt read, fence-before-delayed-send and lost applied/conflict/fence responses must yield one decision/effect. Missing legacy history, damaged coverage, absent original acceptance binding and unsupported generations remain locks, not successes. No retry can restore a revoked source or authority.

### Native restart, compatibility and A/B isolation

For **each of the ten existing variants** retain original descriptor/hash/receipt/parser behavior, legacy read-or-lock rules and correct terminal form. Exercise shared serializer/strict decoder, pending/full-proof/retired states, duplicate/delayed callbacks and consumed-key contradictions; add regression evidence for changed native dispatch/storage paths, not only draft tests. Notes keep note-specific policy; custom create and correct remain two distinct cases. Do not compact seven historical full-proof variants as incidental cleanup.

For new Save test both create and update on actual Android official SDK/encrypted file and actual Swift Firebase/Keychain/CryptoKit, with real process terminate/relaunch: before prepare acknowledgment, durable prepare before send, server commit/lost response, both durable conflict forms/lost response, resolver-first fence, proof written before retirement, injected proof-write/retirement failure, already compacted cold start. Include largest payload plus each retained history class, oversize refusal leaving storage byte-identical, corrupt/unknown schema/UID/key/marker locks and original authority restoration failure. Verify exact original bytes/UID/key/revisions and absence of text/source identities in settled markers.

Freeze/late transport/native completion, background/root retirement, online reconnect, known authority loss and clean A→B switch must prove no new command after freeze and no old content/callback/proof leakage. Test dirty form loss separately from acknowledged server-draft persistence and submitted-command recovery. SDK network toggles/model lifecycle injection are not controlled OS-event evidence. Kotlin/Native unit tests are not Swift SDK tests.

Use create-only synthetic fixtures and fresh distinct command keys; preserve existing PR 1/PR 2/M5 device partitions, datasets, original pending records, keys, coverage and complete Auth/Firestore/Storage history. Finish reset-oriented suites before a single preserved native supervisor; export/check history before shutdown. No rekey/reset/reseed to manufacture recovery. Keep credentials, raw exports and private logs outside the diff.

### Actual interaction and design checks

Read [navigation](../navigation.md) and [design inventory](../design-inventory.md#section-to-product-map). Relevant Programs frames include `R1fmB`, `ESl4e`, `TSSam`, `K2ved`, `ZpoAR`, `zQwo8`, `ebdKd`, `r9dwqa`, `fWI3J`, `sAv0c`, `W1Imbf`, `ziDYW`, `HNGLy`, `mGklx`, canonical builder **`G7StO`**; exercise picker references are `LDmjm`, `Easmv`, `PdFjC`, `GoLR8`, `qRujb`, `mr1jb`. These IDs/visual language are retained inventory evidence. On this task Pencil initially reported no open file; after the user opened it, active-file/read-skill attempts still reported no open file and the explicit repository-path lookup reported the wrong-file error. **No fresh frame read/render succeeded and no design changed.** Do not relabel those attempts as visual acceptance.

On both final native hosts use real touches/keyboard/gestures, not just state injection, for:

- Programs list/empty/loading/error/current draft, actual system/custom picker paging and detail, select/cancel/reopen, changed/unavailable source guidance and explicit replacement.
- Title/description/workout entry, numeric input/null clearing/invalid decimals, software keyboard Next/Done/newline, focus/caret/selection, long paste and combined-limit errors. Reach Save/Cancel and lower items while keyboard is visible.
- Add/remove workouts/items, remove nonempty confirmation, Move up/down/to-workout and optional drag, position announcements and focus after move/remove. Test first/last/empty and maximum list; retain stable IDs after movement.
- Actual Save, durable success and fresh reopen, duplicate taps, Cancel/toolbar/system Back/tab Leave/Stay, keyboard dismiss, stale queued Save after confirmed leave, actual background/foreground and process loss.
- Two-session revision conflict and picker-source conflict through real UI, terminal settlement, disabled resubmission, explicit Reload/repair, failed reload preserving authorized foreground input and later successful new Save.
- Scrolling the full maximum form, pagination and bottom controls at normal/200% Android font size and **OS** iOS Dynamic Type; keyboard/safe-area reachability, localization expansion and reduced motion. Controlled Compose scaling is separately labeled.
- TalkBack and VoiceOver: logical traversal/headings, names/roles/units, source labels, stable reorder focus and announcements, field errors/status live regions, selected picker row, disabled/busy Save, conflict/confirmation focus and focus restoration. AX inspection is not a screen-reader run. Preserve visible-44/effective-48 target decision and existing font/icon qualifications.

Builds, backend/shared tests, native recovery harnesses and screenshots do **not** establish these interaction passes. Record exact build/source, host/OS, stimulus, observed result and gaps; a later fix cannot inherit earlier evidence without qualification.

## Remaining gates and handoff

This plan is feasible within existing recovery budgets **for the proposed name-only, bounded draft**, with actual serializer/provenance/native integration still to prove. Required decisions are P1–P6, particularly source-name custody, omitted instruction copies, 650 combined scalars/three workouts/twelve items, minimal metric prescriptions, unsaved-edit loss and draft marker extension, followed by explicit P7 authorization. Rejecting the reduced copy policy requires choosing/recalculating an alternative before implementation.

Technical prerequisites: actual TypeScript/Kotlin/null/hash vector agreement, complete real-envelope admission and native encryption sizing, bounded query/document/transaction measurements, source/authority transactional race proof, exact receipt/fence coverage for eleven total kinds, compatible pending/settled decoding, both native restart/retirement/A–B checks and actual UI/accessibility evidence. PR 4 complete-copy publication feasibility is unresolved; M7 assignment integration/assignment-index Rules limitation remains outside this slice.

Retain [PR 1 interaction gaps](milestone-6-pr1-report.md#actual-interaction-and-remaining-acceptance), [PR 2 partial/failed interaction evidence](milestone-6-pr2-report.md#actual-interaction-and-remaining-acceptance) and [M5 H1–H11](milestone-5-closeout.md#remaining-gates-and-dependencies), including the inherited Swift worst-case sizing caveat above, M2/M4 accessibility, OS-event/physical/shared-device checks, identity/storage/history locks, privacy/retention/export/deletion, real catalog rights/content, production environment/IAM/indexes/keys/signing and offline/media gates. Merged PRs are not full acceptance. No real-data collection, production initialization/deployment, offline grants or support override is approved.

Planning verification: repository/PR inspection; the documentation-only sizing calculation and standalone Foundation JSON-escaping probe; full documentation diff/terminology review; all 98 local file/anchor links and all four changed files checked for whitespace, conflict markers and final newlines. `git diff --check` passes. No application/backend/Rules/index/dependency/design implementation or application build, commit, push, PR or deployment is part of this task. Leave all changes uncommitted and unstaged for decision.

## Approval and implementation authorization (2026-10-28)

Josip approved P1–P6 and separately authorized P7 for the exact synthetic, emulator-only slice in this plan, including the stricter new-draft admission ceiling subject to actual serializer and native proof. This supersedes the earlier approval-pending statements only for that scope. Complete the feasibility checks before enabling Save; preserve all ten existing protected variants and their recovery behavior. Publication, assignment, real data, production and offline remain unapproved. The authorized planning commit contains only the four planning files, followed by the implementation branch; implementation changes remain uncommitted and unstaged. No push, PR, merge or deployment is authorized.

Final-review authorization (2026-10-29): Josip authorized bounded review and in-scope corrections, one implementation commit, normal push and a PR against main. This supersedes earlier uncommitted/no-push handoff instructions only for PR 3. The [report](milestone-6-pr3-report.md#final-review-and-publication-authorization-2026-10-29) preserves verification attribution and remaining gates. No merge, deployment, production/offline enablement or PR 4 authorization.
