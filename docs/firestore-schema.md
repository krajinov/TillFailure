# Firestore schema proposal

Status: **proposed product schema with Milestone 3 emulator primitives; no cloud resources created**
Review date: **2026-09-20**

## Conventions

- Every workspace-scoped path is tenant-isolated by `workspaceId`. Immutable owner/tenant IDs are repeated where needed for rules and collection-group queries, but clients may never change them.
- IDs are stable, client-generated random UUID strings for offline-safe records. Trusted commands carry a unique `idempotencyKey`; authorization records, booking locks, and command receipts use the deterministic paths below instead of random IDs.
- Every mutable document has `schemaVersion`, `createdAt`, `createdBy`, `updatedAt`, and `updatedBy`; server-owned records use server timestamps. Archival uses `archivedAt`/`archivedBy`. `deletedAt` is reserved for recoverable lifecycle workflows.
- Large or repeated records use subcollections. Exercise sequences, logged sets, messages, notifications, tokens, and photos never accumulate in unbounded arrays.
- Domain models and UI state do not expose `DocumentSnapshot`, server timestamp sentinels, or other Firebase types.
- Examples omit routine audit fields when the table already specifies them. Values are fictitious.
- Per-device transport state, offline eligibility, workout download manifests, active-session recovery snapshots, mutation journals, upload registries, and account-switch markers are app-owned local records defined canonically in [offline-sync.md](offline-sync.md); they are not shared Firestore business fields.

## Identity and tenancy

