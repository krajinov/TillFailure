# Firebase authorization and security plan

Status: **proposal; rules and resources do not yet exist**
Review date: **2026-09-01**

## Authorization model

Authentication answers who the caller is; the active membership document answers what the caller may do in a workspace. Every workspace rule must read `workspaces/{workspaceId}/memberships/{request.auth.uid}` and require `status == "active"`. A locally cached role, navigation shell, custom claim, or hidden UI is never authorization.

Roles for MVP:

- `trainer`: manages the single-trainer workspace, clients, programming, availability, review, and trainer-only notes.
- `client`: accesses only their membership/profile, assigned content, own sessions/progress, their appointments, and conversations they participate in.
- Trusted backend: uses Admin SDK only after repeating authentication, membership, role, ownership, input, and idempotency checks. Admin SDK bypasses Firestore rules.

Membership role/status/tenant IDs and immutable owner IDs are protected by field allowlists. Role creation, role changes, ownership transfer, and revocation are trusted operations. Revocation takes effect for server requests as soon as current Rules/Functions evaluate it. An offline device cannot discover a remote revocation immediately and may render previously downloaded content under the bounded local eligibility policy in [offline-sync.md](offline-sync.md); that local grant never authorizes a server request. On reconnect, denied pending writes remain recoverable through the account-owned mutation journal rather than being silently deleted.

## Firestore permission matrix

`Own` means the authenticated UID matches the immutable owner/client field. `Participant` means the conversation/appointment contains the UID and the membership is active.

| Path | Read | Create | Update | Delete/archive | Required validation |
|---|---|---|---|---|---|
| `users/{uid}` | Own | Trusted account bootstrap | Own safe fields; status/email linkage trusted | Trusted lifecycle | Exact UID; field allowlist; bounded strings/enums |
| `users/{uid}/deviceTokens/{id}` | Own; trusted sender | Own | Own safe token metadata; trusted invalidation | Own/trusted | UID, platform, installation ownership; token never publicly readable |
| `users/{uid}/notifications/{id}` | Own | Trusted fan-out | Own `readAt`/archive only | Own/trusted retention | Recipient immutable; safe destination/type payload |
| `workspaces/{wid}` | Active member | Trusted owner creation | Trainer safe metadata; ownership/status trusted | Trusted | Active membership; owner immutable |
| `.../memberships/{uid}` | Self or active trainer | Trusted invitation acceptance | Trusted role/status; self may only safe preferences if any | Trusted | UID/tenant/role protected; no role escalation |
| `.../trainerProfiles/{uid}` | Active members | Own active trainer | Own safe fields | Trusted/archive own if allowed | Active trainer membership and matching UID |
| `.../clientProfiles/{uid}` | Own client or active trainer | Trusted onboarding/acceptance | Own client-safe or trainer-safe field sets | Trusted | Matching client membership; trainer relationship; health-field bounds |
| `invitations/{id}` | **Admin SDK/trusted endpoint only; no mobile read** | Trusted | Trusted create/resend/revoke/accept/expire | Trusted retention | Internal email binding and token hash/version; expiry, single-use and idempotency |
| `workspaces/{wid}/invitationSummaries/{id}` | Active workspace trainer | Trusted projection only | Trusted projection only | Trusted retention | Allowlists masked email, role, status and timestamps; contains no token/hash/internal email key |
| `systemExercises/{id}` | Authenticated active member | Admin only | Admin only | Admin only | Published/status filter; no client writes |
| `.../exercises/{id}` | Active member | Trainer | Trainer | Trainer archive | Immutable tenant/owner; bounded normalized fields/media refs |
| `.../programTemplates/{id}` and versions/workouts/items | Trainer; assigned client reads only published version referenced by their assignment | Trainer | Trainer drafts/metadata; published versions immutable | Trainer archive; trusted cleanup | Ownership, version state, bounds, exercise references; client cannot enumerate unrelated templates |
| `.../assignedPrograms/{id}` | Matching client or trainer | Trainer | Trainer; narrowly allowed client acknowledgement if required | Trainer archive | Immutable client/trainer/version/tenant; active relationships |
| `.../plannedWorkouts/{id}` | Matching client or trainer | Trainer/trusted materializer | Trainer scheduling/status allowlist; client session-linked acknowledgement only | Trainer archive | Immutable snapshot/version/participants; state transition validation |
| `.../workoutSessions/{id}` | Matching client or trainer | Matching client | Client legal business-status fields; trainer review fields in disjoint allowlist | Archive by policy | Immutable participants/plan refs; monotonic status, `baseRevision`/revision/operation ID; no client-authored shared transport state |
| `.../loggedSets/{id}` | Session client/trainer | Session client | Session client under edit policy | Client tombstone under policy | Parent ownership; position/value/status bounds; Rules compare `baseRevision` to existing revision and require next revision/operation ID |
| `.../feedback/{id}` | Session client/trainer | Trainer | Authoring trainer | Trainer archive | Visibility fixed to client; trainer/session relationship |
| `.../progressEntries/{id}` | Matching client/trainer | Matching client | Matching client; trainer comment only if separate rule | Client archive | Immutable client; type-specific allowed fields and numeric bounds; media ownership |
| `.../privateTrainerNotes/{id}` | Authoring active trainer only | Trainer | Authoring trainer | Authoring trainer/archive | Never client-readable; subject must be trainer’s active client |
| `.../availabilityRules/{id}` | Active workspace participants as needed for booking | Trainer | Trainer | Trainer archive | Trainer owner, valid local intervals/zone/effective dates |
| `.../blockedPeriods/{id}` | Trainer; clients receive derived available slots, not reasons | Trainer | Trainer | Trainer archive | Valid intervals; reason not exposed to clients |
| `.../appointments/{id}` | Participant | Trusted booking | Trusted reschedule/cancel | Trusted archive/retention | Server transaction, active membership, availability, buffers, transition, idempotency |
| `.../conversations/{id}` | Participant | Trusted or trainer-client validated create | Participant safe metadata only | Participant-specific archive/trusted | Exact permitted pair; participant IDs immutable and bounded |
| `.../messages/{id}` | Participant | Participant | Sender within edit policy | Sender tombstone/trusted retention | Parent participation; sender UID; bounded content; owned attachments; immutable owner fields |

