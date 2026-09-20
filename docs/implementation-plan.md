# Implementation roadmap

Status: **Milestone 3 locally accepted; later product milestones require explicit approval**
Review date: **2026-09-20**

Milestones are ordered, reviewable changes. Foundation work may be horizontal; feature milestones must demonstrate an end-to-end user outcome with authorization and recovery, not just screens or repositories. “Commands” are planned verification and may require files that do not exist yet.

## 1. Verified project foundation: `androidApp`, `iosApp`, `shared`

Implementation status (2026-09-01): **implemented and verified with recorded toolchain warnings**. The technical shell, dependency graph, MVI tests, Android runtime flow, shared iOS tests, Xcode build, and Android/iOS cold launches are evidenced in [`milestones/milestone-1-report.md`](milestones/milestone-1-report.md). The user manually passed the interactive Home/Details state-retention and ViewModel disposal/recreation flow on an iPhone 16e simulator; this is user-attributed rather than independently automated. No claim of full process-death restoration is made.

- **User outcome:** Both native apps launch the same minimal shared shell reliably; no product capability is claimed.
- **Affected areas:** Existing `androidApp`, Xcode `iosApp`, `shared`, and version catalog. Keep entry points thin and preserve the Android-KMP library plugin/source-set names. CI remains a separately owned operational decision.
- **Domain/data:** Establish only the MVI conventions, Koin composition boundary, and typed Navigation 3 spike needed by the technical flow. Do not add speculative clocks, IDs, account/workspace contracts, persistence, or Firebase DTOs.
- **Firebase/security:** No Firebase integration or environment work; that is Milestone 3.
- **Tests/commands:** Treat the previously reported sample Gradle commands as limited historical evidence, not a rerun or app-launch result. Capture new milestone evidence for dependency resolution, common/Android/iOS Kotlin tests, Android host build/launch, and Xcode host build/launch after changes. Compile Koin/Navigation 3/coroutines/lifecycle on both targets.
- **Acceptance:** Minimal shared shell launches from Android and Xcode hosts; MVI Route/Screen convention and navigation scope behavior are tested; lifecycle collection and ViewModel scoping pass where the available runtime can exercise them; unresolved platform checks are reported; resolved versions are recorded; no combined `composeApp` or Android app plugin in `shared`.
- **Dependencies/risks:** Navigation/Koin/lifecycle and prerelease Material/lifecycle compatibility. Firebase projects, TypeScript, privacy retention, provisioning, and Apple Firebase bridging do not block this non-Firebase foundation.

## 2. Shared design system and preview fixtures

Implementation status (verified through 2026-09-13): **implemented; acceptance pending screen-reader checks**. Central tokens/components, five deterministic compositions, responsive previews, debug-only catalog access, Android/iOS visual checks, and large-text evidence are recorded in [`milestones/milestone-2-report.md`](milestones/milestone-2-report.md). PR #2 review corrections include responsive five-item navigation and detail tabs; typed appointment, dashboard, workout, and progress presentation; explicit fixture callbacks; contrasting loading indicators; one active-destination snackbar host above bottom bars and safe areas; and four visually and semantically distinct filter-chip states. A focused audit also removed remaining duplicated sample-business values across all five fixtures, with common regression tests for derived labels, fractions, selection, initials, counts, invalid-state normalization, snackbar ownership, and disabled chips. No Firebase, production feature, dependency, deployment, or Milestone 3 work was added.

- **User outcome:** Approved TillFailure components render consistently in shared previews without Firebase.
- **Affected areas:** `shared/core/designsystem`, shared resources, preview fixtures; canonical auth/client/trainer sample screens only as nonfunctional fixtures.
- **Domain/data:** Typed semantic UI models only; no feature persistence.
- **Firebase/security:** None.
- **Tests/commands:** Shared compile/tests plus component screenshots; Android 200% and iPhone accessibility-large responsive checks; 48 dp effective target checks. TalkBack and VoiceOver traversal remain pending and are not claimed.
- **Acceptance:** Dark surfaces, lime accent, typography, spacing/radii and 30 master-state language map to centralized tokens/components; focus/error/loading/disabled states pass; no repeated hardcoded feature tokens or unapproved design change.
- **Dependencies/risks:** Font licensing/bundling, 44 visual vs 48 effective touch target, localization/reduced-motion evidence.

## 3. Firebase environment, native parity, and persistence spike

**Status 2026-09-20:** **locally accepted** with Node 22 emulator and native runtime evidence. ADR-001 is accepted for official Android and Apple SDKs plus the narrow Swift bridge. Corrected booking/entitlement/assignment primitives, Rules, matching metadata transitions, deliberately stalled cancellation/timeout, epoch fencing, and encrypted Android Keystore/Apple Keychain recovery all passed. Production/cloud and physical-device security evidence remains separate. See [Milestone 3 report](milestones/milestone-3-report.md).

