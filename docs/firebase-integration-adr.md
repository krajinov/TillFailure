# ADR-001: Firebase client integration

Status: **accepted — official Android SDK + official Apple SDK + narrow Swift bridge**
Date: **2026-09-20**

## Decision

Select **official Firebase Android and Apple SDKs behind TillFailure-owned interfaces and platform bindings** for the MVP (option B). Milestone 3 proved the narrow Swift/Kotlin bridge and Android adapter through the same emulator-backed runtime surface.

Feature/domain repository contracts live in `shared/commonMain`. Android Firebase adapters live in `shared/androidMain`. Apple Firebase is consumed through Swift Package Manager by `iosApp`; Swift implementations satisfy a **small, callback-oriented native bridge**, which `shared/iosMain` adapts to shared repository contracts. This placement is implemented and runtime-proven for the spike surface. Exporting Kotlin `suspend`/`Flow` contracts for Swift to implement is not the bridge design.

This is a mixed setup only in the ordinary sense that initialization, APNs/FCM, Analytics, and Crashlytics must be platform-native. It does not mix two Firebase abstraction libraries for the same product.

## Options compared

| Criterion | A. GitLive Firebase Kotlin SDK | B. Official SDKs + platform adapters |
|---|---|---|
| Maintenance | Active community project; stable `2.6.0` released 2026-08-05, but one additional compatibility owner. | First-party release cadence and support; TillFailure owns small adapters. |
| Required API fit | Source exposes Auth observation, get/listen/write/transactions, metadata, typed rejection codes, `waitForPendingWrites`, termination, cache clearing, and emulator routing. | The same Auth/Firestore/lifecycle surface passed through both native runtimes. Storage upload recovery was not evaluated. |
| Platform parity | One Kotlin-facing API can reduce bridge surface, provided every required metadata/cancellation API exists in `2.6.0`. | Deliberately different native implementations; greater risk of semantic drift across the Swift/Kotlin boundary. |
| Offline behavior | Delegates to native SDKs and exposes persistent-cache settings. | Uses documented native Firestore persistence and native pending-write metadata directly. |
| Testability | Easy to wrap, but Firebase types can leak if used directly. | Project interfaces/fakes make common tests Firebase-free; adapter/rule tests use emulators. |
| Integration effort | Lower common-code effort, extra iOS linking/version alignment. | More adapter code, but native setup is required anyway for notifications/observability. |
| Upgrade risk | Adds GitLive Kotlin metadata plus Android/Apple SDK alignment; `2.6.0` builds against Kotlin 2.2.21, Android BoM 34.17.0, and CocoaPods Apple SDK 11.8.0. | Native SDK changes are isolated behind adapters; Milestone 3 verified Android BoM 34.19.0 and Apple SPM 12.19.2. |

## Rationale

TillFailure relies heavily on offline Firestore metadata and rejected-write recovery. Direct first-party APIs provide the clearest vendor behavior and isolate SDK changes behind adapters. The Swift bridge cost is material but remained small and concrete in the spike.

GitLive remains a credible alternative and its stable `2.6.0` common API covers the required Auth/Firestore surface. It was not selected because it does not remove TillFailure-owned fencing/persistence policy and its exact stable build adds a wrapper compatibility owner while lagging the verified Apple SDK/integration model. Revisit if that alignment changes materially.

## Apple bridge boundary

- `commonMain`: Firebase-free domain models, repository interfaces, `SyncStatus`, domain failures, use cases, and MVI consumers.
- `iosApp` Swift: initialize Firebase; own Auth observers, Firestore `ListenerRegistration`, write Tasks/async calls, Storage upload tasks, and vendor errors. Implement concrete narrow bridge objects.
- `shared/iosMain`: wrap callback bridge methods with structured coroutines/`callbackFlow`, map stable bridge DTOs/error codes to domain types, propagate cancellation back to Swift, and prevent Firebase/`NSError` types from entering common code.
- Bridge API: concrete non-generic request/response DTOs, explicit completion callbacks, and an idempotent `CancellationHandle`; avoid requiring Swift to implement Kotlin `Flow`, generic sealed types, or `suspend Unit` APIs.
- Startup DI: `iosApp` constructs one `NativeFirebaseBridgeBundle` after `FirebaseApp.configure()` and passes it to the shared Compose entry factory. The shared composition root binds wrappers into the account Koin scope. `shared/iosMain` never initializes Apple Firebase.
- Ownership/lifecycle: the account scope owns a monotonically increasing account epoch; every listener/upload callback captures UID+epoch. Disposal first cancels Swift registrations/tasks where safe, then Kotlin collectors; callbacks after disposal are ignored and may only record sanitized diagnostics.
- Error delivery: Swift maps vendor errors into a small stable category/code plus retryability; `iosMain` maps that into project domain failures. Raw error descriptions are diagnostics-only and privacy-filtered.

