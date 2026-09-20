# Testing and observability strategy

Status: **Milestone 3 Firebase/native spike checks implemented; later feature strategy remains planned**
Review date: **2026-09-20**

## Milestone 3 executed evidence

The Node 22 emulator backend passed 31/31 tests, including revision-checked single-successor assignment replacement with atomic retirement of both predecessor representations, workspace-roster cap enforcement on membership activation serialized through the expected workspace revision, and immutable `utcBucket` lock identity across booking/reschedule/replay; Android host tests passed 57/57; Android connected tests passed 58/58; iOS simulator shared tests passed 57/57; and the signed Swift debug harness passed Auth, read/write/rejection, cache/pending metadata transitions, transaction, deliberately stalled cancellation and timeout, exactly-once late-completion/epoch fencing, real background/foreground delivery, and terminate/clear. Actual Android Keystore and Apple Keychain/CryptoKit recovery implementations passed encrypted restart, UID isolation, tamper/key-loss, backup-exclusion and atomic-replacement checks. Native Storage process recovery is deferred to Milestone 9 or 11 and is not part of Milestone 3 acceptance. Full commands and attribution are in [Milestone 3 report](milestones/milestone-3-report.md).

## Test layers

| Layer | Scope and examples | Environment |
|---|---|---|
| Pure domain | Validators, role policy, program version rules, set-value parsing, completion rules, time-zone/DST slot generation, buffers, state transitions, idempotency request hashes | `shared/commonTest`, deterministic clock/ID fakes |
| MVI contract | Every meaningful event’s state transition/effect, duplicate click suppression, retry, raw vs validated input, cancellation, loading with retained content, durable success vs transient navigation | `commonTest` with coroutine test scheduler and Turbine; fake repositories, no Firebase |
| Data mapping/contracts | Native DTO ↔ domain mapping, missing/legacy fields, schema versions, repository listener/error/transport semantics | Common contract suite plus `androidHostTest`; `iosTest` tests `iosMain` wrappers with fake Swift bridge contracts, not Swift Firebase implementations |
| Native Apple bridge | Swift Auth/Firestore/Storage implementations, metadata, callback/cancellation/error/ownership behavior | Native Swift/Xcode integration tests against emulator/non-production Firebase |
| Firebase Rules | Read/create/update/delete matrix for every Firestore/Storage path, cross-workspace denial, field immutability, revoked role, private notes, conversation participation, media metadata | Firebase Local Emulator Suite and `@firebase/rules-unit-testing` |
| Cloud Functions | Caller/member authorization, input bounds, invitation acceptance, appointment transactions, idempotency/retry, notification privacy, account cleanup steps | Node 22 unit tests plus Auth/Firestore/Functions emulators |
| Concurrency/recovery | Two simultaneous bookings, duplicate request, overlapping buffer, DST overlap/gap, two-device set edit, offline completion/restart, interrupted upload | Emulator orchestration plus Android/iOS device/simulator integration |
| Platform smoke | App startup, native Firebase initialization, Compose host, lifecycle, notification/deep-link delivery, permission/media binding, account switch | Android emulator/device and iOS simulator/device; some APNs/push checks require physical/signed environments |
| UI/design | Design-system tokens/components/states, Route/Screen boundary, accessibility semantics/focus/touch targets, font scale/reflow, canonical screens against approved Pencil references | Compose previews/screenshot tests where supported; TalkBack and VoiceOver manual/automated checks |

## Critical scenario matrix

Minimum release scenarios:

