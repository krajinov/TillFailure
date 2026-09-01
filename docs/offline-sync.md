# Offline, synchronization, concurrency, and recovery

Status: **proposed policy with explicit Firebase-spike gates**
Review date: **2026-09-01**

This is the canonical document for offline access, local mutation durability, workout-download completeness, and sign-out/account isolation. Navigation, security, testing, and milestones reference this policy rather than redefining it.

## Evidence labels

- **Verified SDK behavior:** documented by the official Android and Apple Firebase API references.
- **Proposed MVP policy:** recommended TillFailure behavior that still needs product/security approval.
- **Spike-gated:** the desired behavior is not considered feasible until native Android and Apple integration tests prove it.

## Three different kinds of state

Do not collapse these into one Firestore `syncState` field:

| State kind | Owner and examples | Persistence/authority |
|---|---|---|
| Local draft state | Raw weight text, an unsubmitted edit, local completion intent | This device/account only; app-owned durable recovery store for critical workout edits |
| Local transport state | `LocalOnly`, `PendingSync`, `SyncFailed`, `ConflictDetected`; upload progress; Firebase `hasPendingWrites` | This installation/account scope only. Never write it as a shared business field that another device would mistake for its own state. |
| Server business state | Session `in_progress`/`completed`, accepted logged-set revision, appointment `confirmed` | Shared Firestore/server record after Security Rules or trusted server acceptance |

`Synced` means the server acknowledged the particular mutation/revision from this device; it is not a permanent document property. A completion snackbar/effect is never the completion record.

## Offline identity and membership policy

Server-side Firestore/Storage Rules and Functions always authorize requests from current server data. The local policy below only decides whether the app may render previously downloaded content while disconnected; it never authorizes a server request.

| Situation | Required behavior |
|---|---|
| First-time sign-in or invitation acceptance | Online-only. Firebase identity and active membership must be verified by the server before creating an offline eligibility record. |
| Online session restoration | Restore Firebase Auth, then fetch active membership from the server before entering the normal protected shell. Update the local eligibility record only after a server-sourced success. |
| Offline restoration for a previously verified account | Permit a **restricted offline workout shell** only when the persisted Firebase UID matches a locally protected `OfflineAccessGrant`, the grant is within its approved age, membership was not locally observed as revoked, and the workout/session passes local completeness checks. No schedule booking, invitations, membership changes, or other server-authoritative actions. |
| Offline restart without a prior verified account/grant | Do not enter a protected shell. Show sign-in/connectivity guidance; retain any already-present recovery data without exposing it to a different identity. |
| Known revoked membership | Do not open protected content. Enter a restricted recovery state for unsynchronized data; do not silently delete it. |
| Revocation that has not reached an offline device | The device cannot discover it immediately and may continue restricted offline access until reconnect or offline eligibility expires. This limitation must be disclosed in the security model. Server requests are still denied by current Rules/Functions as soon as they reach the backend. |
| Expired offline eligibility | Lock protected content pending online revalidation. Preserve unsynchronized recovery records; do not interpret expiry as proof of revocation or permission to erase them. |
| Reconnect | Revalidate membership promptly. Active membership restores the normal shell and permits synchronization. Revoked/changed membership closes protected navigation; backend rejection is expected and the mutation journal preserves rejected workout edits for the restricted recovery flow. |

**Proposed MVP bound:** allow restricted offline workout access for at most seven days after the last successful server membership verification. Seven days is a recommendation, not an approved product rule; product/security may choose a shorter duration. The `OfflineAccessGrant` should contain UID, workspace ID, role, membership revision/status, server-verified timestamp, proposed expiry, and local schema version in OS-protected app storage. It is a local eligibility record, not a credential or membership authority.

When access is lost with unsynchronized workout data, the simplest safe MVP behavior is a locked recovery screen that shows non-sensitive counts/timestamps and offers: reconnect/retry under the same account, retain until policy resolution, or explicit destructive discard after warning. Export and support recovery are product/privacy decisions. A different account must never receive the payload.

## Operation classification and write primitive