The bridge design follows the Compose skill’s Swift-interop guidance: keep the exported API small/concrete, make cancellation explicit, and avoid assuming automatic coroutine/Flow implementation from Swift.

## Bindings

| Boundary | `commonMain` contract | Platform implementation/entry responsibility |
|---|---|---|
| Identity | `AuthSessionRepository` | Android adapter in `androidMain`; Swift Auth observer bridge in `iosApp`, wrapped by `iosMain`; app entries initialize Firebase. |
| Documents | Feature repositories and `SyncStatus` | Android Firestore adapter in `androidMain`; concrete Swift Firestore bridge injected by `iosApp` and adapted in `iosMain`. |
| Media | `MediaRepository`, opaque `MediaId` | Android native adapter; Swift Storage bridge + `iosMain` wrapper; picker remains a separate platform service. |
| Push | `PushRegistration`, `NotificationRouter` | Android notification channel/service and iOS APNs delegate; token persistence through repository. |
| Diagnostics | `Diagnostics`, `AnalyticsTracker` | Privacy-filtered Crashlytics/Analytics implementations. |
| Trusted calls | Purpose-specific gateways, e.g. `BookingGateway` | Native callable/HTTPS client only for named trusted operations. Ordinary CRUD remains direct. |

## Consequences

- Adapters must have shared contract tests and mapping tests on each target.
- No cross-platform Firebase DTO is a domain model. DTOs and snapshot parsing remain data-layer details.
- Account switching follows the canonical protocol in [offline-sync.md](offline-sync.md). Disposing Koin does not isolate Firestore persistence; pending writes must be synchronized or explicitly discarded before cache cleanup.
- Apple dependencies use Swift Package Manager; Android uses the Firebase BoM. No Firebase resources are created by this ADR.

## Completed spike and decision criteria

The emulator/non-production spike proved on both platforms:

1. Auth state observation and cancellation.
2. One Firestore document listener including cache/server and pending-write metadata.
3. One ordinary write covering local visibility, pending state, server acknowledgement, and rejected-write error propagation.
4. Listener removal with no mutation after disposal.
5. Stable error mapping into a common domain failure.
6. Account-scope disposal, late-callback epoch fencing, `waitForPendingWrites`, termination, and cleanup behavior.
7. The same repository contract scenarios on Android and iOS.

GitLive `2.6.0` was compared against those requirements. ADR acceptance includes a signed iOS application build/launch using the Swift bridge, not only Kotlin/Native tests. A deliberately stalled pending-write timeout/cancellation remains a milestone limitation, but the API exists and successful completion/cancellation fencing passed.

## Test boundaries

- `shared/commonTest` and shared Kotlin iOS tests validate pure/domain logic and `iosMain` wrappers with fake bridge implementations.
- `iosSimulatorArm64Test` compiles/runs Kotlin/Native tests; it does **not** test Swift implementations that live in the Xcode app.
- Native Swift/Xcode integration tests exercise Firebase bridge objects, callbacks, cancellation, metadata, and error mapping.
- A full iOS application build and launch verifies Swift Package Manager linkage, Firebase initialization, DI injection, Compose hosting, and lifecycle. These are separate evidence items.

## Evidence

Milestone 3 evidence is recorded in [the milestone report](milestones/milestone-3-report.md). Android instrumentation and the actual signed Swift implementation passed Auth observation, accepted/server reads and writes, Rules rejection mapping, metadata listeners, transactions, cancellation, pending-write drain, epoch fencing, and terminate/clear. Swift also passed a real Settings background/foreground transition. The accepted decision does not claim production-ready encrypted persistence or native Storage recovery.

- [GitLive 2.6.0 coordinates, coverage, and iOS linking](https://github.com/GitLiveApp/firebase-kotlin-sdk/blob/v2.6.0/README.md)
- [GitLive 2.6.0 build versions](https://github.com/GitLiveApp/firebase-kotlin-sdk/blob/v2.6.0/gradle/libs.versions.toml)
- [Firebase Android setup and BoM](https://firebase.google.com/docs/android/setup)
- [Firebase Apple setup with Swift Package Manager](https://firebase.google.com/docs/ios/setup)
- [Android Firestore lifecycle/persistence API](https://firebase.google.com/docs/reference/kotlin/com/google/firebase/firestore/FirebaseFirestore)
- [Apple Firestore lifecycle/persistence API](https://firebase.google.com/docs/reference/swift/firebasefirestore/api/reference/Classes/Firestore)
