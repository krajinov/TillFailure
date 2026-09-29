# TillFailure

**Every rep counts.**

TillFailure is a Kotlin Multiplatform mobile application for personal trainers and their clients.

It replaces fragmented workflows based on messaging applications, spreadsheets, and manually managed calendars with one connected system for:

- client management
- workout programming
- workout execution and set logging
- progress tracking
- personal-training appointments
- trainer-client communication

The application targets Android and iOS and shares both business logic and user interface through Kotlin Multiplatform and Compose Multiplatform.

## Project status

Milestone 3 is locally accepted on branch `feature/milestone-3-firebase-spike`: the Node 22 Local Emulator Suite, corrected trusted transaction/Rules probes, official Android adapter, official Apple SDK plus narrow Swift bridge, deliberately stalled pending-write handling, matching metadata transitions, and encrypted UID-partitioned recovery persistence have runtime evidence. ADR-001 selects the official SDK boundary. This remains emulator/debug spike code, not production Firebase initialization or a Milestone 4 feature. See [Milestone 3 report](docs/milestones/milestone-3-report.md).

Milestone 1, the verified project foundation, is complete. Milestone 2, the shared design system and representative visual fixtures, is implemented for review on `feature/milestone-2-design-system`; its Android/iOS catalog walkthrough, PR #2 P2 corrections (including destination-owned snackbar placement and disabled filter-chip states), and a five-screen typed-model consistency audit were verified through 2026-09-13. Acceptance remains pending the screen-reader checks listed in [`docs/milestones/milestone-2-report.md`](docs/milestones/milestone-2-report.md).

The repository contains a dedicated Android application (`androidApp`), a native Xcode host (`iosApp`), and an Android-KMP shared library (`shared`). Both hosts render the same temporary `Foundation Home -> Foundation Details -> Back` Compose flow.

This flow proves typed Navigation 3, destination-scoped Koin ViewModels, lifecycle-aware state/effect collection, MVI state changes, transient effects, and destination disposal. It is technical demonstration code under `shared/.../foundation` and is explicitly intended for replacement as later product milestones establish the real application shell.

No Firebase-backed product feature, production authentication flow, role selector, deployment, or cloud resource is implemented. Milestone 3 adds only emulator/debug SDK adapters and bounded backend/persistence probes. See the [Milestone 3 report](docs/milestones/milestone-3-report.md) for current evidence and the [Milestone 1 report](docs/milestones/milestone-1-report.md) for foundation evidence.

Debug builds expose **Open development UI catalog** on Foundation Home. The catalog contains centralized TillFailure foundations/components and five deterministic, nonfunctional visual compositions. Android uses `BuildConfig.DEBUG`; iOS uses `_isDebugAssertConfiguration()`, so the entry is not supplied to release hosts. The fixtures do not authenticate, persist data, or bypass a production shell.

The Android technical flow was exercised during implementation, and the interactive iOS navigation/scoping/lifecycle flow was subsequently passed manually by the user on an iPhone 16e simulator. Process-death and durable restoration remain unverified and out of scope.

The complete Client and Trainer experiences have been designed in Pencil. The approved `.pen` design is the visual source of truth for implementation.

Product features and Firebase work require their own explicit milestone approval.

## User roles

### Client

Clients can:

- accept a trainer invitation
- complete onboarding
- view assigned programs
- perform and log workouts
- review workout history
- track progress
- book personal-training sessions
- communicate with their trainer

### Trainer

Trainers can:

- invite and manage clients
- maintain an exercise library
- create program templates
- assign and customize programs
- review completed workouts
- provide feedback
- manage availability and appointments
- monitor client progress
- communicate with clients

## Technology direction

The intended technology stack is:

- Kotlin Multiplatform
- Compose Multiplatform
- Material 3
- MVI architecture
- Kotlin Coroutines and Flow
- Koin
- Firebase Authentication
- Cloud Firestore
- Firebase Storage
- Firebase Cloud Messaging
- Firebase Crashlytics
- Firebase Analytics
- Cloud Functions
- Firebase Local Emulator Suite

Dependency coordinates, versions, target support, and API compatibility must be verified before they are added to the project.

## Verified foundation commands

Prerequisites used for the fresh Milestone 1 checks are JDK 21, Android SDK 36, an Android emulator, and Xcode 26.2 on Apple silicon. Gradle uses the checked-in wrapper.

```text
# Shared metadata, Android-host tests, and Android debug application
./gradlew :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest :androidApp:assembleDebug --console=plain

# Shared iOS simulator compilation and shared tests
./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:iosSimulatorArm64Test --console=plain

# Discover the native Xcode scheme
xcodebuild -list -json -project iosApp/iosApp.xcodeproj

# Build the native host for an available simulator (substitute its UDID)
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'platform=iOS Simulator,id=<SIMULATOR_UDID>' CODE_SIGNING_ALLOWED=NO build
```