- Online session restoration routes only after a server membership lookup. Offline restart with a prior eligible UID and fully downloaded workout enters only the restricted workout shell; a first-time/unverified or expired account remains locked.
- A device offline during remote revocation may remain locally eligible until reconnect/bound expiry; reconnect denies backend access, closes protected navigation, and preserves rejected unsynchronized workout data in Locked Recovery.
- Invitation internal records are never client-readable; trainer summaries contain no secret/email-binding fields. Acceptance is single-use/account-email bound; resend invalidates the old token and revoke/accept updates summary status.
- Template v2 does not change an account-owned assignment snapshot copied from v1 or any completed session; clients cannot directly read even the assigned source version.
- Download completeness rechecks every required cached child/revision. Partial download, stale manifest, cache eviction/missing child and never-cached workout cannot start offline; optional media absence is represented separately.
- A resumable active workout survives planned-content eviction and process death from its account-owned recovery snapshot/journal.
- Logged-set mutation survives restart before send, while pending, and after stale-revision rejection. Conflict UI receives durable local plus server payload, records explicit resolution, and retries without duplicate session/set.
- Concurrent overlapping first bookings with different IDs/keys share deterministic bucket documents and produce at most one confirmed appointment; retrying the winner's key returns the original result without acquiring locks again.
- DST gaps/overlaps, duration, buffers, blocked periods, reschedule, and cancellation follow server authority.
- Revoked client and cross-workspace trainer cannot read/write protected documents or media.
- Client cannot read private trainer notes; nonparticipants cannot enumerate/read a conversation or attachment.
- Sign-out/account switch freezes edits and handles pending writes/uploads before Auth change. Offline, sync failure, cleanup failure, process death, and late callbacks cannot expose or mutate the next account; explicit discard is tested as destructive.
- Notification/deep link rechecks access before navigation and does not reveal private content in payload/error.

## Planned booking contention tests