Rules will use explicit allowed-key and affected-key checks, type/length/range validation, immutable field comparisons, and parent-document checks. They will not rely on client-supplied denormalized role fields.

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

1. Create/resend/revoke/accept invitation: own the Admin-only internal record and trainer-readable summary projection; rotate/consume token hash, verify expiry and authenticated email/account binding, and atomically create membership/profile on acceptance. The projection contains no secret fields.
2. Change/revoke membership or transfer ownership: enforce last-owner and role constraints and terminate downstream access.
3. Book/reschedule/cancel appointment: transactionally check rule expansion, blocked periods, existing buffered appointments, participant status, and idempotency key before returning `confirmed`.
4. Send reminders/notification fan-out: read current authorization, minimize push payload, handle invalid tokens, and make retries idempotent.
5. Account export/deletion: reauthenticate where required, revoke tokens/memberships, apply retention/pseudonymization policy, and audit completion.
6. Derived summaries that clients cannot safely update atomically, such as accepted-message conversation summaries.

Ordinary authorized CRUD—profiles, exercise drafts, program drafts, set logs, progress values, and messages—uses Firestore/Storage directly behind repositories. It is not routed through Functions without a demonstrated invariant.

## Server-side authorization checklist

Every callable/HTTP/task handler must:

- verify Firebase ID token/App Check policy as applicable; never trust UID/body claims alone;
- load active membership and resource ownership from the database;
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
- Custom claims, if later used for coarse routing, are hints only because propagation is delayed. Firestore membership documents remain authoritative.

## Rule and abuse tests required before release

- Cross-workspace reads/writes and forged tenant/owner fields fail.
- Client cannot promote itself, create membership, or edit trainer-only fields.
- Revoked membership loses server read/write access even with stale local UI state; queued stale-revision/revoked writes fail Rules.
- Offline unknown revocation can use only the bounded restricted shell; reconnect locks access and preserves rejected local journal data.
- Direct reads of internal invitation records and client writes to invitation summaries fail; trusted resend/revoke/accept keeps summary status consistent and old tokens unusable.
- Client cannot read private trainer notes, another client’s progress/media, or nonparticipant conversations.
- Trainer cannot access another workspace or unrelated system administration.
- Message sender and conversation participants cannot be forged.
- Appointment direct writes fail; concurrent trusted bookings produce at most one confirmation.
- Storage rejects wrong path ownership, oversized/disallowed content, and mismatched metadata.
- Admin-backed Functions deny unauthorized callers independently of Firestore rules.

## Unresolved policy inputs

Retention durations, regional/privacy obligations, trainer access after client revocation, message edit/delete windows, progress-photo launch scope, audit-log retention, App Check rollout, offline eligibility duration, revoked-data recovery/export/discard, and shared-device persistence requirements need legal/product decisions before rules are finalized.
