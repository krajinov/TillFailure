# Navigation and platform boundaries

Status: **Milestone 1 foundation navigation implemented and build-verified; product navigation remains proposed**
Review date: **2026-09-01**

## Navigation choice

Use JetBrains Compose Multiplatform Navigation 3 UI, `org.jetbrains.androidx.navigation3:navigation3-ui:1.1.1`, in `shared/commonMain`. Its official 1.1.1 release supports Android and iOS. Typed, serializable project-owned `NavKey` values describe destinations; domain objects, Firebase snapshots, tokens, and private message bodies are never navigation arguments.

The foundation integrates this approach on Kotlin 2.4.10 and Compose Multiplatform 1.11.1. Product root/session/deep-link behavior below remains a proposal for later milestones.

## Milestone 1 foundation result

The temporary shell uses serializable `FoundationHome` and `FoundationDetails` keys, `rememberNavBackStack` with an explicit `SavedStateConfiguration`, and `NavDisplay`. `rememberSaveableStateHolderNavEntryDecorator` retains destination UI saveable state and `rememberViewModelStoreNavEntryDecorator` owns destination ViewModel stores. Each entry obtains its ViewModel with Koin's `koinViewModel()`; the optional Koin Navigation 3 adapter was not required.

Android runtime checks demonstrated Home state retention after opening and popping Details, system-back handling, Details ViewModel release on pop, and a fresh Details instance on the next navigation. On 2026-09-01 the user manually verified the equivalent state-retention, Details disposal/recreation, and background/foreground behavior on an iPhone 16e simulator. That iOS result is user-attributed, not independently automated. Back-stack serialization is configured, but process-death restoration and durable feature state were not claimed or tested.

## Root state machine

```text
Launch
  -> RestoreFirebaseIdentity
     -> NoPersistedIdentity/Auth
     -> PersistedIdentity
        -> OnlineMembershipCheck
           -> PendingInvitation/Auth or AcceptInvitation
           -> NeedsOnboarding/Onboarding
           -> MultipleValidMemberships/WorkspaceChoice
           -> ActiveClient/ClientShell
           -> ActiveTrainer/TrainerShell
           -> NoActiveWorkspace/WorkspaceGate
           -> Revoked/AccessLost
        -> OfflineEligibilityGate
           -> EligibleAndDownloaded/RestrictedOfflineWorkoutShell
           -> NoGrantOrExpired/ConnectToVerify
           -> KnownRevokedOrPendingRecovery/LockedRecovery
```

Online protected navigation is driven by server-sourced membership. A splash timeout or cached role must not choose the normal shell. The restricted offline shell is a local availability decision for a previously verified UID, bounded grant, and cache-verified workout—not server authorization. An offline device cannot immediately discover remote revocation; reconnect revalidates and server Rules independently deny stale requests. The canonical policy and proposed seven-day bound are in [offline-sync.md](offline-sync.md).

Milestone 4 issues no offline grant because its duration is unapproved and no downloaded workout exists. Its offline branch therefore reaches `ConnectToVerify`, or Locked Recovery for unresolved UID-owned data; the restricted workout shell remains a later feature. Trainer provisioning is operator-only; mobile navigation can show pending status and recheck membership but cannot initiate the privileged command.

## Destination hierarchy

| Graph/shell | Destinations | Notes |
|---|---|---|
| Auth | Welcome, SignIn, ForgotPassword | Clears protected stacks; invitation context survives through auth via a safe opaque token reference |
| Invitation | InvitationPreview, AcceptInvitation | Link can arrive signed out or signed in; server validates token/account binding at acceptance |
| Onboarding | PersonalInfo, Complete; Goal, Experience, Constraints only after product/privacy approval | Step state belongs to one onboarding feature/flow; health/constraint collection is not approved for Milestone 4 |
| Client shell | Home, Workouts, Schedule, Progress, Messages, Profile | Bottom destinations retain bounded state per shell; details push above the selected root |
| Client details | WorkoutDetail, ActiveWorkout, WorkoutSummary, HistoryDetail, AppointmentDetail, ProgressEntry, Conversation, Settings | ActiveWorkout restoration is repository-driven; completion clears/replaces the active-workout segment |
| Trainer shell | Dashboard, Clients, Programs, Schedule, Messages, Profile | Role-specific roots; no client-only destination is reachable by forged key without domain authorization |
| Trainer details | ClientDetail, InviteClient, ProgramDetail, ProgramBuilder, AssignProgram, ExerciseLibrary, ExerciseEditor, WorkoutReview, Availability, AppointmentDetail, Conversation, Notifications, Settings | Builder substeps may be child states/sheets, not automatically destinations |

