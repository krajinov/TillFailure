# Firebase authorization and security plan

Status: **proposal; rules and resources do not yet exist**
Review date: **2026-09-10** (booking contention and catalog entitlement proposal)

## Authorization model

Authentication answers who the caller is; the active membership document answers what the caller may do in a workspace. Protected workspace data requires an active account, active workspace, and `workspaces/{workspaceId}/memberships/{request.auth.uid}.status == "active"`, plus the path's role/ownership checks. Global `systemExercises` instead uses the fixed account entitlement described below; it grants no workspace role or access. A locally cached role/entitlement, navigation shell, custom claim, or hidden UI is never authorization.

Roles for MVP:

- `trainer`: manages the single-trainer workspace, clients, programming, availability, review, and trainer-only notes.
- `client`: accesses only their membership/profile, assigned content, own sessions/progress, their appointments, and conversations they participate in.
- Trusted backend: uses Admin SDK only after repeating authentication, resource-specific authorization, role/ownership, input, and idempotency checks. Workspace operations require membership; global catalog operations require the account entitlement. Admin SDK bypasses Firestore rules.

Membership role/status/tenant IDs and immutable owner IDs are protected by field allowlists. Role creation, role changes, ownership transfer, and revocation are trusted operations. Revocation takes effect for server requests as soon as current Rules/Functions evaluate it. An offline device cannot discover a remote revocation immediately and may render previously downloaded content under the bounded local eligibility policy in [offline-sync.md](offline-sync.md); that local grant never authorizes a server request. On reconnect, denied pending writes remain recoverable through the account-owned mutation journal rather than being silently deleted.

## Firestore permission matrix

`Own` means the authenticated UID matches the immutable owner/client field. `Participant` means the conversation/appointment contains the UID and the membership is active.