The Xcode project currently has an application scheme but no test target. Kotlin/Native tests run through Gradle; do not replace the Xcode `build` command above with `test` unless an Xcode test target is added and verified.

## Architecture

TillFailure follows unidirectional data flow using MVI.

Each non-trivial screen defines:

- an immutable `State`
- a sealed `Event`
- a sealed `Effect`
- a ViewModel exposing `StateFlow<State>`
- a single `onEvent(event)` entry point
- one-shot effects delivered through a buffered `Channel`

The presentation boundary is divided into:

- `FeatureRoute` — obtains the ViewModel, collects state and effects, and connects navigation and platform APIs
- `FeatureScreen` — stateless screen renderer
- reusable leaf composables — render narrow data and expose specific callbacks

Business logic must not be implemented inside composables.

Firebase SDK types must not leak into domain or presentation code.

The intended dependency direction is:

```text
Presentation → Domain ← Data
```

The project follows the external Compose architecture guidance from:

https://github.com/Meet-Miyani/compose-skill

Contributors and coding agents working on Compose code must read and follow that skill before making architectural or implementation changes.

## Planned project structure

```text
TillFailure/
├── androidApp/
├── iosApp/
├── shared/
├── firebase/
├── design/
├── docs/
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
├── firebase.json
└── README.md
```

### `androidApp`

Android application entry point.

Responsibilities include:

- `Application`
- `MainActivity`
- Android manifest
- Android application configuration
- Android Firebase configuration
- Android notification channels
- Android-specific permission handling
- Android platform integrations

This module depends on `shared`.

It must not contain shared domain or feature logic.

### `iosApp`

Native Xcode application entry point.

Responsibilities include:

- Swift application entry point
- shared Compose root integration
- iOS Firebase initialization
- APNs registration
- iOS permission handling
- iOS platform configuration
- Apple-specific lifecycle integration

This application consumes the framework produced by `shared`.

### `shared`

Kotlin Multiplatform library containing shared UI and business logic.

Expected source sets:

```text
shared/src/
├── commonMain/
├── commonTest/
├── androidMain/
├── androidHostTest/
├── androidDeviceTest/  # planned when device tests are configured
├── iosMain/
└── iosTest/
```

`commonMain` contains:

- application shell
- navigation contracts
- design system
- MVI contracts and ViewModels
- domain models
- repository interfaces
- use cases containing meaningful business rules
- Firebase-independent data abstractions
- shared Compose UI
- validation
- synchronization state
- shared resources

`androidMain` and `iosMain` contain only necessary platform implementations and bindings.

Do not use `expect/actual` when an ordinary interface with dependency injection is sufficient.

### `firebase`

Firebase backend configuration and trusted server-side code.

```text
firebase/
├── functions/
├── firestore.rules
├── firestore.indexes.json
└── storage.rules
```

Cloud Functions are reserved for operations requiring trusted execution or transactional consistency, such as:

- invitation handling
- role and membership changes
- trusted assigned-program snapshot materialization and lifecycle
- double-booking prevention
- scheduled reminders
- push-notification fan-out
- protected account deletion
- related-data cleanup

Ordinary safe CRUD operations do not need to be routed through Cloud Functions.

### `design`

Contains the approved Pencil design source.

```text
design/
└── tillfailure.pen
```

The Pencil design is the visual source of truth for:

- colors
- typography
- spacing
- components
- screen layouts
- visual states
- Client navigation
- Trainer navigation

Do not reinterpret or replace the approved design without an explicit product decision.

### `docs`

Contains product and technical decisions.

Expected documentation:

```text
docs/
├── product-scope.md
├── design-inventory.md
├── architecture.md
├── firebase-integration-adr.md
├── firestore-schema.md
├── firestore-security.md
├── offline-sync.md
├── navigation.md
├── dependency-verification.md
├── testing-strategy.md
├── implementation-plan.md
└── open-questions.md
```

## MVP scope

The first release is limited to:

1. Authentication and session restoration
2. Trainer invitation and client onboarding
3. Role-based Client and Trainer shells
4. Client and trainer profiles
5. Client management
6. Exercise library
7. Program templates and program builder
8. Program assignment
9. Client workout overview
10. Active workout and set logging
11. Workout completion and summary
12. Basic workout history
13. Basic progress tracking
14. Trainer workout review and feedback
15. Trainer availability
16. Appointment booking, rescheduling, and cancellation
17. Trainer-client messaging
18. Push notifications
19. Essential offline and synchronization behavior
20. Privacy, account security, and sign-out

The complete Pencil design contains post-MVP functionality and edge states. A designed screen is not automatically part of the initial release.

## Post-MVP scope

The following are intentionally deferred:

- payments
- subscriptions
- invoices
- session packages and credits
- group sessions
- advanced analytics
- advanced progress-photo comparison
- multiple trainers per workspace
- public trainer discovery
- AI-generated workout programs
- wearable integrations
- nutrition tracking
- desktop trainer dashboard

## Security principles