Confirmation dialogs, pickers, filters, snackbars, permission prompts, and most bottom sheets are parent-owned overlays/effects. A sheet becomes a destination only if it needs independent restoration/deep linking or contains a substantial nested flow.

## Deep-link handling

PR 3 supports manual invitation entry in the shared Android/iOS flow using the reserved emulator `.invalid` format defined in [the local procedure](milestones/milestone-4-pr3-invitations.md). Tokens stay in a process-only pending-link coordinator across sign-in, verification and a controlled wrong-account departure, and are cleared on acceptance. They never enter typed navigation/saved state. Restart requires reopening the original link; a protected safe command identity allows same-key reconciliation of an ambiguous acceptance. Associated domains, Universal Links and production delivery remain gated. The acceptance response only requests fresh server membership verification and stops at the existing client feature-pending root.

Deep links enter a shared coordinator as a validated, project-owned intent after platform URL parsing:

- Invitation: preserve opaque token; restore/authenticate; server preview/acceptance; then onboarding or correct shell.
- Notification: resolve notification ID after session restoration, recheck current membership, then navigate to a safe destination.
- Message: resolve workspace/conversation IDs, verify participation through repository, then open conversation.
- Appointment/workout: resolve IDs and active relationship; denied or archived content routes to a safe explanation rather than leaking existence.

Android intent filters and iOS Universal Link/custom URL handling remain in their application entry points. Neither platform entry point decides business authorization.

## Session, role, and stack rules

- First sign-in, invitation acceptance, and any future offline eligibility grant require connectivity and server verification. Online restoration waits for a server membership result; Milestone 4 issues no grant, so offline restoration cannot enter a protected shell.
- Sign-out/account switch is a stateful flow, not an immediate Auth call: PR 1 allows only proven clean departure on both hosts; PR 5 adds validated original-command recovery and Locked Recovery admission before reattempting the same clean departure. Destructive discard requires separate approval. Fence callbacks, terminate/clean up, dispose scopes, then replace protected stacks. The full protocol and failure/process-death states are canonical in `offline-sync.md`.
- The MVP has one active account per installation and no hot switch. A new repository/ViewModel/navigation scope is created only after departure isolation completes. Koin scope creation does not isolate Firebase persistent cache.
- A Debug-only development catalog opens as a separate visual-fixture root from a stable identity gate; the entry is hidden during busy departure and cleanup. It never receives the product identity client or selects a role; returning to the product root resolves the current native client generation, creates a new gate and repeats online authorization. Release hosts do not expose the catalog entry.
- Membership revocation or role change replaces the entire role shell after it is server-observed. Rejected unsynchronized data remains in UID-scoped Locked Recovery rather than being silently deleted. A trainer-to-client role change cannot preserve trainer detail screens.
- Back from the root shell follows platform convention; back never returns to onboarding/auth after successful transition. Invitation tokens are removed from navigation state after consumption.
- Process restoration serializes only safe stable keys plus an account-switch recovery stage outside navigation. A destination reloads server-authorized data online or locally eligible downloaded data offline.

## MVI route boundary

Each non-trivial destination exposes a `FeatureRoute` that obtains/scopes the ViewModel, lifecycle-collects `StateFlow`, collects buffered effects, and executes navigation/snackbar/permission/platform actions. `FeatureScreen` receives immutable state and `onEvent`; leaf composables receive narrow values/callbacks. Navigation is triggered by `FeatureEffect.Navigate...` handled by the route, while durable saved outcomes remain in state/repository data.

Foundation routes use `collectAsStateWithLifecycle` for state and one `LaunchedEffect(viewModel, lifecycleOwner)` plus `repeatOnLifecycle(STARTED)` for effects. Android navigation/back and iOS foreground/background smoke checks passed. Full product-shell lifecycle, deep-link, and process-restoration behavior remains a later-milestone gate.

## Platform responsibility map

