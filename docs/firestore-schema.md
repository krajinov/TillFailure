# Firestore schema proposal

Status: **proposal; no Firebase resources have been created**
Review date: **2026-09-01**

## Conventions

- Every workspace-scoped path is tenant-isolated by `workspaceId`. Immutable owner/tenant IDs are repeated where needed for rules and collection-group queries, but clients may never change them.
- IDs are stable, client-generated random UUID strings for offline-safe records. Trusted commands also carry a unique `idempotencyKey`.
- Every mutable document has `schemaVersion`, `createdAt`, `createdBy`, `updatedAt`, and `updatedBy`; server-owned records use server timestamps. Archival uses `archivedAt`/`archivedBy`. `deletedAt` is reserved for recoverable lifecycle workflows.
- Large or repeated records use subcollections. Exercise sequences, logged sets, messages, notifications, tokens, and photos never accumulate in unbounded arrays.
- Domain models and UI state do not expose `DocumentSnapshot`, server timestamp sentinels, or other Firebase types.
- Examples omit routine audit fields when the table already specifies them. Values are fictitious.
- Per-device transport state, offline eligibility, workout download manifests, active-session recovery snapshots, mutation journals, upload registries, and account-switch markers are app-owned local records defined canonically in [offline-sync.md](offline-sync.md); they are not shared Firestore business fields.

## Identity and tenancy