| Collection/path and ID | Ownership and required fields | Mutability and lifecycle | Queries, indexes, growth, offline behavior |
|---|---|---|---|
| `users/{uid}` (Firebase UID) | User-owned global account: `displayName`, `emailNormalized`, `photoAssetId?`, `locale`, `timeZoneId`, `accountStatus`, server-owned `lifecycleRevision` | UID, normalized email, status and lifecycle revision are protected; user may edit safe profile/preferences. Account enable/disable is a revision-checked, receipt-backed command (`transitionAccountLifecycle`): the caller names an explicit `expectedLifecycleRevision`, the stored value must be an integer equal to it, and a successful transition advances it exactly once with the entitlement in the same transaction, so a delayed or replayed command cannot undo a newer security decision. Enabling additionally requires `schemaVersion == 1` on both the account and the fixed entitlement — the version the Firestore Rules authorize — so a missing, malformed, or unsupported version fails closed with no silent migration; disabling deliberately skips that check so access can always be revoked. Retain a minimal tombstone during trusted deletion, then apply retention policy. | Direct lookup. Never search clients by private email from ordinary clients. App-owned local records are UID-partitioned; Firebase cache cleanup follows `offline-sync.md` and is not isolated by DI scope. |
| `users/{uid}/authorizations/systemCatalog` (fixed ID) | Server-owned entitlement: `status` (`active`/`inactive`), integer `activeMembershipCount`, `revision`, plus schema/audit fields | Bootstrap inactive with count zero. Every membership/account/workspace lifecycle operation maintains it atomically as specified in [security](firestore-security.md#system-catalog-entitlement-lifecycle). No client writes. Account disable always writes `inactive` without touching the stored count (malformed forensic state is preserved); account enable requires the stored count to be an actual integer within `0..maxMembershipsPerAccount` and requires this document to remain on `schemaVersion == 1` before either document changes. Every path that would publish `status == "active"` (enable, membership activation, workspace restoration) requires the current schema on the account and entitlement; deactivation paths never do. | Rules point-read this exact UID-derived path and `users/{uid}`. Count is bounded by validated `maxMembershipsPerAccount`; only global catalog access is authorized. Cached state grants no authority. |
| `lifecycleCommands/{commandId}` | Server-owned receipt: caller/service identity, target path, command kind and version, `requestHash`, committed source revision/result, schema/audit fields | ID is SHA-256 of canonically encoded caller/service identity, target, and idempotency key. Immutable successful receipt written in the source/entitlement transaction. Command kinds are `membership-transition`, `workspace-transition`, and `account-lifecycle`; account-lifecycle receipts additionally record the caller UID, requested enabled/disabled state, expected lifecycle revision, and the stored entitlement count for audit. Requests include expected source revision; changed payload/key reuse fails. | Trusted point lookup only. Preserve through the supported retry lifetime; expired keys cannot be accepted as new commands. No mobile access. |
| `workspaces/{workspaceId}` | Trainer-owned tenant: `name`, `ownerUid`, `status`, server-owned `membershipRevision` plus two distinct integer counters: `activeRosterCount` (active relationships) and `catalogContributionCount` (catalog contributions, always a subset of the roster) | `ownerUid` immutable; server-controlled ownership transfer/status. Every membership transition increments `membershipRevision` and maintains both counters in the same transaction: activating an active membership increments the roster in an active **or** suspended workspace, while contributions change only with workspace status. Activation is serialized through the expected workspace revision and bounded by `maxMembershipsPerWorkspace` against the roster. Suspension preserves the roster and zeroes contributions without removing active relationships; restoration re-enables contributions for valid active memberships from a bounded authoritative scan and fails closed when the stored counters disagree with that scan. Archive before deletion. Catalog contributions may only be added while the workspace is schema-current and in a recognized lifecycle state (`active`/`suspended`); an active-but-damaged or unrecognized workspace cannot raise an entitlement, while removals and neutral changes remain possible. Production rollout requires controlled initialization/backfill of existing workspace documents before the invariant is enabled; the Milestone 3 spike has no production data and therefore no migration. | Direct lookup through membership IDs; low growth. The revision serializes roster/lifecycle changes; the counters are trusted bound inputs, never client-writable authorization. This document does not replace membership role checks. |
| `workspaces/{workspaceId}/memberships/{uid}` | One membership per user: `userId`, `role` (`trainer`/`client`), `status`, `joinedAt`, `invitationId?`, `revision`, server-owned `catalogContributionActive`, safe display summary | Tenant/user/role/status/contribution protected; trusted lifecycle sets contribution true exactly when membership and workspace are active. Workspace suspension/restoration scans validate each active membership's `schemaVersion`, stored `workspaceId`, path/`userId` identity, role, boolean contribution, status, and integer revision before deriving any account reference; one malformed member rejects the whole transition atomically. The booking authorization boundary applies the same stored-`workspaceId` and path/`userId` identity requirement to every membership it reads — the caller and both participants, across booking, reschedule, and cancellation — so a path-correct membership naming another tenant can never authorize a command. Each change and its entitlement delta commit together. Revoking one of several contributions, or suspending one workspace while another contribution remains, is a deactivation: it stays possible while the account/entitlement schema is damaged, preserves the stored entitlement status without elevating it, never repairs the schema, and cannot broaden catalog reads while Rules deny the damaged record. A zero contribution delta (revoking an active relationship in a suspended workspace, or re-suspending it) is equally neutral: only an addition may raise the stored status, so a drifted `inactive` entitlement stays inactive. Revoke before archive. | Membership remains authoritative for workspace-scoped access/roles. Server workspace discovery/maintenance may query `userId,status` with a collection-group index; Rules cannot use that query to locate arbitrary membership. Bounded offline rendering is separate. |
| `workspaces/{workspaceId}/trainerProfiles/{uid}` | Active trainer membership: `userId`, public bio/contact preferences | Tenant/user immutable; trainer edits own profile. Archive with membership. | Direct by UID; small. |
| `workspaces/{workspaceId}/clientProfiles/{uid}` | Active client membership: `userId`, `trainerId`, goals/constraints summary, onboarding status | Tenant/user immutable; client edits permitted fields; trainer edits coaching fields defined by field allowlist. Health-related content minimized and separately audited. | Trainer client list: `trainerId,status,displayNameNormalized`; client direct lookup. Offline edits use last-known revision/conflict policy. |
| `invitations/{invitationId}` | **Internal, Admin-SDK-only** record: `workspaceId`, `invitedEmailNormalized` or keyed hash, `role=client`, `invitedBy`, `tokenHash`, `tokenVersion`, `status`, `expiresAt`, `acceptedBy?` | No direct mobile read/write. Trusted create/resend/revoke/accept rotates or consumes token material. Delete token material after expiry/acceptance under retention policy. | Trusted endpoint lookup by token hash/status/expiry. Never rely on Rules to redact fields from this readable document. |
| `workspaces/{workspaceId}/invitationSummaries/{invitationId}` | Trainer-readable projection: `invitationId`, `emailMasked`, `role`, `status`, `expiresAt`, `createdAt`, `lastSentAt`, `acceptedAt?`, `revokedAt?`; no email hash, token, or token hash | Trusted backend creates/updates from the internal record. Active workspace trainer reads; no client writes. Resend/revoke/accept updates visible status/timestamps. | Trainer list by `status,createdAt desc`. Low growth; archive/TTL only after audit/retention policy. Cached projection is display state, not acceptance authority. |

Example membership:

```json
{
  "schemaVersion": 1,
  "workspaceId": "ws_demo",
  "userId": "uid_client",
  "role": "client",
  "status": "active",
  "revision": 1,
  "catalogContributionActive": true,
  "joinedAt": "server timestamp",
  "invitationId": "inv_demo"
}
```

### Invitation lifecycle and read boundary

The internal invitation and trainer projection are a trusted dual write. Mobile clients never read `invitations/{id}` directly because [Firestore reads are document-level and Rules cannot hide selected fields](https://firebase.google.com/docs/firestore/security/rules-fields#allowing_read_access_only_for_specific_fields). Trainer list/detail reads only `workspaces/{wid}/invitationSummaries/{id}`. Token preview/acceptance calls a trusted endpoint that returns an allowlisted DTO after rate limiting and token validation; it never returns the stored hash.

Create generates a high-entropy token, stores only its hash/version internally, and sends the raw token through the approved delivery channel. Acceptance requires an authenticated account, unexpired `pending` record, matching normalized email/account policy, active inviter/workspace, and unused token; the transaction creates membership/profile, applies the catalog entitlement contribution, increments the workspace membership revision, marks internal status accepted, invalidates token material, and updates the summary. Resend rotates token/version and expiry so old links fail, then updates `lastSentAt`/status. Revoke invalidates token material and updates both records. All trusted mutations are idempotent; display-summary repair may retry, but membership and entitlement must never depend on delayed projection delivery. The internal invitation remains acceptance authority.

### System catalog entitlement shape

`activeMembershipCount` counts this UID's catalog contributions (active memberships in active workspaces), independently of account status. `status` is `active` only when that count is positive **and** `users/{uid}.accountStatus == "active"`; otherwise it is `inactive`. This permits immediate account suspension without losing the count needed for safe restoration. `catalogContributionActive` on each membership records its contribution for idempotent before/after deltas, not a client-supplied permission. Every trusted count mutation validates the existing value as an integer in `0..maxMembershipsPerAccount` before applying a delta, then independently validates the computed value against the same range before any write; malformed or already-out-of-range source state blocks both suspension and restoration rather than being normalized into an authorized value. The account entitlement count is contribution-based and must not be confused with the workspace's `activeRosterCount`, which counts active relationships even while the workspace is suspended; the workspace's `catalogContributionCount` is the workspace-side contribution subset that mirrors the account deltas. The authoritative lifecycle, bounds, and failure behavior are in [firestore-security.md](firestore-security.md#system-catalog-entitlement-lifecycle).

## Exercises and programming

| Collection/path and ID | Ownership and required fields | Mutability and lifecycle | Queries, indexes, denormalization, offline behavior |
|---|---|---|---|
| `systemExercises/{exerciseId}` | Global catalog, server/admin-owned: `name`, `nameNormalized`, `muscleGroups`, `equipment`, `instructions`, `mediaRef?`, `status` (`draft`/`published`/`archived`) | Eligible authenticated accounts read only `published` entries; archive transitions status to `archived` and sets audit fields. No client writes; immutable ID. Retain referenced exercise snapshots. | Rules require active account plus the fixed `systemCatalog` entitlement. Queries include `status == "published"`; Rules do not filter unsafe results. Other search/filter indexes are product-dependent; cache is display-only. |
| `workspaces/{workspaceId}/exercises/{exerciseId}` | Trainer custom exercise: `ownerUid`, same descriptive fields as system exercise, workspace `status` (`active`/`archived`) | Tenant/owner immutable; trainers create/update/archive. Never hard-delete while referenced. | `status,nameNormalized`; optional `equipment,status`. Offline create/edit queued; conflicts surface for simultaneous edits. |
| `.../programTemplates/{templateId}` | Trainer-owned logical template: `ownerUid`, `title`, `description?`, `currentVersionId`, `status` | Identity/owner immutable; metadata/current pointer mutable. Archive without touching versions or assignments. | Trainer list: `ownerUid,status,updatedAt desc`. Offline draft editing allowed; publishing requires version transaction. |
| `.../programTemplates/{templateId}/versions/{versionId}` | Immutable published source: `versionNumber`, `title`, `weeksCount`, `publishedAt`, `publishedBy`, `contentHash` | Published content is immutable. Draft storage remains open. | Trainer/workspace-authorized only, including every descendant; clients cannot read even an assigned source version. Trusted assignment copies allowlisted content. |
| `.../programTemplates/{templateId}/versions/{versionId}/workouts/{workoutId}` and `/exercises/{itemId}` | Ordered immutable source workout/exercise: title/day, `position`, exercise reference and display/prescription fields | Immutable once published. Deletion only with unused draft/version cleanup. | Trainer-only ordered queries. These are never client download paths. |
| `users/{uid}/workspaces/{wid}/assignedPrograms/{aid}` | Account-owned, server-written assignment header: `clientId=uid`, `workspaceId=wid`, `assignmentId=aid`, `trainerId`, `sourceTemplateId`, `sourceVersionId`, `sourceVersionNumber`, `sourceContentHash`, `snapshotId=content`, `snapshotHash`, `revision`, `lifecycleState=ready`, `accessStatus`, `accessExpiresAt`, `startDate`, `timeZoneId`; replaced predecessors additionally record `replacedByAssignmentId` and `replacedAt` | Identity/source/content hash immutable; trusted revisioned scheduling and access transitions only. Replacement requires the request's expected predecessor revision and retires the header with `accessStatus=replaced`, `lifecycleState=terminal`, `replacedByAssignmentId` and the advanced revision atomically with its discovery index. Closing requires the caller-supplied expected live revision and an exact `ready`/`active` unexpired state plus a matching discovery index; an already terminal or stale header is rejected without rewriting its terminal reason, replacement metadata, or revision. Header contains no prescriptions, notes or private audit payload. The account-owned header collection lists under the path-bound owner plus the active account/workspace/membership gates, relying on the trusted assignment lifecycle being its only writer and always binding the header identity to that path; single-document reads still prove the identity tuple from the document. Every ID that becomes part of an assignment path (user, workspace, assignment, predecessor assignment, snapshot workout, snapshot exercise, plan) is validated as exactly one document segment before any reference is built, so a path separator is rejected instead of materializing a nested document. | Exact account/workspace collection lists safe headers; content requires the additional assignment gate below. Parent `users/{uid}/workspaces/{wid}` is path context, not a grant. |
| `users/{uid}/workspaces/{wid}/assignedPrograms/{aid}/snapshots/content` and `/workouts/{workoutId}/exercises/{itemId}` (with workout header docs) | Client-safe immutable copy: identity tuple, `snapshotId`, `schemaVersion`, `contentHash`, source IDs/version/hash for provenance; title, ordered workouts, copied exercise names/instructions/prescriptions | Materialized only by trusted assignment operation; never edited after publication. Trainer-only notes, internal annotations and non-client-visible metadata excluded by allowlist, not field redaction. | Direct reads and bounded child queries under one assignment. No dependency on reading original templates, source items or required exercise definitions. |
| `users/{uid}/workspaces/{wid}/assignedPrograms/{aid}/plannedWorkouts/{plannedWorkoutId}` | Identity tuple, `snapshotId=content`, `workoutId`, `scheduledLocalDate`, `scheduledInstant`, `timeZoneId`, `status`, `revision` | Trusted materializer owns all writes. Snapshot/workout refs immutable; schedule/status changes advance assignment revision with manifest. Publication validates before the transaction that every `workoutId` references exactly one workout being materialized into the snapshot; blank or duplicated snapshot workout IDs and blank/dangling plan references reject the whole publication, so no ready assignment or complete-looking manifest can contain an unresolvable plan. | Upcoming query within this assignment: `status,scheduledInstant`. References resolve only inside the same account/assignment snapshot. |
| `users/{uid}/workspaces/{wid}/assignedPrograms/{aid}/manifests/download` | Server-owned bounded inventory: identity tuple, `assignmentRevision`, snapshot hash, sorted duplicate-free `requiredPaths` containing the snapshot content and every created `plannedWorkouts/{id}` path for the current horizon | Trusted creation/update atomic with planning records; never client-authored or an authorization grant. | Client reads through assignment gate, then probes every cached path/revision; inventory presence is not cache completeness. Distinct from the app-owned local manifest in [offline-sync.md](offline-sync.md). |
| `workspaces/{wid}/assignedPrograms/{aid}` and `workspaces/{wid}/plannedWorkouts/{plannedWorkoutId}` | Trainer-only discovery indexes: `workspaceId`, `clientId`, `trainerId`, `assignmentId`, canonical account-owned target IDs, lifecycle/schedule summary and revision; replaced assignment indexes record `status=replaced`, `replacedByAssignmentId` and `replacedAt` | Trusted writes in the same assignment transaction; no duplicate authority and no client reads. The assignment index revision tracks its account-owned header revision; replacement validates and retires both representations in one commit, so trainer discovery never keeps returning a replaced predecessor as active. IDs derived from assignment and workout occurrence for retry stability. | Trainer queries by `trainerId,status,updatedAt desc` or `trainerId,status,scheduledInstant`. Client discovery uses account-owned headers, never these indexes. |

### Assigned-program snapshot identity

Selected product architecture (isolated Milestone 3 primitive implemented; product flow not implemented): for assignment `asg_demo`, the canonical header is `users/uid_client/workspaces/ws_demo/assignedPrograms/asg_demo`, and its one immutable snapshot is `users/uid_client/workspaces/ws_demo/assignedPrograms/asg_demo/snapshots/content`. A workout item is `users/uid_client/workspaces/ws_demo/assignedPrograms/asg_demo/snapshots/content/workouts/day1/exercises/item_squat`. The assignment ID may remain random: every requested descendant already contains it, so Rules derive its parent directly without searching by template/version. New content or reassignment uses a new assignment ID and new fixed `content` snapshot; IDs are never reused.

Example canonical assignment header (timestamp strings illustrate Firestore Timestamp fields):

```json
{
  "schemaVersion": 1,
  "workspaceId": "ws_demo",
  "clientId": "uid_client",
  "assignmentId": "asg_demo",
  "trainerId": "uid_trainer",
  "sourceTemplateId": "tpl_strength",
  "sourceVersionId": "v3",
  "sourceVersionNumber": 3,
  "sourceContentHash": "sha256:source-example",
  "snapshotId": "content",
  "snapshotHash": "sha256:client-safe-example",
  "revision": 1,
  "lifecycleState": "ready",
  "accessStatus": "active",
  "accessExpiresAt": "2026-10-07T00:00:00Z",
  "startDate": "2026-09-07",
  "timeZoneId": "Europe/Sarajevo"
}
```

Every snapshot/planned-workout/manifest document repeats the immutable `workspaceId`, `clientId`, `assignmentId`, and `snapshotId` tuple. Source identifiers are audit/reconciliation and request-hash inputs only; knowledge of them grants no source access. The [direct authorization checks and lifecycle](firestore-security.md#assigned-program-authorization-and-lifecycle) are canonical. Copy required exercise text/prescriptions; optional media is included only if independently authorized for the same client, never by copying a private trainer URL or treating a source ID as a Storage grant.

## Workout, progress, and private coaching data

| Collection/path and ID | Ownership and required fields | Mutability and lifecycle | Queries, indexes, denormalization, offline behavior |
|---|---|---|---|
| `.../workoutSessions/{sessionId}` | Client-created server business record: `clientId`, `trainerId`, `assignmentId?`, `plannedWorkoutId?`, account-owned snapshot/workout IDs, `startedAtClient`, `status`, `revision`, `lastOperationId`, `completedAt?` | Tenant/client/plan refs immutable. Assigned-session creation and edits require the current assignment gate; retained completed history follows its separate ownership/retention policy, never granting snapshot/source access. `completed` means server accepted, while local completion intent remains device-local. | Client history: `clientId,status,completedAt desc`; trainer queue: `trainerId,reviewStatus,completedAt desc`. No shared `syncState`; local transport is defined in `offline-sync.md`. |
| `.../workoutSessions/{sessionId}/loggedSets/{setId}` | Session/client: `exerciseItemId`, `setPosition`, raw values, normalized values, `completionStatus`, `clientEditedAt`, `revision`, `baseRevision`, `lastOperationId` | Owner refs and identity immutable; Rules verify revision transition and edit policy. Trainer feedback is separate/allowlisted. Tombstone deleted sets. | Ordered by `exercisePosition,setPosition`. High but bounded per session. Rejected payload survives in the app-owned mutation journal; Firestore stores only server-accepted business state. |
| `.../workoutSessions/{sessionId}/feedback/{feedbackId}` | Trainer-to-client review: `trainerId`, `clientId`, `body`, `visibility=client` | Trainer creates/edits under audit; archive rather than silent removal after client viewed. | Ordered by `createdAt`; low growth. Explicitly distinct from private notes. |
| `.../progressEntries/{entryId}` | Client: `clientId`, `type`, `recordedAt`, validated measurement payload, `note?`, `mediaAssetIds?` | Tenant/client immutable; client creates/edits; trainer may read and comment only if product permits. Use bounded media IDs or an asset subcollection. | `clientId,type,recordedAt desc`. Offline text/measurement queued; media separately pending. Conflicts preserve both versions for review. |
| `.../privateTrainerNotes/{noteId}` | Trainer-only: `trainerId`, `subjectClientId`, `body` | Only active trainers may CRUD; never stored inside a client-readable profile/session. Archive/delete per explicit retention policy. | `trainerId,subjectClientId,updatedAt desc`. Offline support is a product/privacy decision; default online/cached read with encrypted device protections. |

Example session and set:

```json
{
  "session": {
    "schemaVersion": 1,
    "workspaceId": "ws_demo",
    "clientId": "uid_client",
    "trainerId": "uid_trainer",
    "plannedWorkoutId": "pw_20260907",
    "assignmentId": "asg_demo",
    "snapshotId": "content",
    "workoutId": "day1",
    "status": "in_progress",
    "revision": 4
  },
  "set": {
    "exerciseItemId": "item_squat",
    "setPosition": 1,
    "weightText": "80",
    "weightKg": 80.0,
    "repsText": "8",
    "reps": 8,
    "completionStatus": "completed",
    "baseRevision": 3
  }
}
```

## Scheduling

| Collection/path and ID | Ownership and required fields | Mutability and lifecycle | Queries, indexes, denormalization, offline behavior |
|---|---|---|---|
| `workspaces/{workspaceId}/schedulingPolicies/{trainerId}` | Scheduling policy: `slotQuantumMinutes`, `maxDurationMinutes`, `maxBufferBeforeMinutes`, `maxBufferAfterMinutes`, booking-window settings, `scheduleRevision`, audit/schema fields | Trusted configuration; quantum fixed for the workspace once scheduling is enabled. Trainer availability/block edits must atomically increment only `scheduleRevision` under Rules. | Deterministic read by booking transactions; revision changes invalidate concurrent rule/block expansion, including an initially empty block query. No block reasons or client data. |
| `.../availabilityRules/{ruleId}` | Trainer: weekday/recurrence rule, local start/end, `timeZoneId`, effective dates, buffers | Trainer-owned, revisioned; publish/update/archive atomically advances the scheduling policy revision. Store IANA zone ID, not only offset. | Trainer/effective date; bounded expansion. Editing affects future booking decisions, never silently rewrites confirmed appointments/locks. |
| `.../blockedPeriods/{blockId}` | Trainer: `startsAt`, `endsAt`, `timeZoneId`, `reasonCategory?` | Trainer-owned; validated interval and atomic scheduling policy revision change on create/update/delete/archive. | `trainerId,startsAt,endsAt`; bounded range query. Offline draft allowed, publication online. |
| `.../appointments/{appointmentId}` | Workspace/client/trainer: UTC instants, `timeZoneId`, local display metadata, duration, pre/post buffers, `slotQuantumMinutes`, `firstUtcBucket`, `slotBucketCount`, `status`, original `idempotencyKey`, `revision` | Server owns commands and stored lock-range descriptor; trainer/client identity is immutable. Reschedule validates the stored appointment before calculating/replacing buckets; mismatch rejects before any lock change. Cancel atomically releases locks. Stored `status` is the live `confirmed` state or the recognized terminal `cancelled` transition; trusted lock cleanup releases leftover locks only for a schema-current, tenant-matching appointment whose stored status is explicitly one of the recognized terminal states and fails closed for every missing, malformed, unknown, or transient value. Keep history/audit. | Client: `clientId,startsAt`; trainer: `trainerId,startsAt`; reminder worker: `status,reminderAt`. No direct mobile mutations. |
| `workspaces/{workspaceId}/bookingSlots/{trainerId}_{utcBucket}` | Server-owned occupied bucket: `workspaceId`, `trainerId`, `appointmentId`, integer `utcBucket` (bucket-start epoch minute), `appointmentRevision`, schema/audit fields | ID encodes a canonical path-safe trainer ID and decimal bucket-start epoch minute. `utcBucket` is derived only from the validated bucket calculation, never from client input, and is immutable for the lock's lifetime: initial booking and every reschedule write retained and newly acquired locks with identical fields. Held while the appointment is `confirmed`; released only in the appointment transition transaction or by trusted repair for an appointment whose stored status is explicitly a recognized terminal state and whose stored tenant identity matches the workspace. No client writes or TTL. | Read exact deterministic paths, including absent documents. Each appointment owns a bounded contiguous range; document contains no client, reason, or private appointment payload. |
| `workspaces/{workspaceId}/bookingCommands/{commandId}` | Server-owned receipt: `callerUid`, `commandKind`, `requestHash`, `appointmentId`, committed `result` (status/revision), schema/audit fields | `commandId = SHA-256(canonical encoding of callerUid + idempotencyKey)`, scoped by workspace. Same key with different payload/kind is rejected; successful receipt is immutable. The authorized caller identity is stored on the receipt and bound into `requestHash`, so a different authenticated account has a distinct command identity and can neither replay nor observe another caller's operation. | Point lookup by trusted endpoint only. Created with appointment/lock mutations. Retain for the supported retry lifetime; do not expire and silently accept the same key again. |

### Deterministic bucket coverage and bounds

Explicit local-time/DST resolution remains a planning mechanism that is not yet emulator-proven; the Milestone 3 primitives implement and prove the half-open UTC bucket coverage below. For one trainer in one workspace, validate explicit local-time/DST resolution first, then use half-open UTC intervals throughout. With quantum `q = slotQuantumMinutes` and `Q = q * 60` seconds, the buffered interval is `[startsAt - preBuffer, endsAt + postBuffer)`. Read/write every bucket from `floor(bufferedStartEpochSeconds / Q)` through `ceil(bufferedEndEpochSeconds / Q) - 1`, using the bucket's UTC start epoch minute as `utcBucket`. Every lock document stores that `utcBucket`, and rescheduling rewrites retained and acquired locks with the same fields initial booking wrote. Thus any positive overlap shares at least one deterministic document even when both requests see no existing appointments. An empty appointment query is not the contention mechanism.

The supported policy set is `q in {1, 5, 10, 15}` minutes; product must choose before scheduling is enabled. Starts, durations, and buffers may be unaligned: coverage always rounds outward without silently changing the displayed appointment time. This can reject nearby non-overlapping intervals in the same bucket; that conservative behavior needs product acceptance. All commands for the workspace use the same grid. Changing quantum after enablement is unsupported in MVP; a later migration must preserve all live lock coverage before allowing new bookings. Random lock IDs or quantum/version-specific parallel grids are forbidden.

Deployment policy must specify positive maximum duration, nonnegative maximum buffers, a bounded booking horizon, and bounded availability/block query results. Validate them before opening a transaction and recheck the authoritative policy and stored old range within it. A range requires `N = ceil(bufferedEnd / Q) - floor(bufferedStart / Q)` buckets. Proposed engineering ceilings are **90 buckets per appointment**, **200 document writes per transaction**, and **1 MiB of conservatively estimated changed document/index data**, lowered if the selected backend SDK/API imposes stricter limits. These are safety budgets, not final session durations. Reject unsupported/oversized requests before starting; if authoritative data changes the calculation during retry, abort before any write.

For booking, budget `N + 2 + A` writes (locks, appointment, receipt, and any additional audit/outbox writes `A`). For rescheduling, budget `|old union new| + 2 + A`, at most `180 + 2 + A`; retain shared buckets and release only old-only ones. Cancellation uses `|old| + 2 + A`. Read both old/new sets before writes, include deletes and index costs, and never split one command across transactions. Keep documents/queries bounded so the [Firestore request/time/transform limits](https://firebase.google.com/docs/firestore/quotas#writes_and_transactions) and [transaction failure constraints](https://firebase.google.com/docs/firestore/manage-data/transactions#transaction_failure) are respected. Verify actual SDK write accounting during Milestone 3; the published 500-per-document transform limit is not a general 500-document transaction guarantee.

The trusted acquisition, reschedule, cancellation, and repair protocol is owned by [firestore-security.md](firestore-security.md#booking-transaction-protocol). Example durations below are illustrative values, not product defaults; real appointment documents also persist the computed lock-range descriptor.

Example appointment:

```json
{
  "schemaVersion": 1,
  "workspaceId": "ws_demo",
  "trainerId": "uid_trainer",
  "clientId": "uid_client",
  "startsAt": "2026-10-25T09:00:00Z",
  "endsAt": "2026-10-25T10:00:00Z",
  "timeZoneId": "Europe/Sarajevo",
  "durationMinutes": 60,
  "bufferBeforeMinutes": 10,
  "bufferAfterMinutes": 10,
  "status": "confirmed",
  "idempotencyKey": "book_random_uuid",
  "revision": 1
}
```

## Communication and delivery

| Collection/path and ID | Ownership and required fields | Mutability and lifecycle | Queries, indexes, denormalization, offline behavior |
|---|---|---|---|
| `.../conversations/{conversationId}` | Fixed participants: `participantUids` (bounded), `trainerId`, `clientId`, `lastMessageSummary`, `lastMessageAt` | Tenant/participants immutable; server or rule-safe message write updates summary. Archive per participant without deleting the other participant’s history. | `participantUids array-contains + lastMessageAt desc`; validate exactly the allowed trainer/client pair. Cached conversations are account-scoped. |
| `.../conversations/{conversationId}/messages/{messageId}` | Participant-created: `senderId`, `clientCreatedAt`, `serverCreatedAt`, `body?`, `attachmentAssetIds?`, `deliveryState` | Sender/participants immutable. Limited edit/delete policy; tombstone rather than remove ordering evidence. | `serverCreatedAt desc`; paginate. Offline send queues stable message ID; ordering reconciles on server timestamp. No unbounded message array. |
| `users/{uid}/deviceTokens/{tokenId}` | User/device: token ciphertext/reference, `platform`, `installationId`, `lastSeenAt`, `enabled` | User registers own token; trusted delivery disables invalid tokens. Delete on sign-out/account deletion. | Server delivery lookup by user; never client-listable outside owner. Token is sensitive and never logged. |
| `users/{uid}/notifications/{notificationId}` | Recipient: `type`, safe display payload, destination key/IDs, `createdAt`, `readAt?` | Trusted fan-out creates; recipient may mark read/archive. Avoid private message/note content in push payload. | `createdAt desc`, `readAt`; paginated. Offline read-state queued. |

Example message:

```json
{
  "schemaVersion": 1,
  "workspaceId": "ws_demo",
  "conversationId": "conv_demo",
  "senderId": "uid_client",
  "body": "Completed today’s workout.",
  "clientCreatedAt": "2026-09-01T08:15:00Z",
  "serverCreatedAt": "server timestamp",
  "deliveryState": "accepted"
}
```

## Denormalization and update responsibility

- Display summaries may be copied to cards/messages so lists remain cheap; stale summaries are acceptable only where explicitly documented. Assignment headers, inventories and trainer indexes instead commit together through the trusted lifecycle; no delayed projection grants content access.
- Template publishing atomically creates an immutable version and moves `currentVersionId`. Existing assignments, planned workouts, sessions, and history retain their version references/snapshots.
- Assigned content is an immutable account-owned copy, not a client read-through of a source version. New prescriptions require new assignment/snapshot identity; scheduling, eligibility and the bounded server inventory advance atomically without rewriting content.
- Appointment status and occupied deterministic buckets change atomically. Availability queries and notification summaries never replace the locks as contention authority.
- The account's system-catalog entitlement is a trusted, synchronous authorization projection, maintained atomically with lifecycle source changes; unlike display summaries it must not lag. It grants no workspace permissions.
- Conversation `lastMessage*` fields are derived from the accepted message; a trusted trigger is preferred if rules cannot make the multi-document update safe.

## Compact example documents

All examples also carry the audit/schema fields from Conventions. They are shapes, not production fixture data.

| Path | Example document shape |
|---|---|
| `users/uid_client` | `{ "displayName":"Demo Client", "emailNormalized":"client@example.invalid", "locale":"en", "timeZoneId":"Europe/Sarajevo", "accountStatus":"active", "lifecycleRevision":1 }` |
| `users/uid_client/authorizations/systemCatalog` | `{ "status":"active", "activeMembershipCount":1, "revision":1 }` |
| `workspaces/ws_demo` | `{ "name":"Demo Training", "ownerUid":"uid_trainer", "status":"active", "membershipRevision":1, "activeRosterCount":1, "catalogContributionCount":1 }` |
| `.../memberships/uid_client` | `{ "userId":"uid_client", "role":"client", "status":"active", "revision":1, "catalogContributionActive":true, "invitationId":"inv_demo" }` |
| `.../trainerProfiles/uid_trainer` | `{ "userId":"uid_trainer", "bio":"Strength coach", "contactPreference":"in_app" }` |
| `.../clientProfiles/uid_client` | `{ "userId":"uid_client", "trainerId":"uid_trainer", "onboardingStatus":"complete", "goalSummary":"strength" }` |
| `invitations/inv_demo` | `{ "workspaceId":"ws_demo", "invitedEmailNormalized":"client@example.invalid", "role":"client", "invitedBy":"uid_trainer", "tokenHash":"sha256:…", "tokenVersion":2, "status":"pending", "expiresAt":"timestamp" }` (Admin only) |
| `.../invitationSummaries/inv_demo` | `{ "invitationId":"inv_demo", "emailMasked":"c***@example.invalid", "role":"client", "status":"pending", "expiresAt":"timestamp", "lastSentAt":"timestamp" }` |
| `systemExercises/ex_squat` | `{ "name":"Back squat", "nameNormalized":"back squat", "muscleGroups":["legs"], "equipment":["barbell"], "status":"published" }` |
| `.../exercises/ex_custom` | `{ "ownerUid":"uid_trainer", "name":"Tempo squat", "nameNormalized":"tempo squat", "status":"active" }` |
| `.../programTemplates/tpl_strength` | `{ "ownerUid":"uid_trainer", "title":"Strength A", "currentVersionId":"v3", "status":"active" }` |
| `.../versions/v3` | `{ "versionNumber":3, "title":"Strength A", "weeksCount":4, "publishedBy":"uid_trainer", "publishedAt":"timestamp" }` |
| `.../versions/v3/workouts/day1` | `{ "title":"Day 1", "dayOffset":0, "position":0 }` |
| `.../workouts/day1/exercises/item_squat` | `{ "position":0, "exerciseSource":"system", "exerciseId":"ex_squat", "displayName":"Back squat", "sets":3, "repsText":"8" }` |
| `users/uid_client/workspaces/ws_demo/assignedPrograms/asg_demo/snapshots/content` | `{ "workspaceId":"ws_demo", "clientId":"uid_client", "assignmentId":"asg_demo", "snapshotId":"content", "sourceTemplateId":"tpl_strength", "sourceVersionId":"v3", "sourceVersionNumber":3, "sourceContentHash":"sha256:source-example", "contentHash":"sha256:client-safe-example", "title":"Strength A", "weeksCount":4 }` |
| `users/uid_client/workspaces/ws_demo/assignedPrograms/asg_demo/plannedWorkouts/pw_20260907` | `{ "workspaceId":"ws_demo", "assignmentId":"asg_demo", "clientId":"uid_client", "snapshotId":"content", "workoutId":"day1", "scheduledLocalDate":"2026-09-07", "scheduledInstant":"timestamp", "timeZoneId":"Europe/Sarajevo", "status":"planned", "revision":1 }` |
| `.../workoutSessions/sess_demo` | `{ "clientId":"uid_client", "trainerId":"uid_trainer", "plannedWorkoutId":"pw_20260907", "status":"in_progress", "revision":4, "lastOperationId":"op_session_4" }` |
| `.../loggedSets/set_1` | `{ "exerciseItemId":"item_squat", "setPosition":1, "weightText":"80", "weightKg":80.0, "repsText":"8", "reps":8, "completionStatus":"completed", "baseRevision":3, "revision":4, "lastOperationId":"op_set_4" }` |
| `.../feedback/fb_demo` | `{ "trainerId":"uid_trainer", "clientId":"uid_client", "body":"Good control today.", "visibility":"client" }` |
| `.../progressEntries/prog_demo` | `{ "clientId":"uid_client", "type":"body_weight", "recordedAt":"timestamp", "value":72.4, "unit":"kg" }` |
| `.../privateTrainerNotes/note_demo` | `{ "trainerId":"uid_trainer", "subjectClientId":"uid_client", "body":"Private coaching note" }` |
| `.../availabilityRules/rule_mon` | `{ "trainerId":"uid_trainer", "weekday":1, "localStart":"09:00", "localEnd":"17:00", "timeZoneId":"Europe/Sarajevo", "effectiveFrom":"2026-09-01" }` |
| `.../blockedPeriods/block_demo` | `{ "trainerId":"uid_trainer", "startsAt":"timestamp", "endsAt":"timestamp", "timeZoneId":"Europe/Sarajevo", "reasonCategory":"unavailable" }` |
| `.../appointments/appt_demo` | `{ "trainerId":"uid_trainer", "clientId":"uid_client", "startsAt":"timestamp", "endsAt":"timestamp", "timeZoneId":"Europe/Sarajevo", "durationMinutes":60, "bufferBeforeMinutes":10, "bufferAfterMinutes":10, "status":"confirmed", "idempotencyKey":"book_uuid", "revision":1 }` |
| `.../conversations/conv_demo` | `{ "participantUids":["uid_trainer","uid_client"], "trainerId":"uid_trainer", "clientId":"uid_client", "lastMessageSummary":"text", "lastMessageAt":"timestamp" }` |
| `.../messages/msg_demo` | `{ "senderId":"uid_client", "body":"Completed today’s workout.", "clientCreatedAt":"timestamp", "serverCreatedAt":"timestamp", "deliveryState":"accepted" }` |
| `users/uid_client/deviceTokens/token_demo` | `{ "platform":"ios", "installationId":"install_uuid", "tokenReference":"opaque", "lastSeenAt":"timestamp", "enabled":true }` |
| `users/uid_client/notifications/notif_demo` | `{ "type":"message_received", "destination":{"conversationId":"conv_demo"}, "createdAt":"timestamp", "readAt":null }` |

## Growth, archive, and conflict expectations

| Record family | Expected growth | Archival/deletion | Offline/conflict default |
|---|---|---|---|
| User/workspace/profile/membership/invitation | O(1) per identity/relationship; internal invitations and projections accumulate slowly | Revoke/archive first; trusted retention/TTL keeps internal/projection lifecycle consistent | Profile edits may queue; membership/invitation changes server-authoritative; cached summary/grant grants nothing |
| Exercises | System catalog can be large; custom catalog grows per trainer | Archive referenced exercise, purge only when unreferenced/policy permits | Cached/paged reads; custom edit uses revision and surfaces same-document conflict |
| Templates/versions/workout items | Templates grow slowly; immutable versions/items grow with each publish | Archive logical template; retain referenced versions/history | Draft may queue; publish is revision-checked; published snapshots never merge |
| Account-owned assignments/snapshots/plans/manifests and trainer indexes | O(clients × assigned snapshot size + planned horizon); finite transaction caps required | Revoke/cancel/replace/archive denies content first through the parent; expiry uses server time, not TTL. Trusted recursive cleanup retains terminal identity/receipt protection per policy. | Explicit prefetch of client copies only; revision conflicts reject; unknown offline revocation is bounded and known loss locks recovery |
| Sessions/sets/feedback | Sessions grow indefinitely with training history; sets bounded per session | Time/retention partition via queries; archive sessions, preserve history/audit | Offline-first session/sets; stable-ID merge and explicit same-set conflict; feedback revisioned |
| Progress entries/media | Time-series per client; media dominates storage | Retention/account workflow, asset cleanup only after reference audit | Measurement queues; conflicts preserve versions; media has separate pending upload state |
| Private notes | Low-to-moderate per trainer/client | Trainer-only archive and approved retention after relationship/account changes | Default minimizes offline availability; revision conflict never last-write-wins silently |
| Availability/blocks | Small rules; blocks grow over time | Supersede/archive rules; expire historical blocks per policy | Drafts may queue, but slot availability is stale until server confirmation |
| Appointments | Long-lived time series | Keep status/audit; retention/pseudonymization policy | Read cache only; all commands online, transactional, idempotent |
| Booking slots/command receipts | At most 90 occupied bucket docs per appointment; one receipt per accepted command | No independent lock expiry; transition with appointment. Preserve receipts against duplicate command replay. Retention/repair is trusted and audited. | No mobile reads/writes; committed backend result only |
| System catalog entitlement | One fixed document per UID; bounded count | Account/membership/workspace changes update synchronously; retain inactive account tombstone through cleanup | Cached copy cannot grant access; missing/malformed records deny access, and a detected source mismatch requires trusted deactivation before repair |
| Conversations/messages | Conversations bounded by relationships; messages grow indefinitely | Paginate; per-user archive and retention/tombstone policy | Append offline with stable ID; server timestamp order; no overwrite merge |
| Tokens/notifications | Tokens bounded per installations; notifications ongoing | Remove invalid/sign-out tokens; TTL/archive old notifications | Token registration/readactions retry idempotently; delivery remains server-authoritative |

## Index plan

Milestone 3 adds a minimal emulator `firestore.indexes.json`; the spike paths use point reads or simple queries and require no composite index. Expected later product indexes remain:

- membership collection group: `userId, status` for server-side discovery/maintenance, never a Rules membership search;
- invitation summaries: `status,createdAt desc`;
- client profiles: `trainerId, status, displayNameNormalized`;
- program templates: `ownerUid, status, updatedAt desc`;
- account-owned assignment headers: exact UID/workspace collection, `startDate`; no client collection-group discovery;
- trainer-only assignment/planned-workout indexes: `trainerId,status,updatedAt desc` and `trainerId,status,scheduledInstant`;
- account-owned planned workouts: exact assignment collection, `status,scheduledInstant`; client aggregates a bounded set of its headers locally, not cross-assignment collection-group queries;
- snapshot child/planned query indexes also include any repeated identity equality fields required by [Rules integrity predicates](firestore-security.md#assigned-program-authorization-and-lifecycle), plus `position` or schedule ordering; prove exact queries/indexes in the emulator before implementation acceptance;
- workout sessions: `clientId,status,completedAt desc` and `trainerId,reviewStatus,completedAt desc`;
- progress: `clientId,type,recordedAt desc`;
- appointments: participant ID, status, startsAt; plus reminder scheduling;
- booking slots, command receipts, and catalog entitlements use point reads for authorization/contention, not a query-based existence guarantee;
- conversations: `participantUids array-contains,lastMessageAt desc`;
- notifications: `readAt,createdAt desc`.

## Schema evolution and deletion

- Readers tolerate known older `schemaVersion` values; writers emit the current version. Destructive migrations are server-run, resumable, audited, and idempotent.
- Account deletion first atomically sets account status non-active and catalog entitlement inactive, then revokes memberships/tokens and runs trusted retention cleanup. Each membership removal updates its entitlement contribution in the same transaction; keep the user/entitlement tombstones until cleanup is complete. Shared messages/appointments may require pseudonymization; cancel/complete appointments and release locks transactionally before any permitted removal. Legal/product retention policy is unresolved.
- That account denial also gates all account-owned assignment content immediately. Assignment cleanup is explicit and resumable across snapshot/workout/item/manifest descendants and workspace indexes: deleting a parent is not recursive deletion. Retain terminal assignment tombstones/receipts against replay; never restore an active parent over incomplete or revoked children. Follow the [assignment lifecycle](firestore-security.md#assigned-program-authorization-and-lifecycle).
- Firestore TTL may remove expired invitations and operational notification records, but TTL is not used as authorization or as the only account-deletion mechanism.
- Retention periods for health-related progress, workout history, messages, private notes, media, and audit events require legal/product approval before implementation.