| Concern | `androidApp` | `iosApp` | `shared` source sets/common |
|---|---|---|---|
| App entry/lifecycle | `Application`, `MainActivity`, manifest, OS callbacks | Swift `App`, hosting controller, app/scene delegates as required | Common app shell; shared lifecycle-facing abstractions |
| Firebase initialization | Android config and SDK startup | `FirebaseApp.configure()`, Swift SDK implementations and injected native bridge | Project interfaces; Android adapters in `androidMain`; callback/coroutine wrappers in `iosMain` |
| Notifications | FCM service/intent, channel creation, permission request | APNs registration/delegate and FCM token bridging, notification permission | Token repository, notification routing, safe destination intents |
| Deep links | Intent filters and URI extraction | Associated domains/URL callbacks | Parsing of app-level route intent, repository authorization, navigation |
| Media selection | Activity-result integration and URI permission/copy | Photos/file picker and durable local copy/bookmark policy | Project-owned `MediaPicker` interface, validation, upload state |
| Permissions | Android runtime permission APIs | Apple authorization APIs/Info.plist descriptions | Permission state abstraction and rationale UI |
| Secure storage | Keystore-backed implementation | Keychain-backed implementation | Minimal secrets interface; Firebase tokens are managed by SDKs where possible |
| Crash/analytics | Native SDK wiring and consent | Native SDK wiring and consent | Privacy-safe event vocabulary and diagnostic context |

Shared `expect/actual` implementations that are actually Kotlin-owned belong in `shared/src/androidMain` and `shared/src/iosMain`. Under accepted ADR-001, Apple Firebase implementations are Swift objects in `iosApp`; `iosMain` only adapts their narrow injected bridge. OS entry points/configuration stay thin, and `iosApp` remains an Xcode application consuming the framework rather than a Gradle app module.

## Verification scenarios

Later product-navigation tests must cover signed-out invitation; signed-in invitation for matching/mismatched account; online restoration; offline restart with downloaded workout; offline restart without a prior grant; expired offline eligibility; reconnect after previously unknown revocation; Locked Recovery preserving unsynchronized data; role change; sign-out/switch cancellation, cleanup failure and process-death resumption; late callback fencing; and unauthorized deep links. Milestone 1 covers only the technical Home/Details flow, Android back/scoping, and Android/iOS hosting/lifecycle smoke behavior. Firebase bridge behavior is a separate Milestone-3 integration suite.

## PR 4 emulator routing

The live gate now routes server-pending clients to minimal onboarding and server-complete clients to client Home. Trainers enter trainer Home with invitations. Multiple verified memberships require an explicit, freshly revalidated selection; encrypted workspace preference only sorts options. Home and Profile are the only shell tabs. Current gate state overrides saved keys, and UID/workspace/role/authority epochs dispose and fence destination state. Background closes role content until online foreground verification. Protected completion/invitation recovery stays reachable and clean departure remains blocked while a command is unresolved. These are emulator fixtures while product/privacy approval is open; see [the PR 4 report](milestones/milestone-4-pr4-report.md).

## PR 5 recovery routing

Protected storage is inspected before role verification. Unreadable, missing pinned, incompatible or malformed protected records reach Locked Recovery; retained journal/upload/download state requires reconciliation rather than a new Milestone 4 feature. Access loss and offline verification failure with a pending command also reach Locked Recovery, with original invitation/onboarding retry accessible under that UID. Retryable service failure, current authority denial and storage/reconciliation failure have distinct copy. No recovery response chooses a shell.

Background and network loss close protected presentation; only the host foreground event resumes a background root. Command retries/completions cannot mark it foreground. Native reconnect callbacks repeat Auth/server proof while foreground; duplicate and disposed-observer events cannot revive older proof. Final role publication is serialized with the existing departure owner and rejects proofs from an earlier departure generation. Connectivity is a trigger only. Reconnect cannot bypass a departure marker or cleanup lock. A missing selected workspace remains gated until the user explicitly chooses another and that choice verifies online. Child roots remain keyed by UID/workspace/role/authority epoch; late callbacks and restored navigation keys cannot restore access. See the [integrated matrix](milestones/milestone-4-pr5-report.md).

## Milestone 5 PR 1 Clients routing

Trainer shells add Clients beside Home/Profile and retain the existing invitation destination separately. Clients and minimal client details use typed Navigation 3 keys and destination-owned Koin MVI ViewModels. Saved shell keys carry UID/workspace/trainer/role/authority epoch; mismatched restored ownership is replaced before rendering. Client IDs are navigation identity only and details always reload through the guarded endpoint.