Milestone 3 implements and passes the isolated Firebase Rules/Functions emulator proof of the [bucket schema](firestore-schema.md#deterministic-bucket-coverage-and-bounds) and [trusted transaction protocol](firestore-security.md#booking-transaction-protocol). Milestone 10 must still pass the complete scheduling and product-integration suite with approved timing/cap values.

- Property/domain tests for every supported quantum: any positive overlap between buffered UTC intervals intersects their bucket sets; test first/last boundaries, unaligned starts/buffers rounded outward, midnight/day boundaries, and conservative rejection within a shared bucket. Different trainer/workspace namespaces are independent.
- Run two concurrent first bookings into an empty range with different appointment IDs and idempotency keys. Assert at most one confirms, every confirmed appointment owns its full lock set, and the loser leaves no appointment/locks/receipt. Repeat with overlap caused only by pre/post buffers.
- Retry the winning key concurrently and after timeout; one immutable receipt/appointment results, with no duplicate locks. Changed payload/kind with the same key fails. Replay after cancellation returns the original receipt without recreating locks or misrepresenting current appointment state.
- Reschedule atomically acquires new and releases old-only buckets; retained shared buckets keep the same owner. Race it against another booking/reschedule, including disjoint and partially intersecting ranges. Rejection preserves the entire old appointment/lock set.
- Cancellation/completion atomically changes status and releases only owned locks; race cancel/retry with new booking of released capacity. Failed/aborted transactions and exhausted retries leave no partial state and never report success before commit.
- Validate DST gaps/ambiguous local times, resolved UTC instants, availability, blocked periods, booking window, participant account/workspace/membership, and trainer relationship. Race rule edits and first block creation against booking; `scheduleRevision` must force revalidation.
- Reject unsupported quantum, live quantum changes, excessive durations/buffers/horizon/query size, bucket counts over 90, or complete old/new write/index budgets beyond configured limits. Check preflight rejection and revalidation after policy changes. Measure chosen SDK write accounting in Milestone 3; transaction budgets are a proposal until that proof.
- Rules deny all mobile appointment, lock, and receipt mutations, including offline-queued attempts; availability/block mutations lacking the atomic policy revision update fail.
- Cleanup cannot delete locks owned by a confirmed appointment, including past start/end times. Race repair against cancel/reschedule/reuse; a changed owner survives. Missing/inconsistent locks cause safe failure and an audited repair path.

## Planned system catalog authorization tests

Use the actual Rules read/list checks and trusted lifecycle transactions against isolated Auth/Firestore/Functions emulators, not Admin reads masquerading as client permission tests. Milestone 3 proves the fixed entitlement primitive; Milestones 4 and 6 integrate lifecycle and catalog behavior respectively.

- Unauthenticated reads/lists fail; authenticated accounts with absent/inactive entitlement or count zero fail. Missing/non-active user documents and malformed entitlement count/schema fail closed.
- An active account with active membership contribution and entitlement can read/query `published` system exercises. `draft`/`archived` point reads and unfiltered catalog queries fail.
- Clients cannot create, edit, delete, or self-increment entitlement documents or alter protected membership contributions/role/status and workspace revisions. Client reads/writes to lifecycle receipts fail.
- Account bootstrap has no entitlement grant. First membership activation (trainer bootstrap/invitation acceptance) grants it; final revocation removes it; removing one of multiple workspace memberships retains it.
- Repeated/concurrent activation, revocation, restoration, and role changes preserve exact counts. Retry identical receipts, changed payloads, stale expected revisions, and old commands after an intervening restore; none may underflow, overcount, or revoke another remaining contribution.
- Suspend/archive/delete and restore a workspace while members have other workspaces; its status and all affected contributions/entitlements change atomically. Race new membership activation against the lifecycle scan. Reject exceeding configured roster/account/transaction budgets without partial changes.
- Disable/delete an account through the trusted workflow: account and entitlement deny catalog access in the same commit, including requests using a still-valid old ID token. Fail/retry the subsequent Auth deletion step; access stays denied. Restoration cannot grant a zero-count account access; membership cleanup preserves the tombstone/count invariant.
- Inject lifecycle transaction failure: neither membership/source nor entitlement nor successful receipt partially commits. Missing/corrupt entitlement remains denied during repair; retries and concurrent cross-workspace updates retain correct counts.
- A catalog entitlement grants no access to unrelated workspace data or trainer roles. Existing workspace/cross-client denial cases remain unchanged, and cached entitlement/custom claims neither authorize local writes nor bypass online Rules.

## Planned assigned-program snapshot tests

Milestone 3 passes the isolated [direct path authorization](firestore-security.md#assigned-program-authorization-and-lifecycle) and bounded trusted-transaction proof. Milestones 7/8 still implement the complete assignment lifecycle and native offline behavior. Tests use client SDK/Rules contexts for allow/deny assertions and Admin only to seed fixtures or exercise separately authorized trusted operations.

- Active owning client can get its safe assignment header and ready/active/unexpired snapshot, workout/item children, planned instances and server inventory. Verify exact-collection header lists and identity-constrained descendant queries. Verify the four direct lookups and actual query/multi-read access-call budgets; do not rely on Rules queries or assumed call caching.
- Unauthenticated, different UID/workspace/client, inactive/missing/malformed account/workspace/membership/assignment, wrong role, non-ready parent and expired/terminal parent fail content reads. An eligible owner may still read its own allowlisted terminal header, with no prescriptions or private notes. Snapshot-ID substitutions and unspecified descendant collections fail.
- Every client template, version, workout and exercise-item get/list fails, including its own assigned source, unrelated published versions, guessed IDs, collection-group enumeration and broad recursive matches. Trainer source/index reads require the correct active workspace role. Client reads of workspace trainer indexes and other clients' account-owned data fail.
- Tamper with `workspaceId`, client account UID/`clientId`, assignment ID, snapshot ID, source template/version ID, source revision/hash, trainer/owner fields, eligibility, inventory paths and revision. Deny all mobile assignment/snapshot/plan/inventory/index creates/updates/deletes, including trainer SDK writes. Trusted requests with forged ownership/source workspace/unpublished versions also fail independently of Rules; no Admin caller can bypass handler validation by supplying a plausible header.
- Seed trainer-only notes/internal metadata/private URLs and confirm materialization allowlists exclude them from every readable document and inventory. Required exercise content is copied; optional media checks cannot become a source-template grant.
- Successful assignment atomically creates one header, full immutable snapshot, planned horizon, trainer indexes, inventory and receipt. Concurrent identical retries/timeouts produce one result; changed request/key reuse, stale expected revisions and reused assignment IDs fail. Retry after revocation returns only the original receipt result and does not reactivate content.
- Inject transaction failure/process termination before commit and after commit but before reply. Assert all-or-none publication; recovery reads/retries the same receipt, never creates a second assignment. Reject source-item/horizon/read/write/index-byte caps without partial publication; measure complete replacement cost including predecessor denial/index update.
- Race source publication/archive, schedule changes, replacement and revocation with assignment commands. Source version is pinned; later edits never mutate the copy. Schedule/horizon/inventory/header/index revisions commit together. Replacement creates a new immutable copy and denies the old one in the same transaction; failed replacement preserves the old state. Expiry denies at server time without a worker/TTL.
- Revoke/cancel/archive a parent while descendants still physically exist; all client content reads fail. Account/membership/workspace denial also gates content without fan-out. Restore a suspended relationship only for still-active/unexpired assignments; terminal/expired assignments need new identity. Account deletion denies before Auth cleanup; fail/retry cleanup without reopening access.
- Interrupt recursive cleanup and resume its server-only cursor; remove only the terminal target's children/indexes, preserving tombstone/receipt replay protection and concurrent replacement IDs. Corrupt/missing published items trigger blocked-parent repair; restoration creates a new complete assignment, never enables an incomplete old header. Backup restore must preserve later denial records, not resurrect revoked access.
- Native offline checks: partial download, changed header during download, stale/malicious inventory, missing/evicted child and mismatched hashes fail completeness. Source v2 does not invalidate the cached v1 copy. Known assignment loss/expiry locks content despite active membership; unknown remote loss lasts only through the approved bound/assignment expiry. Clock rollback fails closed. Pending edits survive in UID-isolated recovery, never migrate to another assignment/account. Crash during account switching/cleanup blocks the next account until isolation succeeds.

## MVI test shape

Tests call the single `onEvent` entry point and observe public `StateFlow` and effect Flow. They do not reach into private mutable state. Effects are collected before triggering when delivery timing matters. Example assertions are behavioral: `OnSaveClick` validates raw fields, sets saving state, calls the use case once, retains a durable saved ID/status, then emits navigation. A snackbar alone is never proof that critical data saved.

## Verification commands

The foundation commands were run. Commands explicitly labeled future require absent backend/device-test configuration and were not run.

```text
# Shared Kotlin / Android host / Android app
./gradlew :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest :androidApp:assembleDebug

# Shared iOS simulator
./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:iosSimulatorArm64Test

# Future Android device tests, after androidDeviceTest is configured
./gradlew :shared:connectedAndroidDeviceTest

# Backend build/unit tests, after firebase/functions exists
npm --prefix firebase/functions run build
npm --prefix firebase/functions test

# Rules + emulator integration, exact script to be defined in package.json
firebase emulators:exec --only auth,firestore,storage,functions "npm --prefix firebase/functions test"

# Native iOS application build; substitute an available simulator UDID
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'platform=iOS Simulator,id=<SIMULATOR_UDID>' CODE_SIGNING_ALLOWED=NO build
```

The first two Gradle groups and the Xcode `build` form above were freshly successful for Milestone 1 on 2026-09-01. Android-host execution ran eight tests and iOS simulator execution ran the seven common MVI tests, all with zero failures/skips. Android and iOS simulator apps cold-launched; Android's full technical navigation/scoping flow was exercised. The iOS host survived a Codex-run background/foreground smoke check, and the user subsequently passed the interactive iOS navigation/scoping/background flow manually on an iPhone 16e simulator. The manual result is user-attributed, not independently automated. The Xcode scheme has no application test target. Full evidence, including `NO-SOURCE` and `SKIPPED` tasks, is in [`milestones/milestone-1-report.md`](milestones/milestone-1-report.md).

## Mandatory Firebase parity spike

Before Firebase-backed product features, run the same behavioral contract on Android and the actual Swift/Xcode bridge:

1. Auth state observation and cancellation.
2. One document listener exposing cache/server and pending-write metadata.
3. One ordinary write from local visibility through backend acknowledgement, plus a Rules-rejected write.
4. Listener cancellation and no state mutation after account disposal.
5. Stable error mapping and retryability.
6. `waitForPendingWrites`, termination/cache cleanup sequencing, user-change failure, and late-callback epoch fencing.
7. Process-death recovery for journal, manifest and switch marker; Storage upload recovery separately if media enters scope.

Evidence is recorded separately as: shared Kotlin test result; Android native integration result; Swift/Xcode bridge test result; Android application build/launch; and iOS application build/launch. `iosSimulatorArm64Test` cannot satisfy the Swift bridge or full iOS app evidence rows.

## CI gates

- Pull requests: formatting/static checks selected later, common and Android host tests, Android debug assembly, iOS simulator compile/tests on macOS, TypeScript build/unit tests, rules/emulator suite, and generated configuration/schema consistency.
- Protected release: physical-device smoke tests where needed, signed push/deep-link flows in a non-production project, visual/accessibility regression review, Crashlytics symbol/upload check without a forced production crash, privacy/permissions review, and restore/account deletion drill.
- Firebase emulator tests use isolated project IDs/data, deterministic seeded fixtures and fail-closed rules. No test depends on production data or credentials.

## Visual and accessibility verification

- Central token/component screenshot coverage focuses on the approved dark surfaces, electric-lime accent, type roles, spacing, focus/error/loading/disabled states, workout-set states and both shells.
- Feature screenshot coverage is risk-based: auth, client home, active workout/recovery, trainer dashboard/client detail/program builder, scheduling confirmation, conversation, offline/error/empty states.
- Test 200% font scaling, smallest supported width, long localized strings, IME visibility, scrolling forms, high contrast, reduced motion, 48 dp effective targets, keyboard focus, TalkBack traversal/actions, and VoiceOver labels/traits.
- Golden updates require design review; they are not a mechanism to silently approve design drift.

## Observability

### Crashlytics and diagnostics

- Native apps initialize Crashlytics only under the approved consent/legal policy. Record app/build version, platform, anonymous installation/session correlation ID, feature, operation category, sync state, and sanitized error code.
- Non-fatal diagnostics cover mapping failures, listener termination, sync retries exhausted, upload failures, deep-link resolution, and trusted-operation correlation IDs. Breadcrumbs name actions/categories, not user content.
- Crash-free metrics are split by app/platform version. Alerts focus on startup, active workout data loss/recovery, booking confirmation, sign-in/invitation, and message delivery.

### Privacy-conscious analytics

Use a small reviewed vocabulary such as `sign_in_result`, `invitation_result`, `workout_started`, `workout_saved_offline`, `workout_sync_result`, `appointment_command_result`, and `message_send_result`. Parameters are enumerated states, durations/count buckets and safe feature IDs—not names or content.

Never log or attach credentials, auth/invitation tokens, device tokens, email addresses, health measurements/notes, exercise free text that may contain health data, private messages, trainer notes, raw document snapshots, local file paths, or private media/download links. Debug logging follows the same rule and is disabled/minimized in release builds.

## Exit criteria

No milestone is complete when only fakes pass. Its domain/MVI tests, adapter contract tests, rules/Functions authorization tests, relevant emulator/offline/concurrency cases, platform smoke checks, and accessibility/visual acceptance must pass at the layer the feature actually uses. Unavailable Apple/APNs/device checks are reported and scheduled before release, never waived implicitly.