TillFailure handles private client, health, progress, and communication data.

The implementation must enforce:

- workspace isolation
- role-based authorization
- client ownership
- trainer-only note isolation
- private progress-photo access
- immutable ownership identifiers
- protection from privilege escalation
- server-authoritative appointment conflict checks
- deterministic buffered UTC booking locks committed with the appointment and command receipt
- global system-catalog reads gated by an active account and trusted fixed-path entitlement
- client program reads confined to account-owned assigned snapshots, never trainer templates/versions/items
- validated conversation participants
- secure account deletion

A locally cached role must never be treated as authorization.

Firestore Security Rules and trusted server code are required parts of the feature definition, not post-development additions.

## Offline behavior

Workout execution must remain usable with unreliable internet connectivity.

The UI must distinguish:

- local-only
- pending synchronization
- synchronized
- synchronization failed
- retrying
- conflict detected

Appointment booking is server-authoritative and must not display success until the appointment, deterministic slot locks, and idempotency receipt commit together. Milestone 3 implements and emulator-tests the bounded contention and catalog-entitlement primitives defined in [schema](docs/firestore-schema.md) and [security](docs/firestore-security.md); complete product scheduling and identity lifecycle integration remain later milestones. Cached catalog entitlement never authorizes a write or bypasses online Rules.

Assigned programs use the selected `users/{uid}/workspaces/{wid}/assignedPrograms/{aid}/snapshots/content` model: trusted publication atomically creates the immutable client-safe copy, planning records, inventory, trainer indexes and receipt. Client Rules derive account/workspace/membership and parent eligibility directly from that path; source template/version IDs are provenance only. New prescriptions/reassignment require a new copy, and revocation/archive/cancellation/expiry denies old content. Offline cached copies follow [bounded eligibility and locked recovery](docs/offline-sync.md#assigned-snapshot-eligibility), not an evergreen grant. Milestone 3 proved the isolated Rules and trusted-transaction primitive; Milestone 7 still owns complete product integration.

An additional local database must not be introduced merely to duplicate Firestore offline persistence. Any additional persistence technology requires a documented architectural reason.

## Development environments

The project plans separate Firebase projects for:

- development
- production

Firebase configuration files and secrets must not be committed unless they are explicitly safe and intended for source control.

Android and Apple Firebase configuration files are currently tracked at the paths below, but Milestone 3 does not use them for its named demo applications. The spike initializes only guarded loopback/demo clients; these configuration files are not evidence of deployment or production readiness, and ownership/environment/source-control policy still requires review.

Expected local files include:

```text
androidApp/google-services.json
iosApp/GoogleService-Info.plist
```

The Firebase Local Emulator Suite should be used for:

- Authentication testing
- Firestore integration testing
- Security Rules testing
- Cloud Functions development
- Storage Rules testing where supported

## Getting started

Use the verified foundation commands above for shared metadata, Android-host tests/assembly, shared iOS tests, and the native Xcode host build. Milestone 3 backend prerequisites and commands are in [`firebase/README.md`](firebase/README.md); full runtime attribution, warnings, and limitations are in the [Milestone 3 report](docs/milestones/milestone-3-report.md).

Open `iosApp/iosApp.xcodeproj` in Xcode to run the native host application. The Firebase CLI/backend tree is a Milestone 3 local spike and is intentionally separate from the Milestone 1 foundation flow.

## Development rules

Before making changes:

1. Read this README.
2. Read the relevant files in `docs/`.
3. Read and follow the project’s Compose skill.
4. Inspect existing code and conventions.
5. Verify new dependencies before adding them.
6. Keep changes within the requested feature scope.
7. Add or update tests for behavior changes.
8. Update documentation when architecture or data behavior changes.

General rules:

- keep composables free of business logic
- preserve unidirectional data flow
- use immutable screen state
- dispatch UI actions through `onEvent`
- use effects for navigation and other one-shot commands
- keep Firebase behind repository or service interfaces
- do not expose Firebase types outside the data layer
- do not introduce unnecessary abstractions
- do not create a use case for every repository method
- do not add unsupported dependencies to `commonMain`
- do not hardcode repeated design-system values in feature screens
- do not bypass Security Rules during client implementation

## Testing expectations

Critical behavior must be covered at the appropriate level.

Priority areas include:

- invitation acceptance
- role authorization
- program assignment
- health-warning behavior
- active workout state transitions
- workout completion
- offline workout synchronization
- appointment conflict prevention
- private trainer-note isolation
- conversation participant isolation
- account deletion

Tests should favor observable behavior over implementation details.

## Documentation policy

Architecture decisions that materially affect the project must be documented as ADRs.

Examples include:

- Firebase KMP integration approach
- navigation library
- role and membership model
- assigned-program versioning
- offline workout strategy
- notification routing
- account-deletion workflow

Documentation must describe the implemented system. Do not leave architectural documents claiming behavior that the code no longer follows.

## License

License has not yet been selected.