| Collection/path and ID | Ownership and required fields | Mutability and lifecycle | Queries, indexes, growth, offline behavior |
|---|---|---|---|
| `users/{uid}` (Firebase UID) | User-owned global account: `displayName`, `emailNormalized`, `photoAssetId?`, `locale`, `timeZoneId`, `accountStatus` | UID, normalized email and status are protected; user may edit safe profile/preferences. Retain a minimal tombstone during trusted account deletion, then apply retention policy. | Direct lookup. Never search clients by private email from ordinary clients. App-owned local records are UID-partitioned; Firebase cache cleanup follows `offline-sync.md` and is not isolated by DI scope. |
| `workspaces/{workspaceId}` | Trainer-owned tenant: `name`, `ownerUid`, `status` | `ownerUid` immutable; server-controlled ownership transfer/status. Archive before deletion. | Direct lookup through membership IDs; low growth. Workspace is not an authorization cache. |
| `workspaces/{workspaceId}/memberships/{uid}` | One membership per user: `userId`, `role` (`trainer`/`client`), `status`, `joinedAt`, `invitationId?`, safe display summary | Tenant/user/role/status immutable to ordinary clients; trusted operations change role/status. Revoke immediately by setting status, later archive. | User’s workspace list requires a collection-group index on `userId,status`; membership remains server authority. A cached/grant value never authorizes server access; bounded offline rendering is separate. |
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
  "joinedAt": "server timestamp",
  "invitationId": "inv_demo"
}
```

### Invitation lifecycle and read boundary

The internal invitation and trainer projection are a trusted dual write. Mobile clients never read `invitations/{id}` directly because [Firestore reads are document-level and Rules cannot hide selected fields](https://firebase.google.com/docs/firestore/security/rules-fields#allowing_read_access_only_for_specific_fields). Trainer list/detail reads only `workspaces/{wid}/invitationSummaries/{id}`. Token preview/acceptance calls a trusted endpoint that returns an allowlisted DTO after rate limiting and token validation; it never returns the stored hash.

Create generates a high-entropy token, stores only its hash/version internally, and sends the raw token through the approved delivery channel. Acceptance requires an authenticated account, unexpired `pending` record, matching normalized email/account policy, active inviter/workspace, and unused token; the transaction creates membership/profile, marks internal status accepted, invalidates token material, and updates the summary. Resend rotates token/version and expiry so old links fail, then updates `lastSentAt`/status. Revoke invalidates token material and updates both records. All trusted mutations are idempotent and repairable if projection update delivery is retried; the internal record is authority.

## Exercises and programming

| Collection/path and ID | Ownership and required fields | Mutability and lifecycle | Queries, indexes, denormalization, offline behavior |
|---|---|---|---|
| `systemExercises/{exerciseId}` | Global catalog, server/admin-owned: `name`, `nameNormalized`, `muscleGroups`, `equipment`, `instructions`, `mediaRef?`, `status` | Client read-only; immutable ID. Version or update content with audit trail; archive instead of deleting referenced exercises. | Search/filter indexes are product-dependent; Firestore prefix search is limited. Cache selected catalog pages; full-text search remains unresolved. |
| `workspaces/{workspaceId}/exercises/{exerciseId}` | Trainer custom exercise: `ownerUid`, same core fields as system exercise | Tenant/owner immutable; trainers create/update/archive. Never hard-delete while referenced. | `status,nameNormalized`; optional `equipment,status`. Offline create/edit queued; conflicts surface for simultaneous edits. |
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
| `.../availabilityRules/{ruleId}` | Trainer: weekday/recurrence rule, local start/end, `timeZoneId`, effective dates, buffers | Trainer-owned, revisioned; archive superseded rules. Store IANA zone ID, not only offset. | Trainer/effective date; small. Editing may affect future slots but never silently rewrites confirmed appointments. |
| `.../blockedPeriods/{blockId}` | Trainer: `startsAt`, `endsAt`, `timeZoneId`, `reasonCategory?` | Trainer-owned; server validates interval. Archive after retention period. | `trainerId,startsAt,endsAt`; range query. Offline draft allowed, confirmation online. |
| `.../appointments/{appointmentId}` | Workspace/client/trainer: UTC instants, `timeZoneId`, local display metadata, duration, pre/post buffers, `status`, `idempotencyKey`, `revision` | Participants/tenant and original creator protected. Booking/reschedule/cancel is server-authoritative and transactional. Keep history or append audit events. | Client: `clientId,startsAt`; trainer: `trainerId,startsAt`; reminder worker: `status,reminderAt`. Composite indexes include status and time. Never show confirmed until server commits. |

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
- Appointment availability and status are server-authoritative. Notification summaries are derived and never authority for the source event.
- Conversation `lastMessage*` fields are derived from the accepted message; a trusted trigger is preferred if rules cannot make the multi-document update safe.

## Compact example documents

All examples also carry the audit/schema fields from Conventions. They are shapes, not production fixture data.

| Path | Example document shape |
|---|---|
| `users/uid_client` | `{ "displayName":"Demo Client", "emailNormalized":"client@example.invalid", "locale":"en", "timeZoneId":"Europe/Sarajevo", "accountStatus":"active" }` |
| `workspaces/ws_demo` | `{ "name":"Demo Training", "ownerUid":"uid_trainer", "status":"active" }` |
| `.../memberships/uid_client` | `{ "userId":"uid_client", "role":"client", "status":"active", "invitationId":"inv_demo" }` |
| `.../trainerProfiles/uid_trainer` | `{ "userId":"uid_trainer", "bio":"Strength coach", "contactPreference":"in_app" }` |
| `.../clientProfiles/uid_client` | `{ "userId":"uid_client", "trainerId":"uid_trainer", "onboardingStatus":"complete", "goalSummary":"strength" }` |
| `invitations/inv_demo` | `{ "workspaceId":"ws_demo", "invitedEmailNormalized":"client@example.invalid", "role":"client", "invitedBy":"uid_trainer", "tokenHash":"sha256:…", "tokenVersion":2, "status":"pending", "expiresAt":"timestamp" }` (Admin only) |
| `.../invitationSummaries/inv_demo` | `{ "invitationId":"inv_demo", "emailMasked":"c***@example.invalid", "role":"client", "status":"pending", "expiresAt":"timestamp", "lastSentAt":"timestamp" }` |
| `systemExercises/ex_squat` | `{ "name":"Back squat", "nameNormalized":"back squat", "muscleGroups":["legs"], "equipment":["barbell"], "status":"active" }` |
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
| Conversations/messages | Conversations bounded by relationships; messages grow indefinitely | Paginate; per-user archive and retention/tombstone policy | Append offline with stable ID; server timestamp order; no overwrite merge |
| Tokens/notifications | Tokens bounded per installations; notifications ongoing | Remove invalid/sign-out tokens; TTL/archive old notifications | Token registration/readactions retry idempotently; delivery remains server-authoritative |

## Index plan

The exact `firestore.indexes.json` will be generated only when implementation begins. Expected composite indexes are:

- membership collection group: `userId, status`;
- invitation summaries: `status,createdAt desc`;
- client profiles: `trainerId, status, displayNameNormalized`;
- program templates: `ownerUid, status, updatedAt desc`;
- assigned programs: `clientId, status, startDate` and `trainerId, status, updatedAt desc`;
- planned workouts: participant ID, status, scheduled instant;
- workout sessions: `clientId,status,completedAt desc` and `trainerId,reviewStatus,completedAt desc`;
- progress: `clientId,type,recordedAt desc`;
- appointments: participant ID, status, startsAt; plus reminder scheduling;
- conversations: `participantUids array-contains,lastMessageAt desc`;
- notifications: `readAt,createdAt desc`.

## Schema evolution and deletion

- Readers tolerate known older `schemaVersion` values; writers emit the current version. Destructive migrations are server-run, resumable, audited, and idempotent.
- Account deletion first revokes membership and tokens, then runs a server-side deletion/export/retention workflow. Shared records such as messages and appointments may require pseudonymization rather than unilateral deletion; legal/product policy is unresolved.
- Firestore TTL may remove expired invitations and operational notification records, but TTL is not used as authorization or as the only account-deletion mechanism.
- Retention periods for health-related progress, workout history, messages, private notes, media, and audit events require legal/product approval before implementation.