| Operation | Offline class | Primitive and authority |
|---|---|---|
| Load assigned/next workout | Offline only after cache verification | Server reads while online; cache-only point reads at offline start; app-owned manifest is a hint/index, not proof |
| Create/resume session | Offline, queued | Stable-ID ordinary write plus durable local recovery record; server Rules accept/reject |
| Log/edit/delete set | Offline, queued | Stable-ID ordinary write with `operationId`, `baseRevision`, next revision and tombstone where needed; Rules reject stale transitions |
| Complete workout | Offline completion intent, queued | Durable local intent then an idempotent batched write if session and final set mutations must commit together; server-accepted session status is authority |
| Progress measurement/text | Offline, queued | Ordinary stable-ID write; conflict-capable fields require the same journal/revision approach |
| Text message | Offline, queued | Append-only ordinary write with stable message ID; server timestamp reconciles ordering |
| Media upload | Transfer requires connectivity | App-owned upload record/file plus native Storage task; attach asset ID only after server acceptance |
| Template publish | Online | Online transaction or trusted operation if immutable version creation/current pointer cannot be safely enforced as a bounded batch |
| Book/reschedule/cancel, invitation acceptance, membership/role change, deletion | Online/server-authoritative | Purpose-specific trusted Function with transaction and idempotency key |

Firestore mobile transactions are not the offline mutation mechanism. Batched writes may queue offline but do not solve stale-revision recovery by themselves. The Firebase spike must prove the exact callback and metadata behavior used by each adapter.

## Reliable workout download completeness

### Manifest ownership and contents

The download manifest is an **app-owned durable record scoped by Firebase UID + workspace + planned-workout ID**, not a Firestore business document and not a boolean in screen state. Its exact storage technology is spike-gated. It contains:

- manifest schema version, account/workspace/planned-workout IDs;
- assignment ID/revision and immutable template/program version ID;
- every required Firestore document path/ID plus expected schema/revision or content hash: planned-workout header, workout snapshot, exercise items, prescriptions, and any required exercise definitions;
- download attempt/completion timestamps and the last successful server membership verification reference;
- optional asset descriptors and per-asset availability; videos/images are optional offline unless product marks a specific asset required;
- no Firebase SDK object, token, or private URL.

### Establishing and rechecking completeness

1. While online, fetch each required document from the server, validate referential/schema integrity, then read it through the native cache path and compare the expected revision/hash.
2. Persist the manifest only after all required items pass. A saved `complete=true` is never sufficient evidence on its own.
3. Immediately before offline startup, issue cache-only point reads for every required item and compare identity/revision/hash. A query result alone may be incomplete, and Firestore can evict older cached documents when its configured threshold is exceeded.
4. If any item is missing, mismatched, partially downloaded, or unparsable, mark the manifest incomplete and refuse a new offline start with a precise “download required” state. When online, repair only missing/stale items and revalidate the whole required set.
5. Invalidate on assignment/version change, required-item revision/schema change, account/workspace change, app migration requiring a new snapshot shape, explicit cache cleanup, failed integrity check, or local eviction discovered by a cache probe. An age limit may prompt refresh but must not by itself claim that files are gone.

A **downloaded workout** is a planned workout whose required cache probe passes. A **resumable active session** additionally has an app-owned recovery snapshot/journal containing the exact prescription and local edits needed to resume even if planned-workout cache entries are later evicted. **Optional offline media** is independently available and never determines resumability unless product explicitly makes it required.

## Durable mutation journal: recommended, implementation spike required

Native Firestore persistence reliably queues writes, but a rejected optimistic write can lose its local overlay; it is not a project-owned conflict archive. TillFailure therefore recommends a small app-owned `WorkoutMutationJournal` for critical workout/session mutations. This is an architectural requirement, not a dependency selection. The spike must choose the smallest KMP-compatible durable implementation (for example an atomic app file or a verified structured store). Room, SQLDelight, or a second general database is not added by default, but additional persistence is allowed if tests demonstrate that simpler storage cannot meet recovery/atomicity requirements.

Alternatives considered:

| Option | Assessment |
|---|---|
| Firebase cache only | Simplest, but insufficient for durable presentation of a server-rejected local payload after restart. Not selected for conflict-capable workout edits. |
| Immutable operation documents reconciled by server | Robust audit/replay but adds backend model and operational complexity. Defer unless Rules-based revision writes cannot meet requirements. |
| Small app-owned mutation journal | Recommended MVP direction: preserves only critical local payloads/statuses and leaves Firestore as the business database. Exact storage and encryption remain spike-gated. |