| Path | Read | Create | Update | Delete/archive | Required validation |
|---|---|---|---|---|---|
| `users/{uid}` | Own | Trusted account bootstrap | Own safe fields; status/email linkage/lifecycleRevision trusted | Trusted lifecycle | Exact UID; field allowlist; bounded strings/enums |
| `users/{uid}/authorizations/systemCatalog` | Own active account, for display only; Rules may point-read | Trusted bootstrap/lifecycle | Trusted lifecycle only | Trusted lifecycle/retention | Count/status/revision/audit entirely server-owned; no client create/update/delete |
| `lifecycleCommands/{id}` | Trusted only | Trusted lifecycle transaction | Immutable receipt | Trusted retention | Scoped caller/target/key, request hash, committed result; no mobile access |
| `users/{uid}/deviceTokens/{id}` | Own; trusted sender | Own | Own safe token metadata; trusted invalidation | Own/trusted | UID, platform, installation ownership; token never publicly readable |
| `users/{uid}/notifications/{id}` | Own | Trusted fan-out | Own `readAt`/archive only | Own/trusted retention | Recipient immutable; safe destination/type payload |
| `workspaces/{wid}` | Active member | Trusted owner creation | Trainer safe metadata; ownership/status/membershipRevision trusted | Trusted | Active account/workspace/membership; lifecycle entitlement updates atomic |
| `.../memberships/{uid}` | Self or active trainer | Trusted bootstrap/invitation acceptance | Trusted role/status/contribution/revision; self may only safe preferences if any | Trusted lifecycle | UID/tenant/role protected; no role escalation; entitlement delta atomic |
| `.../trainerProfiles/{uid}` | Active members | Own active trainer | Own safe fields | Trusted/archive own if allowed | Active trainer membership and matching UID |
| `.../clientProfiles/{uid}` | Own client or active trainer | Trusted onboarding/acceptance | Own client-safe or trainer-safe field sets | Trusted | Matching client membership; trainer relationship; health-field bounds |
| `invitations/{id}` | **Admin SDK/trusted endpoint only; no mobile read** | Trusted | Trusted create/resend/revoke/accept/expire | Trusted retention | Internal email binding and token hash/version; expiry, single-use and idempotency |
| `workspaces/{wid}/invitationSummaries/{id}` | Active workspace trainer | Trusted projection only | Trusted projection only | Trusted retention | Allowlists masked email, role, status and timestamps; contains no token/hash/internal email key |
| `systemExercises/{id}` | Authenticated active account with active `users/{uid}/authorizations/systemCatalog` | Admin only | Admin only | Admin only | Positive bounded membership count, `status == "published"`; archived/draft entries denied; no client writes |
| `.../exercises/{id}` | Active member | Trainer | Trainer | Trainer archive | Immutable tenant/owner; bounded normalized fields/media refs |
| `.../programTemplates/{id}` and versions/workouts/items | Trainer; assigned client reads only published version referenced by their assignment | Trainer | Trainer drafts/metadata; published versions immutable | Trainer archive; trusted cleanup | Ownership, version state, bounds, exercise references; client cannot enumerate unrelated templates |
| `.../assignedPrograms/{id}` | Matching client or trainer | Trainer | Trainer; narrowly allowed client acknowledgement if required | Trainer archive | Immutable client/trainer/version/tenant; active relationships |
| `.../plannedWorkouts/{id}` | Matching client or trainer | Trainer/trusted materializer | Trainer scheduling/status allowlist; client session-linked acknowledgement only | Trainer archive | Immutable snapshot/version/participants; state transition validation |
| `.../workoutSessions/{id}` | Matching client or trainer | Matching client | Client legal business-status fields; trainer review fields in disjoint allowlist | Archive by policy | Immutable participants/plan refs; monotonic status, `baseRevision`/revision/operation ID; no client-authored shared transport state |
| `.../loggedSets/{id}` | Session client/trainer | Session client | Session client under edit policy | Client tombstone under policy | Parent ownership; position/value/status bounds; Rules compare `baseRevision` to existing revision and require next revision/operation ID |
| `.../feedback/{id}` | Session client/trainer | Trainer | Authoring trainer | Trainer archive | Visibility fixed to client; trainer/session relationship |
| `.../progressEntries/{id}` | Matching client/trainer | Matching client | Matching client; trainer comment only if separate rule | Client archive | Immutable client; type-specific allowed fields and numeric bounds; media ownership |
| `.../privateTrainerNotes/{id}` | Authoring active trainer only | Trainer | Authoring trainer | Authoring trainer/archive | Never client-readable; subject must be trainer’s active client |
| `.../schedulingPolicies/{trainerId}` | Active member | Trusted setup | Trainer may advance scheduleRevision atomically with availability/block edit; all policy values trusted | Trusted; no removal while bookings exist | Validated bounded policy; quantum immutable after scheduling enablement |
| `.../availabilityRules/{id}` | Active workspace participants as needed for booking | Trainer | Trainer | Trainer archive | Trainer owner, valid local intervals/zone/effective dates; atomic scheduleRevision advance |
| `.../blockedPeriods/{id}` | Trainer; clients receive derived available slots, not reasons | Trainer | Trainer | Trainer archive | Valid intervals; reason not exposed to clients; atomic scheduleRevision advance |
| `.../appointments/{id}` | Participant | Trusted booking | Trusted reschedule/cancel/completion | Trusted archive/retention | Account/workspace/membership, availability, deterministic buffered bucket locks, legal transition and receipt commit together; every direct mobile mutation denied |
| `.../bookingSlots/{trainerId}_{utcBucket}` | Trusted only | Trusted booking transaction | Trusted appointment transaction | Trusted release/repair transaction only | Immutable tenant/trainer/bucket identity; appointment owner checked; no mobile mutations or independent expiry |
| `.../bookingCommands/{id}` | Trusted endpoint only | Trusted appointment transaction | Immutable receipt | Trusted retention | Caller/key/request hash binding; committed original result; no mobile access |
| `.../conversations/{id}` | Participant | Trusted or trainer-client validated create | Participant safe metadata only | Participant-specific archive/trusted | Exact permitted pair; participant IDs immutable and bounded |
| `.../messages/{id}` | Participant | Participant | Sender within edit policy | Sender tombstone/trusted retention | Parent participation; sender UID; bounded content; owned attachments; immutable owner fields |

Rules will use explicit allowed-key and affected-key checks, type/length/range validation, immutable field comparisons, and parent-document checks. They will not rely on client-supplied denormalized role fields.

### Global catalog Rules check

