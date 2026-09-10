# Firestore schema proposal

Status: **proposal; no Firebase resources have been created**
Review date: **2026-09-10** (booking contention and catalog entitlement proposal)

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
| `users/{uid}` (Firebase UID) | User-owned global account: `displayName`, `emailNormalized`, `photoAssetId?`, `locale`, `timeZoneId`, `accountStatus`, server-owned `lifecycleRevision` | UID, normalized email, status and lifecycle revision are protected; user may edit safe profile/preferences. Account lifecycle atomically advances revision with entitlement. Retain a minimal tombstone during trusted deletion, then apply retention policy. | Direct lookup. Never search clients by private email from ordinary clients. App-owned local records are UID-partitioned; Firebase cache cleanup follows `offline-sync.md` and is not isolated by DI scope. |
| `users/{uid}/authorizations/systemCatalog` (fixed ID) | Server-owned entitlement: `status` (`active`/`inactive`), integer `activeMembershipCount`, `revision`, plus schema/audit fields | Bootstrap inactive with count zero. Every membership/account/workspace lifecycle operation maintains it atomically as specified in [security](firestore-security.md#system-catalog-entitlement-lifecycle). No client writes. | Rules point-read this exact UID-derived path and `users/{uid}`. Count is bounded by validated `maxMembershipsPerAccount`; only global catalog access is authorized. Cached state grants no authority. |
| `lifecycleCommands/{commandId}` | Server-owned receipt: caller/service identity, target path, command kind, `requestHash`, committed source revision/result, schema/audit fields | ID is SHA-256 of canonically encoded caller/service identity, target, and idempotency key. Immutable successful receipt written in the source/entitlement transaction. Requests include expected source revision; changed payload/key reuse fails. | Trusted point lookup only. Preserve through the supported retry lifetime; expired keys cannot be accepted as new commands. No mobile access. |
| `workspaces/{workspaceId}` | Trainer-owned tenant: `name`, `ownerUid`, `status`, server-owned `membershipRevision` | `ownerUid` immutable; server-controlled ownership transfer/status. Every membership transition increments `membershipRevision`; workspace deactivation/restoration atomically maintains affected catalog entitlements. Archive before deletion. | Direct lookup through membership IDs; low growth. The revision serializes roster/lifecycle changes; this document does not replace membership role checks. |
| `workspaces/{workspaceId}/memberships/{uid}` | One membership per user: `userId`, `role` (`trainer`/`client`), `status`, `joinedAt`, `invitationId?`, `revision`, server-owned `catalogContributionActive`, safe display summary | Tenant/user/role/status/contribution protected; trusted lifecycle sets contribution true exactly when membership and workspace are active. Each change and its entitlement delta commit together. Revoke before archive. | Membership remains authoritative for workspace-scoped access/roles. Server workspace discovery/maintenance may query `userId,status` with a collection-group index; Rules cannot use that query to locate arbitrary membership. Bounded offline rendering is separate. |
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

`activeMembershipCount` counts this UID's active memberships in active workspaces, independently of account status. `status` is `active` only when that count is positive **and** `users/{uid}.accountStatus == "active"`; otherwise it is `inactive`. This permits immediate account suspension without losing the count needed for safe restoration. `catalogContributionActive` on each membership records its contribution for idempotent before/after deltas, not a client-supplied permission. The authoritative lifecycle, bounds, and failure behavior are in [firestore-security.md](firestore-security.md#system-catalog-entitlement-lifecycle).

## Exercises and programming

| Collection/path and ID | Ownership and required fields | Mutability and lifecycle | Queries, indexes, denormalization, offline behavior |
|---|---|---|---|
| `systemExercises/{exerciseId}` | Global catalog, server/admin-owned: `name`, `nameNormalized`, `muscleGroups`, `equipment`, `instructions`, `mediaRef?`, `status` (`draft`/`published`/`archived`) | Eligible authenticated accounts read only `published` entries; archive transitions status to `archived` and sets audit fields. No client writes; immutable ID. Retain referenced exercise snapshots. | Rules require active account plus the fixed `systemCatalog` entitlement. Queries include `status == "published"`; Rules do not filter unsafe results. Other search/filter indexes are product-dependent; cache is display-only. |
| `workspaces/{workspaceId}/exercises/{exerciseId}` | Trainer custom exercise: `ownerUid`, same descriptive fields as system exercise, workspace `status` (`active`/`archived`) | Tenant/owner immutable; trainers create/update/archive. Never hard-delete while referenced. | `status,nameNormalized`; optional `equipment,status`. Offline create/edit queued; conflicts surface for simultaneous edits. |
| `.../programTemplates/{templateId}` | Trainer-owned logical template: `ownerUid`, `title`, `description?`, `currentVersionId`, `status` | Identity/owner immutable; metadata/current pointer mutable. Archive without touching versions or assignments. | Trainer list: `ownerUid,status,updatedAt desc`. Offline draft editing allowed; publishing requires version transaction. |
| `.../programTemplates/{templateId}/versions/{versionId}` | Immutable published snapshot: `versionNumber`, `title`, `weeksCount`, `publishedAt`, `publishedBy` | Published version content is immutable. Drafts may be separate mutable records or local builder state; decision recorded in open questions. | Read by assignment/reference. Exercises live in bounded workout/exercise subcollections, not one growing array. |
| `.../programTemplates/{templateId}/versions/{versionId}/workouts/{workoutId}` and `/exercises/{itemId}` | Ordered immutable workout/exercise snapshot: title/day, `position`, exercise reference and copied display/prescription fields | Immutable once version published. Deletion only with unused draft/version cleanup. | Query by `position`; bounded per version but subcollections prevent document-size pressure. Explicit prefetch supports offline workouts. |
| `.../assignedPrograms/{assignmentId}` | Client/trainer/tenant: `clientId`, `trainerId`, `templateId`, `templateVersionId`, `startDate`, `timeZoneId`, `status` | IDs/version immutable; scheduling/status mutable by trainer or trusted workflow. Archive on completion/replacement. | Client: `clientId,status,startDate`; trainer: `trainerId,status,updatedAt desc`. Offline read if prefetched; assignment changes use revision check. |
| `.../plannedWorkouts/{plannedWorkoutId}` | Assignment instance: `assignmentId`, `clientId`, `trainerId`, `templateVersionId`, `scheduledLocalDate`, `timeZoneId`, immutable workout snapshot reference/content, `status`, `revision` | Identity and snapshot immutable after publication; date/status may change under explicit rules. Never rewrite due to template edits. | Client upcoming: `clientId,status,scheduledInstant`; trainer review: `trainerId,status,scheduledInstant`. Prefetch required documents; the app-owned manifest and cache recheck are defined in `offline-sync.md`. |

Example assigned program:

```json
{
  "schemaVersion": 1,
  "workspaceId": "ws_demo",
  "clientId": "uid_client",
  "trainerId": "uid_trainer",
  "templateId": "tpl_strength",
  "templateVersionId": "v3",
  "startDate": "2026-09-07",
  "timeZoneId": "Europe/Sarajevo",
  "status": "active"
}
```

## Workout, progress, and private coaching data

| Collection/path and ID | Ownership and required fields | Mutability and lifecycle | Queries, indexes, denormalization, offline behavior |
|---|---|---|---|
| `.../workoutSessions/{sessionId}` | Client-created server business record: `clientId`, `trainerId`, `plannedWorkoutId?`, immutable workout/version snapshot refs, `startedAtClient`, `status`, `revision`, `lastOperationId`, `completedAt?` | Tenant/client/plan refs immutable. Client requests legal monotonic transitions; trainer review fields are allowlisted. `completed` means server accepted, while local completion intent remains device-local. Archive per health-data retention policy. | Client history: `clientId,status,completedAt desc`; trainer queue: `trainerId,reviewStatus,completedAt desc`. There is no shared `syncState`; local transport is defined in `offline-sync.md`. |
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
    "templateVersionId": "v3",
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
| `.../appointments/{appointmentId}` | Workspace/client/trainer: UTC instants, `timeZoneId`, local display metadata, duration, pre/post buffers, `slotQuantumMinutes`, `firstUtcBucket`, `slotBucketCount`, `status`, original `idempotencyKey`, `revision` | Server owns commands and stored lock-range descriptor; only a committed acquisition can set `confirmed`. Reschedule atomically replaces lock range; cancel atomically releases it. Keep history/audit. | Client: `clientId,startsAt`; trainer: `trainerId,startsAt`; reminder worker: `status,reminderAt`. No direct mobile mutations. |
| `workspaces/{workspaceId}/bookingSlots/{trainerId}_{utcBucket}` | Server-owned occupied bucket: `workspaceId`, `trainerId`, `appointmentId`, `slotStart` (UTC), schema/audit fields | ID encodes a canonical path-safe trainer ID and decimal bucket-start epoch minute. Held while the appointment is `confirmed`; release only in the appointment transition transaction. No client writes or TTL. | Read exact deterministic paths, including absent documents. Each appointment owns a bounded contiguous range; document contains no client, reason, or private appointment payload. |
| `workspaces/{workspaceId}/bookingCommands/{commandId}` | Server-owned receipt: `callerUid`, `commandKind`, `requestHash`, `appointmentId`, committed `result` (status/revision), schema/audit fields | `commandId = SHA-256(canonical encoding of callerUid + idempotencyKey)`, scoped by workspace. Same key with different payload/kind is rejected; successful receipt is immutable. | Point lookup by trusted endpoint only. Created with appointment/lock mutations. Retain for the supported retry lifetime; do not expire and silently accept the same key again. |

### Deterministic bucket coverage and bounds

This is a selected planning mechanism, not an implemented or emulator-proven guarantee. For one trainer in one workspace, validate explicit local-time/DST resolution first, then use half-open UTC intervals throughout. With quantum `q = slotQuantumMinutes` and `Q = q * 60` seconds, the buffered interval is `[startsAt - preBuffer, endsAt + postBuffer)`. Read/write every bucket from `floor(bufferedStartEpochSeconds / Q)` through `ceil(bufferedEndEpochSeconds / Q) - 1`, using the bucket's UTC start epoch minute as `utcBucket`. Thus any positive overlap shares at least one deterministic document even when both requests see no existing appointments. An empty appointment query is not the contention mechanism.

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

- Display summaries may be copied to cards, messages, and assignments so lists remain cheap. The source record remains authoritative; trusted fan-out or bounded client transactions update copies. Stale summaries are acceptable only where explicitly documented.
- Template publishing atomically creates an immutable version and moves `currentVersionId`. Existing assignments, planned workouts, sessions, and history retain their version references/snapshots.
- Appointment status and occupied deterministic buckets change atomically. Availability queries and notification summaries never replace the locks as contention authority.
- The account's system-catalog entitlement is a trusted, synchronous authorization projection, maintained atomically with lifecycle source changes; unlike display summaries it must not lag. It grants no workspace permissions.
- Conversation `lastMessage*` fields are derived from the accepted message; a trusted trigger is preferred if rules cannot make the multi-document update safe.

## Compact example documents

All examples also carry the audit/schema fields from Conventions. They are shapes, not production fixture data.

| Path | Example document shape |
|---|---|
| `users/uid_client` | `{ "displayName":"Demo Client", "emailNormalized":"client@example.invalid", "locale":"en", "timeZoneId":"Europe/Sarajevo", "accountStatus":"active", "lifecycleRevision":1 }` |
| `users/uid_client/authorizations/systemCatalog` | `{ "status":"active", "activeMembershipCount":1, "revision":1 }` |
| `workspaces/ws_demo` | `{ "name":"Demo Training", "ownerUid":"uid_trainer", "status":"active", "membershipRevision":1 }` |
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
| `.../assignedPrograms/asg_demo` | `{ "clientId":"uid_client", "trainerId":"uid_trainer", "templateVersionId":"v3", "startDate":"2026-09-07", "timeZoneId":"Europe/Sarajevo", "status":"active" }` |
| `.../plannedWorkouts/pw_20260907` | `{ "assignmentId":"asg_demo", "clientId":"uid_client", "trainerId":"uid_trainer", "templateVersionId":"v3", "scheduledLocalDate":"2026-09-07", "scheduledInstant":"timestamp", "status":"planned" }` |
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
| Assignments/planned workouts | O(clients × active programs × scheduled workouts) | Archive completed/replaced assignments; retain history per policy | Explicit prefetch; assignment conflicts reject stale revision |
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

The exact `firestore.indexes.json` will be generated only when implementation begins. Expected composite indexes are:

- membership collection group: `userId, status` for server-side discovery/maintenance, never a Rules membership search;
- invitation summaries: `status,createdAt desc`;
- client profiles: `trainerId, status, displayNameNormalized`;
- program templates: `ownerUid, status, updatedAt desc`;
- assigned programs: `clientId, status, startDate` and `trainerId, status, updatedAt desc`;
- planned workouts: participant ID, status, scheduled instant;
- workout sessions: `clientId,status,completedAt desc` and `trainerId,reviewStatus,completedAt desc`;
- progress: `clientId,type,recordedAt desc`;
- appointments: participant ID, status, startsAt; plus reminder scheduling;
- booking slots, command receipts, and catalog entitlements use point reads for authorization/contention, not a query-based existence guarantee;
- conversations: `participantUids array-contains,lastMessageAt desc`;
- notifications: `readAt,createdAt desc`.

## Schema evolution and deletion

- Readers tolerate known older `schemaVersion` values; writers emit the current version. Destructive migrations are server-run, resumable, audited, and idempotent.
- Account deletion first atomically sets account status non-active and catalog entitlement inactive, then revokes memberships/tokens and runs trusted retention cleanup. Each membership removal updates its entitlement contribution in the same transaction; keep the user/entitlement tombstones until cleanup is complete. Shared messages/appointments may require pseudonymization; cancel/complete appointments and release locks transactionally before any permitted removal. Legal/product retention policy is unresolved.
- Firestore TTL may remove expired invitations and operational notification records, but TTL is not used as authorization or as the only account-deletion mechanism.
- Retention periods for health-related progress, workout history, messages, private notes, media, and audit events require legal/product approval before implementation.