### Logged-set mutation lifecycle

1. **Local edit:** `FeatureState` holds raw input; validation produces a typed value without destroying the raw text.
2. **Local persistence:** before showing “saved on this device,” atomically upsert journal entry `{accountId, workspaceId, sessionId, setId, operationId, payload, baseRevision, createdAt, transportState=LocalOnly}`.
3. **Pending synchronization:** issue the Firestore write with stable document/operation ID; mark the journal `PendingSync`. The adapter observes native pending-write metadata, but the journal remains the recovery source.
4. **Server acceptance:** a server-sourced snapshot/read with `hasPendingWrites=false` and matching `lastOperationId`/revision, or the verified write acknowledgement contract, marks the journal entry accepted. It may then be compacted after a short recovery window.
5. **Stale-revision rejection:** Security Rules reject when `baseRevision` does not match the server revision/transition. Keep the journal payload, fetch the server value, and mark `ConflictDetected`; do not overwrite either value.
6. **Process restart:** load journal entries for the exact UID/workspace before restoring an active session. A pending entry remains pending until reconciliation proves acceptance or rejection; absence of a callback is not success.
7. **Conflict presentation:** show the local and current server values with safe context and explicit choices. No automatic last-write-wins for the same set.
8. **Resolution:** `Keep local` creates a new operation based on the now-current server revision; `Use server` records explicit discard/resolution and removes the local payload only after confirmation. A merge is offered only for independently mergeable fields.
9. **Retry:** use a new resolution operation ID or the same idempotent transport retry as defined by the adapter; never create a duplicate set/session.

The Firebase spike must prove that Rules can enforce the proposed revision transition on ordinary mobile writes, how each SDK reports asynchronous rejection after restart, and how accepted `lastOperationId` is observed. If this fails, Milestone 8 remains blocked while immutable operation reconciliation is evaluated.

## Session and completion meanings

- Server session `status=in_progress` means the backend has accepted an active session.
- Local `completionIntent` in the journal means the user finished locally; it may exist while the server still says `in_progress`.
- Server session `status=completed` and `completedAt` mean the completion mutation was accepted. Trainer review queries only this server business state.
- Native `hasPendingWrites` and journal transport status describe this installation’s delivery state. Remove `syncState` from shared session documents.
- Completion retry uses the stable session/operation ID and a legal monotonic transition. A duplicate accepted completion returns/observes the same completed outcome.

## Process-death recovery

On restart, identify the persisted Firebase UID before opening account-scoped data; validate the offline grant; load the active-session recovery snapshot and journal; perform cache completeness checks; and then show Resume, Locked recovery, or Connect to revalidate. Pending media records retain only durable app-owned file references. No ViewModel, navigation state, or Firebase callback is the recovery source.

If access becomes known revoked, preserve the journal and local upload records in the locked account partition. Do not reopen full workout content, synchronize under another user, or silently delete. Retention/export/discard behavior remains a product/privacy approval.

## Safe sign-out and account-switch protocol

### Verified Firebase SDK behavior

- Android and Apple `clearPersistence` remove cached documents **and pending writes**. It must run before Firestore starts or after `terminate`; it is primarily a test/reset API and does not securely overwrite cached bytes.
- `terminate` releases the Firestore instance but **does not cancel pending writes**. Awaiting server tasks do not resolve; a restarted instance resumes sending those writes.
- `waitForPendingWrites` confirms backend acknowledgement only for writes pending when called (including older-session writes). Later writes require another call. An outstanding wait fails/cancels if the authenticated user changes.
- Firestore persistence is enabled by default on Android/Apple, caches actively used data, and may evict older unused documents at its size threshold. A new Koin scope does not isolate or erase this SDK cache.

