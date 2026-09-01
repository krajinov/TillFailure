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

TillFailure is currently in the architecture and project-foundation phase.

The complete Client and Trainer experiences have been designed in Pencil. The approved `.pen` design is the visual source of truth for implementation.

Production features should not be implemented until the initial architecture, Firebase model, security strategy, and MVP plan are reviewed.

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

## Project structure

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
├── androidUnitTest/
└── iosMain/
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

Appointment booking is server-authoritative and must not display a successful booking until conflict checking completes.

An additional local database must not be introduced merely to duplicate Firestore offline persistence. Any additional persistence technology requires a documented architectural reason.

## Development environments

The project uses separate Firebase projects for:

- development
- production

Firebase configuration files and secrets must not be committed unless they are explicitly safe and intended for source control.

Expected local files include:

```text
androidApp/google-services.json
iosApp/iosApp/GoogleService-Info.plist
```

Exact locations may be adjusted during project scaffolding to match the generated Android and Xcode structures.

The Firebase Local Emulator Suite should be used for:

- Authentication testing
- Firestore integration testing
- Security Rules testing
- Cloud Functions development
- Storage Rules testing where supported

## Getting started

Project scaffolding and verified dependency versions have not yet been finalized.

After the foundation is created, this section must document:

1. required JDK version
2. supported Android Studio version
3. supported Xcode version
4. Firebase CLI requirements
5. Node.js requirements for Cloud Functions
6. Android Firebase configuration
7. iOS Firebase configuration
8. emulator startup
9. Android build and run commands
10. iOS build and run instructions
11. test commands
12. code-quality commands

Do not add guessed version requirements or commands before they are verified against the generated project.

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

This is a Kotlin Multiplatform project targeting Android, iOS.

* [/iosApp](./iosApp/iosApp) contains an iOS application. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/shared](./shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./shared/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./shared/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./shared/src/jvmMain/kotlin)
    folder is the appropriate location.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Android app: `./gradlew :androidApp:assembleDebug`
- iOS app: open the [/iosApp](./iosApp) directory in Xcode and run it from there.

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :shared:testAndroidHostTest`
- iOS tests: `./gradlew :shared:iosSimulatorArm64Test`

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…