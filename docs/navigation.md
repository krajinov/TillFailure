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

## Destination hierarchy

| Graph/shell | Destinations | Notes |
|---|---|---|
| Auth | Welcome, SignIn, ForgotPassword | Clears protected stacks; invitation context survives through auth via a safe opaque token reference |
| Invitation | InvitationPreview, AcceptInvitation | Link can arrive signed out or signed in; server validates token/account binding at acceptance |
| Onboarding | PersonalInfo, Goal, Experience, Constraints, Complete | Step state belongs to one onboarding feature/flow; not five unrelated ViewModels by default |
| Client shell | Home, Workouts, Schedule, Progress, Messages, Profile | Bottom destinations retain bounded state per shell; details push above the selected root |
| Client details | WorkoutDetail, ActiveWorkout, WorkoutSummary, HistoryDetail, AppointmentDetail, ProgressEntry, Conversation, Settings | ActiveWorkout restoration is repository-driven; completion clears/replaces the active-workout segment |
| Trainer shell | Dashboard, Clients, Programs, Schedule, Messages, Profile | Role-specific roots; no client-only destination is reachable by forged key without domain authorization |
| Trainer details | ClientDetail, InviteClient, ProgramDetail, ProgramBuilder, AssignProgram, ExerciseLibrary, ExerciseEditor, WorkoutReview, Availability, AppointmentDetail, Conversation, Notifications, Settings | Builder substeps may be child states/sheets, not automatically destinations |

Confirmation dialogs, pickers, filters, snackbars, permission prompts, and most bottom sheets are parent-owned overlays/effects. A sheet becomes a destination only if it needs independent restoration/deep linking or contains a substantial nested flow.

## Deep-link handling

Deep links enter a shared coordinator as a validated, project-owned intent after platform URL parsing:

- Invitation: preserve opaque token; restore/authenticate; server preview/acceptance; then onboarding or correct shell.
- Notification: resolve notification ID after session restoration, recheck current membership, then navigate to a safe destination.
- Message: resolve workspace/conversation IDs, verify participation through repository, then open conversation.
- Appointment/workout: resolve IDs and active relationship; denied or archived content routes to a safe explanation rather than leaking existence.

Android intent filters and iOS Universal Link/custom URL handling remain in their application entry points. Neither platform entry point decides business authorization.

## Session, role, and stack rules

- First sign-in, invitation acceptance, and creation of an offline eligibility grant require connectivity and server verification. Online restoration waits for a server membership result; offline restoration may enter only the restricted workout shell described above.
- Sign-out/account switch is a stateful flow, not an immediate Auth call: freeze edits, inspect app-owned pending mutations/uploads, synchronize/cancel/explicitly discard, fence callbacks, terminate/clean up, dispose scopes, then replace protected stacks. The full protocol and failure/process-death states are canonical in `offline-sync.md`.
- The MVP has one active account per installation and no hot switch. A new repository/ViewModel/navigation scope is created only after departure isolation completes. Koin scope creation does not isolate Firebase persistent cache.
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