Names, pages and cursors are transient. No response data enters saved navigation or protected command storage. Refresh clears prior target presentation and starts a fresh traversal; page failure preserves loaded rows and the original continuation for retry, while invalid cursors discard pages and request refresh. Background/scope disposal clears state, cancels requests and drains queued navigation; generation checks fence late replies. Current gate loss replaces the shell through existing identity mechanisms. This adds no recovery queue or offline grant. See [verification and remaining gaps](milestones/milestone-5-pr1-report.md).

## Milestone 5 PR 2 connected invitations

Clients exposes Invite client and Invitations and recovery. Trainer invitation entry uses the existing root-owned InvitationViewModel and protected ledger; no second feature store is created. Home/Profile invitation shortcuts enter the same Clients-owned route. Typed keys contain only owner scope, entry mode and a refresh version, never invitation replies, emails or bearers. Invitation visits and shell-page replacements advance the scope-owned navigation version across Home/Profile transitions. Queued navigation requires its captured source, and invitation callbacks/disposal require the current visit as well as UID/workspace/authority and confirmation identity. Duplicate entry and stale Back are guarded. Native Back, Back to Clients and the Clients tab replace the roster destination store so returning always requests a fresh authorized page, including during interrupted transitions. Explicit summary refresh discovers acceptance; Recheck active clients returns through that same fresh read. No new listener or reply-to-roster insertion exists.

Form, confirmed result and the labeled newest-20 summary list remain separate from active clients. Resend/revoke require the existing confirmation, then reload summaries through the trusted list. Leaving clears transient draft/confirmation/delivery/list state and fences callbacks; native settlement continues against the original protected command. Root disposal also clears transient presentation and rejects reentry. Pending recovery stays under the existing parent owner and remains reachable from the gate after authority loss. Account/workspace/authority changes retain their existing root fences. See [PR 2 evidence and limitations](milestones/milestone-5-pr2-report.md).

## Milestone 5 PR 3 basic-profile editor

Client Profile offers Edit basic profile using a typed EditProfile destination with UID/workspace/role/authority ownership and a distinct visit on reopening. The single root-owned Koin ProfileViewModel shares editor and recovery presentation; it uses the existing protected command service. Initial/reload values come from a fresh trusted own-profile read. Save does not grant a role or insert roster data. Back and Home/Profile tab departures route through MVI unsent-draft confirmation; stale source/visit/prompt callbacks cannot navigate or affect a newer scope. The consumer rechecks the live editor owner/visit and effect sequence, consuming it once; an already received effect cannot survive background clearing or disposal. Saved NavKeys contain no basics or submitted payload.

Conflict prevents another Save until explicit reload; replacing a modified draft requires its current confirmation and occurs only after a successful read. Bounded unsent drafts are transient and are cleared on destination/scope/background retirement. Submitted commands stay in the existing encrypted account slot with their original fields/revision/key, including after root/process recreation or authority loss. Root recovery remains reachable without the original workspace grant; replay still requires fresh server eligibility. On return, only scope-fenced freshly read own basics may refresh the existing Profile presentation, without replacing its root or changing role/workspace/authority. No trainer detail editing is added.

## Milestone 5 own-command recovery entry

The existing Invitations/Locked Recovery surface now exposes confirmed Resolve for any of the original six protected variants (and the PR 4 seventh variant below) when original identity and binding permit. It distinguishes Retry from Resolve and shows pending, applied/not-applied, unavailable, binding-required, identity-required and storage failure guidance. Confirmation is bound to the exact command and prompt visit; UID/workspace/authority changes retire it. Terminal proof is repository-owned, never saved navigation state or role authority. The [implementation report](milestones/milestone-5-command-resolution-report.md) records actual interaction evidence separately from native harnesses.

## Milestone 5 PR 4 client access revocation