For both single reads and queries, require `request.auth != null`, `users/{request.auth.uid}.accountStatus == "active"`, and `users/{request.auth.uid}/authorizations/systemCatalog.status == "active"` with a positive integer count within the deployed bound and supported schema version. Rules and trusted backend use the same versioned membership-cap configuration, never a client-supplied limit. The exercise must have `status == "published"`; `draft` and `archived` are distinct denied statuses, not display-only flags. Queries must constrain published status because Rules do not filter results. Missing account/entitlement or invalid data denies access. These two fixed document reads fit the [Rules document-access model](https://firebase.google.com/docs/firestore/security/rules-conditions#access_other_documents); the planned tests must confirm the actual access-call budget.

Rules cannot search a membership collection group for any active membership. A trusted backend may perform bounded membership queries for lifecycle maintenance, but Rules use only the fixed UID paths above. Custom claims, client-maintained flags, cached roles, and UI visibility are not substitutes. Catalog entitlement never satisfies a workspace membership or role check.

### System catalog entitlement lifecycle

The source/entitlement/receipt shapes are owned by [firestore-schema.md](firestore-schema.md#identity-and-tenancy). Bootstrap an account and its inactive, zero-count entitlement together before adding memberships. All lifecycle entry points, including administrative tooling, use the following transaction protocol; an asynchronous membership trigger is not the authorization mechanism.

1. Verify caller authority. Read the deterministic lifecycle receipt, current account/workspace, affected membership(s), and entitlement(s) before any writes. A matching successful receipt returns the recorded result before checking a new transition's expected source revision; changed payload with the same key or a stale new command fails. A revoked caller is not reauthorized by an old receipt.
2. For each affected membership compute `delta = newCatalogContributionActive - oldCatalogContributionActive` (booleans mapped to 1/0), from current membership and workspace statuses. Apply it once to the UID's current count, rejecting underflow, overflow, malformed or inconsistent state. Never blindly increment/decrement on event delivery. Concurrent changes in different workspaces read/write the same UID entitlement and therefore retry on conflict. All membership transitions write the entitlement revision even when delta is zero.
3. Atomically write membership status/contribution/revision, the count and derived entitlement status, workspace `membershipRevision`, and the successful command receipt. First activation (including trainer bootstrap/invitation acceptance) grants catalog access; final contribution removal revokes it; removing one of several leaves it active. Restoration adds the contribution exactly once. A role-only change does not add a membership. A failed transaction changes none of these records.
4. Workspace suspend/archive/delete or restore reads the bounded set of memberships with `status == "active"`, adjusts every affected contribution/entitlement, and changes workspace status in **one transaction**. All membership writers also advance the same workspace revision, preventing an activation from escaping a concurrent lifecycle scan. Suspended-workspace memberships can keep their relationship status for restoration, but contribute zero. Do not report deactivation complete after merely queuing fan-out.
5. Account disable/deletion first atomically sets `users/{uid}.accountStatus` non-active and entitlement status inactive; retain the count until membership cleanup updates it transactionally. Commit this Firestore denial before disabling/deleting Firebase Auth, whose API cannot participate in the Firestore transaction. Old ID tokens then cannot read the catalog. Keep tombstones through cleanup; retry failed Auth/retention steps without reopening access. Restoration enables Auth first, then transactionally activates the Firestore account and recalculates entitlement status from the maintained count. Raw Auth-console changes alone are not this lifecycle and must not be advertised as immediate Rules revocation.

**Bounded lifecycle contract:** trusted configuration must supply finite `maxMembershipsPerAccount` and `maxMembershipsPerWorkspace`, covering memberships whose relationship status is active even in a suspended workspace. Both activation and restoration enforce these limits; count remains within the account bound. Concurrent per-account membership scans serialize through its entitlement revision; workspace scans serialize through `membershipRevision`. Configure caps so a full workspace transition's `2 * affectedMemberships + 2 + A` writes (membership/entitlement pairs, workspace, receipt, optional audit writes `A`) and all document/index bytes fit the [schema transaction budgets](firestore-schema.md#deterministic-bucket-coverage-and-bounds). Bound account reconciliation reads as well. Preflight limits and recheck inside the transaction; never truncate a scan or split the authorization change into eventual batches. Cap values remain product/operational decisions, but an uncapped deployment is unsupported.

Missing/malformed entitlements deny catalog access and block related lifecycle mutations pending audited, bounded reconciliation of current sources. A detected source/count mismatch requires trusted deactivation of the entitlement before repair; Rules cannot discover a plausible but stale count by searching memberships. Repair must serialize with those same lifecycle records. No independent TTL deletes entitlements or contributing memberships. Milestone 3 must prove these invariants before the lifecycle is implemented in Milestone 4. If required workspace size exceeds the atomic budget or a lifecycle path cannot maintain it, this proposal is blocked: explicitly approve another catalog policy/layout before enabling that path, rather than claiming arbitrary Rules lookup or delayed projection is equivalent.

## Storage permission matrix

Metadata must include immutable `workspaceId`, `ownerUid`, asset purpose, content type, byte size, and related document ID. Upload paths use random asset IDs, not user-provided filenames. Rules validate size and a narrow MIME allowlist; post-upload processing may verify actual content before publication.

| Storage path | Read | Write/delete | Notes |
|---|---|---|---|
| `avatars/{uid}/{assetId}` | Authenticated users allowed to view the profile | Owner; trusted lifecycle | Public-to-members profile asset only; no sensitive metadata |
| `workspaces/{wid}/clients/{clientUid}/progress/{entryId}/{assetId}` | Matching client and active trainer | Matching client; trusted cleanup | Private health/progress media; never use permanent public URLs |
| `workspaces/{wid}/exercises/{exerciseId}/{assetId}` | Active workspace members | Trainer | Custom exercise media; system catalog uses separately managed assets |
| `workspaces/{wid}/conversations/{conversationId}/{messageId}/{assetId}` | Conversation participant | Uploading participant; trusted cleanup | Validate conversation participation and message association |
| `workspaces/{wid}/exports/{uid}/{assetId}` | Requesting user only | Trusted server only | Time-limited export/download workflow if implemented |

## Trusted server operations

Cloud Functions are justified for operations that require secrets, cross-document invariants, transactions, fan-out, or privileged cleanup:

1. Create/resend/revoke/accept invitation: own the Admin-only internal record and trainer-readable summary projection; rotate/consume token hash, verify expiry and authenticated email/account binding, and atomically create membership/profile and maintain catalog entitlement on acceptance. The display projection contains no secret fields.
2. Activate/change/revoke/restore membership, transfer ownership, or change workspace lifecycle: enforce role/last-owner and bounded-roster constraints; atomically maintain catalog entitlement as specified above.
3. Book/reschedule/cancel appointment: execute the deterministic lock and receipt protocol below; `confirmed` is returned only after commit.
4. Send reminders/notification fan-out: read current authorization, minimize push payload, handle invalid tokens, and make retries idempotent.
5. Account export/deletion: reauthenticate where required; disable/deletion follows atomic account/entitlement denial before Auth and retention cleanup; membership cleanup maintains count deltas. Apply retention/pseudonymization policy and audit completion.
6. Derived summaries that clients cannot safely update atomically, such as accepted-message conversation summaries.

Ordinary authorized CRUD—profiles, exercise drafts, program drafts, set logs, progress values, and messages—uses Firestore/Storage directly behind repositories. It is not routed through Functions without a demonstrated invariant.

### Booking transaction protocol

The [schema](firestore-schema.md#deterministic-bucket-coverage-and-bounds) owns UTC bucket calculation, policy bounds, lock paths, appointment range descriptors, and receipt IDs. Availability is advisory until this protocol commits. An appointment-range query, even inside a transaction, is not the shared contention mechanism.

1. Authenticate the request and preflight bounded input, explicit local-time/DST resolution, duration/buffers, and total transaction cost. Generate a stable appointment ID outside the retriable callback. Inside the transaction read the caller and participants' accounts, active workspace/memberships, trainer identity/relationship, policy, and deterministic `bookingCommands` receipt. Recheck authorization, ownership, and authoritative bounds; for matching successful retries return the original result without touching locks. Reject key reuse with a different command/payload. A receipt records the original outcome, not the appointment's current status after later cancellation/rescheduling; fetch current authorized state for display.
2. For booking/reschedule, read all required availability rules and blocked periods and validate booking window, rule expansion, and buffered interval. For changes, read the existing appointment/revision and stored old lock range and enforce expected revision and legal transition. Cancellation checks its authorization/cutoff policy without requiring the old interval to remain bookable. Every published availability/block mutation must atomically advance the trainer's `scheduleRevision`; Rules require its next revision via `getAfter`, including for new or deleted records. This makes concurrent policy changes invalidate a booking's read set even if its earlier range query was empty.
3. Point-read **every** required deterministic bucket, including missing documents, before writing. For reschedule read the union of old and new sets; for cancellation read the old set. Reject any new bucket owned by another active appointment. Treat unexplained occupied/missing old locks as inconsistency requiring repair, never silently steal them. A reschedule may retain buckets already owned by the same appointment.
4. In that same transaction acquire/write new locks, retain shared locks, release only old-only locks on reschedule (all old locks on cancellation), write the appointment and new revision, and create the immutable successful receipt. Cancel transitions status and releases locks atomically. Completion or any other transition releasing capacity uses the same rule. No direct mobile appointment/lock create, update, delete, or queued offline write is allowed.
5. Return `confirmed` only after a successful booking commit. Failed/aborted transactions leave no newly confirmed appointment or orphaned locks; a failed reschedule/cancel leaves the prior appointment and locks intact. Retriable callbacks have no external side effects; reminders/fan-out start from committed state. A timeout is unknown until the authorized receipt lookup/retry resolves it. Old successful keys never reacquire locks, including after later cancellation.

Overlapping requests share bucket documents regardless of different appointment IDs or idempotency keys, including the first bookings in an empty range. Transaction conflict/retry makes a later contender observe occupied buckets and fail; **at most** one overlapping booking confirms (both may fail for other reasons). Disjoint trainers/workspaces use distinct lock namespaces.

Cleanup/repair must read the current appointment and affected locks in one transaction with owner/revision checks. Never independently expire/delete locks of a `confirmed` appointment, infer release merely from elapsed wall time, or use TTL to unlock. Terminal appointments may release leftover locks only after validating current ownership; a concurrently reused bucket must survive. Missing locks on confirmed appointments stop booking/trigger audited repair; a repair encountering another owner reports a conflict rather than overwriting. This protocol relies on [Firestore atomic writes, read-before-write ordering, and retried transactions](https://firebase.google.com/docs/firestore/manage-data/transactions#updating_data_with_transactions), but its TillFailure implementation remains unproven.

## Server-side authorization checklist

Every callable/HTTP/task handler must:

- verify Firebase ID token/App Check policy as applicable; never trust UID/body claims alone;
- load current account and resource-specific authority: active workspace/membership/ownership for workspace operations, fixed entitlement for any global catalog endpoint;
- check role and permitted state transition;
- validate immutable fields, bounds, tenant IDs, time zone, and referenced resources;
- use transaction/precondition and an idempotency record for retried commands;
- return the existing result for a repeated idempotency key;
- emit a privacy-safe audit event and structured diagnostic correlation ID;
- avoid logging credentials, ID tokens, invitation tokens, device tokens, health notes, messages, trainer notes, or private media links.

## Revocation, account switching, and cached data

- Membership listeners drive online UI freshness but do not replace server enforcement. Offline staleness is unavoidable; the proposed seven-day maximum eligibility and restricted-recovery behavior are proposals, not approved authorization semantics.
- Sign-out/account switching follows the freeze → inspect/synchronize-or-discard → cancel listeners/jobs → terminate/cleanup → dispose scopes protocol in `offline-sync.md`. A new Koin scope does not isolate persistent Firebase caches, and `terminate` does not cancel pending writes. Another account is not initialized while cleanup is incomplete.
- `clearPersistence` removes cached documents and pending writes but is not secure erasure. It is called only after acknowledgement or explicit discard and after Firestore termination. App-owned journal/manifests/uploads require their own account-scoped cleanup policy.
- Known revocation closes protected navigation but preserves unsynchronized account-owned workout data in locked recovery. Retention/export/discard requires product/privacy approval; it is not an automatic deletion side effect.
- Custom claims, if later used for coarse routing, are hints only because propagation is delayed. Membership documents remain authoritative for workspace roles/data; the atomically maintained account entitlement is authoritative only for global catalog reads alongside active account status.

## Rule and abuse tests required before release

- Cross-workspace reads/writes and forged tenant/owner fields fail.
- Client cannot promote itself, create membership, or edit trainer-only fields.
- Revoked membership loses server read/write access even with stale local UI state; queued stale-revision/revoked writes fail Rules.
- Offline unknown revocation can use only the bounded restricted shell; reconnect locks access and preserves rejected local journal data.
- Direct reads of internal invitation records and client writes to invitation summaries fail; trusted resend/revoke/accept keeps summary status consistent and old tokens unusable.
- Client cannot read private trainer notes, another client’s progress/media, or nonparticipant conversations.
- Trainer cannot access another workspace or unrelated system administration.
- Message sender and conversation participants cannot be forged.
- Appointment/slot/receipt direct writes fail; overlapping first bookings with distinct keys contend on shared buckets and produce at most one confirmation; retry/reschedule/cancel/repair preserve the lock invariant.
- Catalog read/list and atomic entitlement lifecycle denial/allowance tests are specified in [testing-strategy.md](testing-strategy.md#planned-system-catalog-authorization-tests); stale claims/cache never grant access.
- Storage rejects wrong path ownership, oversized/disallowed content, and mismatched metadata.
- Admin-backed Functions deny unauthorized callers independently of Firestore rules.

## Unresolved policy inputs

Retention durations, regional/privacy obligations, trainer access after client revocation, message edit/delete windows, progress-photo launch scope, audit-log retention, App Check rollout, offline eligibility duration, revoked-data recovery/export/discard, and shared-device persistence requirements need legal/product decisions before rules are finalized.