- **User outcome:** Developers can run isolated local backend tests and have evidence that the conditional native SDK architecture can support TillFailure safely; no production feature or deployment.
- **Affected areas:** Planned root `firebase.json`; `firebase/functions`; Firestore/Storage rules/indexes; Android/Apple native adapters and configuration boundaries.
- **Domain/data:** Project-owned repositories plus narrow Apple bridge contracts; Android adapter; Swift implementation and `iosMain` wrapper; stable error/cancellation types; prototypes for offline grant, manifest, mutation journal, upload registry and switch marker.
- **Firebase/security:** Emulator-only baseline, deny-by-default rules, Node 22 Functions build, environment/project naming. Do not create/deploy resources without separate authorization.
- **Tests/commands:** Gradle wrapper tests; native Swift/Xcode integration tests; full Android/iOS build and launch; emulator rules/backend commands after their files exist. Run the seven-case parity matrix from ADR-001/testing, including rejected writes, cancellation, pending-write wait, termination/clear, account epoch and process recovery.
- **Architecture proof gate:** Milestone 3 proved deterministic empty-range booking contention, callable-level participant authorization with caller-bound receipts, one immutable `utcBucket` lock schema preserved through reschedule/replay, and fixed-path catalog entitlement Rules/atomic lifecycle primitives from [testing-strategy.md](testing-strategy.md#planned-booking-contention-tests), including configured transaction/write caps and workspace-roster cap enforcement on activation. Complete scheduling implementation, booking-rights policy, and approval of final product timing/cap values remain Milestone 10.
- **Assignment proof gate:** Milestone 3 proved account-owned snapshot authorization, denied client source enumeration, atomic bounded publication/replay, a deterministic inventory containing every created planned-workout path, and revision-checked replacement that retires the predecessor header and its discovery index atomically so exactly one active successor can exist. Full assignment product integration and final caps remain Milestone 7.
- **Acceptance:** Passed locally. Android and actual Swift implementations satisfy the same Auth/listener/write/error/disposal/stalled-wait contract; encrypted journal/manifest storage survives restart and fails closed; the Node 22 emulator suite starts/stops cleanly; no Swift bridge claim relies on `iosSimulatorArm64Test`.
- **Dependencies/risks:** Production Firebase ownership/configuration, approved policy/caps, physical-device protection, shared-device cache policy, App Check and release/deployment controls remain future gates. Native Storage recovery is deferred to the first approved media/upload milestone (9 or 11).

## 4. Authentication, invitations, and role-aware shell

- **User outcome:** A user signs in/restores a session, accepts a valid invitation, completes necessary onboarding and reaches the correct client/trainer shell; expired/revoked access fails safely.
- **Affected areas:** `auth`, `onboarding`, `app/session`, navigation, native links/push registration.
- **Domain/data:** User/workspace/membership/profile/invitation models; session and invitation use cases; account-scoped DI/listeners.
- **Firebase/security:** Auth; user/workspace/membership/profile rules; fixed `users/{uid}/authorizations/systemCatalog` with trusted atomic contribution/count lifecycle; Admin-only internal invitation plus trainer-readable summary projection; trusted create/resend/revoke/accept; token rotation/hash/expiry/account binding/idempotency.
- **Tests/commands:** Domain/MVI plus Auth/Firestore/Functions emulator tests; internal invitation denial/summary allowlist; signed-out/signed-in links; resend old-token failure; role forgery/replay/revocation; offline eligibility; restricted recovery; Android/iOS deep-link smoke.
- **Acceptance:** No cached role authorizes a server request; online restoration verifies membership; bounded offline restoration is restricted; invitation internal fields never reach trainers; consumption is atomic/single-use; reconnect after revocation preserves unsynchronized data; sign-out/switch follows `offline-sync.md`; no sensitive token logging.
- **Entitlement acceptance:** Milestone 3 passed isolated [catalog lifecycle tests](testing-strategy.md#planned-system-catalog-authorization-tests) for multi-workspace retries, first/final contribution, workspace suspension/restoration, account disable/deletion, and account plus workspace membership-cap enforcement on activation and restoration. Milestone 4 must integrate the same bounded lifecycle fan-out from [security](firestore-security.md#system-catalog-entitlement-lifecycle); a delayed trigger/claim is insufficient. Workspace memberships remain role authority.
- **Dependencies/risks:** Approved invitation account/email binding, trainer provisioning, offline eligibility duration/recovery-discard policy, email delivery provider. These decisions block this milestone, not Milestone 1.

## 5. Client management

- **User outcome:** Trainer lists, invites, opens and maintains active clients; client sees only own profile; trainer-only notes remain private.
- **Affected areas:** `clients`, `profile`, trainer dashboard summaries.
- **Domain/data:** Client profile, relationship status, safe trainer/client field ownership, private-note contract.
- **Firebase/security:** Client/trainer profile, membership and private-note rules; invitation function reuse; Storage avatar policy if in scope.
- **Tests/commands:** Mapper/repository/MVI tests; emulator cross-client/workspace, immutable relationship, revoked access and private-note isolation tests; client-list query/index tests.
- **Acceptance:** Trainer cannot access another workspace; client cannot edit trainer fields or read private notes; revocation closes listeners; list/detail empty/error/offline states match design language.
- **Dependencies/risks:** Health-data minimization, private-note offline/cache behavior, profile/photo MVP decision.

## 6. Exercises and program creation

- **User outcome:** Trainer browses system/custom exercises and creates/version-publishes a program template.
- **Affected areas:** `exercises`, `programs` builder/detail.
- **Domain/data:** Exercise source/type, template draft, immutable published version, ordered workout/exercise items, meaningful publish validation.
- **Firebase/security:** Global catalog is read-only for active accounts with the fixed trusted catalog entitlement, and only `published` entries are readable/queryable; source templates/versions and every workout/item descendant are trainer-only within the workspace. Client assignment never grants source reads. Publication transaction/Function only if cross-document invariant proves necessary.
- **Tests/commands:** Domain builder/version tests, MVI input tests, mapping and emulator permission/index tests, builder screenshot/accessibility checks.
- **Acceptance:** Published versions cannot mutate; large content uses child docs, not unbounded arrays; custom exercises are tenant-isolated; retries do not create duplicate versions.
- **Dependencies/risks:** Draft storage model, exercise search requirements, catalog/media ownership and licensing.

## 7. Program assignment

- **User outcome:** Trainer assigns an exact published version; client sees planned workouts without later template edits changing them.
- **Affected areas:** `programs`, `workout` planning, client home/workouts.
- **Domain/data:** Canonical `users/{uid}/workspaces/{wid}/assignedPrograms/{aid}` header, fixed immutable `snapshots/content` copy, account-owned planned instances and server inventory; trainer-only discovery indexes; source IDs/version/hash are provenance, not permission. Local cache manifest remains distinct.
- **Firebase/security:** Trusted [assignment lifecycle](firestore-security.md#assigned-program-authorization-and-lifecycle) atomically publishes all bounded content/plans/indexes/inventory/receipt, with revisioned scheduling and parent-gated revocation. Clients never read original templates/versions/items or write lifecycle fields.
- **Tests/commands:** Complete [snapshot authorization/lifecycle tests](testing-strategy.md#planned-assigned-program-snapshot-tests): allow/deny lists and gets, ownership/source tampering, replay/partial failure, replacement, revocation/expiry, recursive cleanup and offline completeness.
- **Acceptance:** Whole client-safe snapshot exists before activation; new content/reassignment creates new identity. Cancel/archive/revoke/expiry denies descendants; pending local data is preserved only in locked account recovery. Partial/stale/evicted downloads fail completeness; optional media remains separate.
- **Dependencies/risks:** Exact source-size/transaction and planned-horizon caps, expiry/retention policy, clock-integrity handling, and successful Milestone-3 Rules/manifest persistence proof. Full snapshot materialization is selected; chunked publication or lazy source reads require an explicit revised design if bounds cannot fit.

## 8. Active workout, persistence, and recovery

- **User outcome:** Client downloads, starts, logs and completes a workout offline, survives termination, and synchronizes without duplicates.
- **Affected areas:** `workout` Route/Screen/ViewModel/data, session/set repositories, sync UI.
- **Domain/data:** Stable session/set/operation IDs, raw and validated values, server business status versus local completion intent/transport state, `WorkoutMutationJournal`, active-session recovery snapshot and explicit conflict resolution.
- **Firebase/security:** Session/set rules with owner/transition/value validation; indexes; emulator persistence setup. No second database unless a failed requirement is documented.
- **Tests/commands:** Full MVI suite; local-before-send/pending/rejected/restart journal lifecycle; explicit conflict resolution; partial/stale/evicted cache; offline eligible/ineligible startup; duplicate completion; two-device edits; revocation and Locked Recovery; sign-out/switch interruption; Android/iOS smoke and critical visual checks.
- **Acceptance:** Every critical edit enters durable app-owned recovery before UI claims it; rejected payload survives restart; completion distinguishes local intent from server accepted status; resumable session does not depend on retained planned-workout cache; no duplicate session/set; revoked data is preserved but isolated.
- **Dependencies/risks:** Milestone-3 proof of cache metadata, rejection callbacks, journal atomicity/encryption and bridge parity; approved conflict/discard/retention UX. A second persistence technology remains evidence-driven, not prohibited.

## 9. Trainer review and progress

- **User outcome:** Trainer reviews completed workouts and sends client-visible feedback; client records/sees basic progress.
- **Affected areas:** `workout` review, `progress`, dashboard queue.
- **Domain/data:** Review state, visible feedback distinct from private notes, typed progress values, optional asset workflow.
- **Firebase/security:** Feedback/progress rules and indexes; progress Storage boundary if approved.
- **Tests/commands:** Review/progress domain and MVI tests, client/trainer field isolation, offline measurement sync/conflict, media authorization/retry, visual/accessibility checks.
- **Acceptance:** Feedback visibility is explicit; private notes never leak; basic progress works without advanced comparison; pending/failed media is honest and recoverable.
- **Dependencies/risks:** Progress types/units, photo launch scope/consent/retention, trainer edit rights.

## 10. Scheduling

- **User outcome:** Trainer publishes availability; client books/reschedules/cancels an individual appointment and sees confirmation only after the lock/appointment/receipt transaction commits.
- **Affected areas:** `scheduling`, calendar UI, trusted booking gateway.
- **Domain/data:** Availability/block rules, appointment state machine, UTC/time-zone conversion, bounded duration/buffers, fixed slot quantum and deterministic bucket coverage defined in [schema](firestore-schema.md#deterministic-bucket-coverage-and-bounds); command receipts and stored lock ranges.
- **Firebase/security:** Scheduling policy revision guards availability/block changes; direct appointment/lock/receipt mutations denied; trusted Functions use the [atomic acquisition/change/release protocol](firestore-security.md#booking-transaction-protocol); reminder index foundation.
- **Tests/commands:** Complete the [planned booking contention suite](testing-strategy.md#planned-booking-contention-tests), including empty intervals, overlapping buffers, retry, reschedule/cancel races, bounds and repair; Android/iOS calendar/link smoke.
- **Acceptance:** At most one overlapping booking confirms; ambiguous/invalid local times fail or require explicit resolution; receipt retries acquire no duplicate locks; reschedule/cancel release locks atomically; failed transactions and cleanup preserve the invariant.
- **Dependencies/risks:** Select quantum/product durations/buffers/windows/cutoffs within the schema's emulator-validated primitive budgets; approve outward-rounding conservatism. Full scheduling and product-value proof is still required here. External calendars remain deferred.

## 11. Messaging and notifications

- **User outcome:** Trainer and client exchange messages and open safe notifications/deep links; pending/failed delivery is visible.
- **Affected areas:** `messaging`, notifications inbox/router, native FCM/APNs services, media if approved.
- **Domain/data:** Conversation participant model, stable message IDs/order/delivery state, token registration, notification destinations.
- **Firebase/security:** Conversation/message/token/notification rules; trusted notification fan-out and derived summaries; Storage attachments if approved.
- **Tests/commands:** Participant/cross-tenant rules, offline send/order/retry, invalid-token cleanup, duplicate trigger idempotency, terminated/foreground link smoke, push payload privacy review.
- **Acceptance:** Nonparticipants cannot read existence/content/media; push contains no sensitive content; account switch isolates queues; message retry does not duplicate; notification reauthorizes before navigation.
- **Dependencies/risks:** APNs capabilities/signing, message edit/delete/retention, attachments launch scope, background delivery variability.

## 12. Privacy and release hardening

- **User outcome:** MVP is supportable, accessible and safe enough for approved release environments; account lifecycle is explicit.
- **Affected areas:** All modules, CI/release config, privacy/settings, diagnostics, documentation/runbooks.
- **Domain/data:** Export/deletion states, consent/preferences, schema migration/retention jobs, safe analytics vocabulary.
- **Firebase/security:** Full rules regression, App Check decision, least-privilege service accounts, backups/restore/TTL/retention, trusted account deletion, separate environments, budget/alerting.
- **Tests/commands:** Complete test strategy and threat/privacy review; restore/deletion drill; physical device smoke; 200% font/TalkBack/VoiceOver; Crashlytics symbol and sanitized event verification; release builds.
- **Acceptance:** All MVP acceptance checks pass; unavailable platform checks are resolved; secrets/private data never logged; revocation/deletion/restore runbooks pass; post-MVP controls are absent; legal/product sign-offs recorded.
- **Dependencies/risks:** Privacy jurisdiction/health data, retention/deletion obligations, store disclosures, production Firebase approval, operational ownership.

## Review and change discipline

Each milestone should be one or a small sequence of reviewable pull requests with explicit migration and rollback notes. Security rules land with the feature and denial tests; UI does not precede an authorization model. A milestone may not declare success from a visual-only path, emulator-only behavior where native behavior matters, or a snackbar without durable state.

Anything outside the stated MVP—payments/invoices/subscriptions/credits, group sessions, advanced analytics/photo comparison, multi-trainer workspaces, public discovery, AI programs, wearables/nutrition, or desktop/web—requires a new scope decision and must not appear as a nonfunctional release control.