Fresh authorized client details prepare eligibility before showing “Revoke client access to this workspace”. Confirmation uses the existing design-system dialog, authorized target/workspace display and scope/authority epoch/detail visit/revision/prompt ownership. Pending, conflict, unavailable and unknown-outcome states preserve the original command; duplicate or stale actions cannot replace it. After durable success, a consumption-fenced effect clears detail and recreates the authorized roster destination for a fresh read. Refresh failure cannot resubmit a settled command. Product navigation retains stable store-provider defaults per owner and the required saved-state decorator, so ledger-only recomposition cannot dispose a command callback. Exiting entries cannot issue access-lost navigation for a newer destination. Back immediately retires the revocation prompt, and pending navigation fences queued confirmation before disposal. Background/disposal retire presentation; the native ledger still settles and Invitations/recovery supports Retry/Resolve of the seventh variant. Live affected-client denial keeps Auth signed in and issues no automatic Change workspace action; the existing explicit action re-verifies another eligible workspace. See the [PR 4 report](milestones/milestone-5-pr4-report.md).

## Milestone 5 PR 5 private-note editor

Eligible trainer client details open a typed TrainerNote destination containing only owner/authority/client/visit IDs. A destination-owned Koin MVI model uses the existing root command service. Owner, current top destination, visit, request generation and consumed-effect sequence fence events, callbacks and navigation, including actions queued after Back but before disposal. Root-wide navigation stores retain their existing stable defaults/decorator ordering.

Dirty Cancel/Back/tab exits require Leave/Cancel confirmation; reload requires confirmation and replaces a dirty foreground draft only after a successful fresh read. Conflict blocks another Save until explicit reload. Background retires the root/editor and loses unsent text; foreground re-verifies and a new entry reads current saved text. Known denial clears the body. Submitted original commands remain recoverable from Invitations/Locked Recovery without exposing note text or restoring a role. The multiline field is limited to eight visible lines; its body scrolls within the field and page actions remain in the surrounding scrollable layout. The [report](milestones/milestone-5-pr5-report.md) qualifies the final long-text/keyboard/touch evidence.

## Milestone 6 PR 1 Exercises

The verified trainer shell exposes working Exercises Home and bottom-navigation entries. Typed list/detail destinations save only owner/epoch/visit and validated exercise identity; pages/cursors/text remain transient. Detail is a fresh point read. Header/system Back creates a new list visit/read, and current-destination/owner/generation/effect-consumption checks reject exiting or stale callbacks. Client shells expose no Exercises entry; global catalog authorization remains its existing distinct predicate. Background/root retirement clears the feature and foreground requires fresh authority. No search, custom/editor/Programs or assignment affordance is added. See the [PR 1 report](milestones/milestone-6-pr1-report.md).


## Milestone 6 PR 2 custom exercises

The trainer Exercises list has a working My custom exercises entry; system browsing stays read-only. Custom list/detail/editor destinations carry only owner scope, visit and optional UUID. Lists, preparation, details and editor text/taxonomy are transient destination MVI state. The same author/workspace must freshly authorize each request; Back/Refresh recreates the relevant list/detail visit. Saved keys do not persist drafts or confer access.

Dirty editor Cancel/Back/tab exits prompt Leave/Stay. Explicit correction conflict requires Reload and confirms dirty replacement; failed fresh reads preserve a still-authorized foreground draft. Create collision offers explicit Start new only after durable settlement, with a new ID/key, never implicit correction. Applied Save navigates only after durable settlement and loads current detail freshly. Known denial/background/scope retirement clears presentation; native command settlement continues in the existing UID ledger. Original Retry/confirmed Resolve stays in existing Invitations/recovery without displaying submitted text or granting a role. Received effects, stale dialogs and callbacks require the current owner/top destination/visit/generation and one-time consumption. See [contract](milestones/milestone-6-pr2-plan.md) and [qualified verification](milestones/milestone-6-pr2-report.md).

## Milestone 6 PR 3 program drafts

The verified trainer shell adds Programs; client shells have no entry. One destination-owned MVI flow contains the current-draft list, editor and system/custom picker with fresh source preview. Navigation persists only owner/epoch/visit; content and numeric raw input remain transient. Explicit Save is distinct from picker selection and leaving. Dirty Leave/Stay, source repair and explicit conflict Reload are owned by the current foreground destination. Reorder uses stable IDs and accessible move controls; no drag-only operation. ON_STOP, scope/root retirement and known draft-authority denial clear presentation while native settlement continues independently. Submitted leave does not cancel a command; clean account departure still waits for settlement. No Publish, Assign, version, archive or transfer control exists. [Evidence and remaining interaction gates](milestones/milestone-6-pr3-report.md) are separate from builds/harnesses.