Sources: [Android FirebaseFirestore API](https://firebase.google.com/docs/reference/kotlin/com/google/firebase/firestore/FirebaseFirestore), [Android cache/server `Source`](https://firebase.google.com/docs/reference/kotlin/com/google/firebase/firestore/Source), [Apple Firestore API](https://firebase.google.com/docs/reference/swift/firebasefirestore/api/reference/Classes/Firestore), [Apple `FirestoreSource`](https://firebase.google.com/docs/reference/swift/firebasefirestore/api/reference/Enums/FirestoreSource), and [Firestore offline behavior](https://firebase.google.com/docs/firestore/manage-data/enable-offline).

### Proposed simplest safe MVP policy

TillFailure should support one active Firebase account per installation at a time and no instant “hot switch.” Switching means completing the same controlled departure as sign-out, then signing in the next account. With unresolved critical workout mutations, normal sign-out/switch is blocked until the user synchronizes or explicitly discards; this avoids a complex multi-account queue.

Sequence:

1. Freeze the departing account (`SwitchingOut` marker with UID and monotonically increasing account epoch); stop accepting new edits.
2. Inspect the app journal and upload registry. Because Firebase exposes a wait but not a complete application-level pending-write inventory, TillFailure must track its own critical mutations/uploads.
3. Offer **Stay and synchronize**, **Cancel sign-out**, or—where product/privacy policy permits—**Discard local unsynchronized changes and sign out** with an explicit destructive warning. Offline/sync failure cannot complete the synchronize path.
4. For synchronize: finish/cancel media as selected, call `waitForPendingWrites` before changing Auth user, and reconcile journal acknowledgements. Freeze ensures no later writes are accepted. For discard: cancel app jobs/uploads, then proceed to destructive local cleanup; `terminate` alone is not discard.
5. Remove native listener registrations, cancel account jobs, and gate every late callback by captured UID + account epoch. A mismatched/disposed callback is ignored and may only emit sanitized diagnostics.
6. Sign out Auth, `terminate` Firestore, and apply the approved cleanup: `clearPersistence` only after synchronization or explicit discard; delete account-owned journal/manifests/media files and secrets as policy allows. Cache clearing failure leaves the app in locked `CleanupRequired` state and blocks another account.
7. Dispose Koin/repository/ViewModel/navigation scopes and replace protected stacks with Auth only after callbacks are fenced and cleanup reaches a durable terminal state.
8. Initialize/sign in the next account only after the persisted switch marker says isolation completed.

If the process terminates mid-switch, the durable `SwitchingOut` marker is read before constructing any account graph. Resume cleanup/reconciliation for the departing UID; never initialize the next account concurrently. If the device is offline or sync fails, the user may cancel, keep the locked pending partition, or explicitly discard if approved. Cleanup failure is retryable and blocks switching. Cache clearing is not secure erasure because the SDK does not overwrite storage and the OS, backups, or filesystem may retain recoverable blocks; highly sensitive shared-device requirements may instead require disabling persistence, which is a separate product/security decision.

## Booking time rules

- Store authoritative start/end instants plus originating IANA `timeZoneId`, duration, and explicit pre/post buffers.
- Expand availability in the rule’s zone/effective dates; validate daylight-saving gaps/overlaps server-side. Ambiguous local time requires an explicit resolved instant.
- A server transaction rechecks membership, blocks, appointments and buffers at commit. The client displays `Submitting` until the trusted operation returns a committed appointment.
- Timeout is an unknown outcome; query by idempotency key before retrying. Store the confirmed instant and original local/zone metadata because time-zone rules can change.

## Required acceptance scenarios

- Restart offline with a currently eligible account and fully downloaded workout; start/resume succeeds without a server claim.
- Restart offline without a prior verified account/grant; protected content remains locked.
- Reconnect after server-side revocation that the device could not know offline; backend writes fail, navigation closes, and journal payloads remain in restricted recovery.
- Known revocation/expired eligibility with unsynchronized workout data never silently deletes or exposes the payload to another account.
- Partial download, stale manifest, missing/evicted child document, schema/revision mismatch, and offline restart all fail completeness safely; repair succeeds only after full revalidation.
- Logged-set write survives restart before send, while pending, and after stale-revision rejection; both local/server values reach conflict UI and explicit resolution retries correctly.
- Sign-out/switch while online and clean, while offline with pending work, after sync failure, after cleanup failure, and across process death follows the protocol; late callbacks cannot mutate the next account.
- Android and Apple adapters prove matching Auth observation, cache/source metadata, write acknowledgement/rejection, `waitForPendingWrites`, termination/clear behavior, listener cancellation, and account-scope disposal before Firebase-backed features begin.
