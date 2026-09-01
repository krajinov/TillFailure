# ADR-001: Firebase client integration

Status: **conditional proposal — approval and native Firebase parity spike required**
Date: **2026-09-01**

## Decision

Provisionally select **official Firebase Android and Apple SDKs behind TillFailure-owned interfaces and platform bindings** for the MVP (option B). The decision becomes accepted only if the spike below proves that the Swift/Kotlin bridge is maintainable and behaviorally matches Android for the exact required APIs.

Feature/domain repository contracts live in `shared/commonMain`. Android Firebase adapters can live in `shared/androidMain`. Apple Firebase is consumed through Swift Package Manager by `iosApp`; Swift implementations satisfy a **small, callback-oriented native bridge**, which `shared/iosMain` adapts to shared repository contracts. This placement is proposed, not proven. Exporting Kotlin `suspend`/`Flow` contracts for Swift to implement is not assumed to be straightforward and is not the bridge design.

This is a mixed setup only in the ordinary sense that initialization, APNs/FCM, Analytics, and Crashlytics must be platform-native. It does not mix two Firebase abstraction libraries for the same product.

## Options compared

| Criterion | A. GitLive Firebase Kotlin SDK | B. Official SDKs + platform adapters |
|---|---|---|
| Maintenance | Active community project; stable `2.6.0` released 2026-08-05, but one additional compatibility owner. | First-party release cadence and support; TillFailure owns small adapters. |
| Required API fit | Must prove Auth observation; document listeners with `fromCache`/`hasPendingWrites`; cache-only/server reads; ordinary write acknowledgement/rejection; listener removal; emulator routing; Storage upload recovery; and any needed `waitForPendingWrites`/termination/cache controls. Native setup is still needed for APNs/FCM, Crashlytics and Analytics. | Vendor APIs document these capabilities, but the project-owned Android and Swift bridges still must prove them end-to-end. |
| Platform parity | One Kotlin-facing API can reduce bridge surface, provided every required metadata/cancellation API exists in `2.6.0`. | Deliberately different native implementations; greater risk of semantic drift across the Swift/Kotlin boundary. |
| Offline behavior | Delegates to native SDKs and exposes persistent-cache settings. | Uses documented native Firestore persistence and native pending-write metadata directly. |
| Testability | Easy to wrap, but Firebase types can leak if used directly. | Project interfaces/fakes make common tests Firebase-free; adapter/rule tests use emulators. |
| Integration effort | Lower common-code effort, extra iOS linking/version alignment. | More adapter code, but native setup is required anyway for notifications/observability. |
| Upgrade risk | Adds GitLive Kotlin metadata plus Android/Apple SDK alignment; `2.6.0` builds against Kotlin 2.2.21, Android BoM 34.17.0, Apple 11.8.0 while current first-party Apple is 12.18.0. | Native SDK changes are isolated behind adapters; no community wrapper lag. |

## Rationale

TillFailure relies heavily on offline Firestore metadata, rejected-write recovery, media tasks, notification registration, Analytics, and Crashlytics. Direct first-party APIs provide the clearest vendor behavior and isolate SDK changes behind adapters, but the Swift bridge cost is material. That trade-off—not overall product-coverage percentages—is why option B is only provisional.

GitLive remains a credible alternative. During the spike, evaluate `2.6.0` against the exact API-fit row above and record any missing or behaviorally different API. If GitLive satisfies the required Firestore/Auth/Storage surface with less lifecycle risk, update this ADR rather than forcing the provisional selection.

## Proposed Apple bridge boundary

- `commonMain`: Firebase-free domain models, repository interfaces, `SyncStatus`, domain failures, use cases, and MVI consumers.
- `iosApp` Swift: initialize Firebase; own Auth observers, Firestore `ListenerRegistration`, write Tasks/async calls, Storage upload tasks, and vendor errors. Implement concrete narrow bridge objects.
- `shared/iosMain`: wrap callback bridge methods with structured coroutines/`callbackFlow`, map stable bridge DTOs/error codes to domain types, propagate cancellation back to Swift, and prevent Firebase/`NSError` types from entering common code.
- Bridge API: concrete non-generic request/response DTOs, explicit completion callbacks, and an idempotent `CancellationHandle`; avoid requiring Swift to implement Kotlin `Flow`, generic sealed types, or `suspend Unit` APIs.
- Startup DI: `iosApp` constructs one `NativeFirebaseBridgeBundle` after `FirebaseApp.configure()` and passes it to the shared Compose entry factory. The shared composition root binds wrappers into the account Koin scope. `shared/iosMain` never initializes Apple Firebase.
- Ownership/lifecycle: the account scope owns a monotonically increasing account epoch; every listener/upload callback captures UID+epoch. Disposal first cancels Swift registrations/tasks where safe, then Kotlin collectors; callbacks after disposal are ignored and may only record sanitized diagnostics.
- Error delivery: Swift maps vendor errors into a small stable category/code plus retryability; `iosMain` maps that into project domain failures. Raw error descriptions are diagnostics-only and privacy-filtered.

The bridge design follows the Compose skill’s Swift-interop guidance: keep the exported API small/concrete, make cancellation explicit, and avoid assuming automatic coroutine/Flow implementation from Swift.

## Planned bindings

| Boundary | `commonMain` contract | Platform implementation/entry responsibility |
|---|---|---|
| Identity | `AuthSessionRepository` | Android adapter in `androidMain`; Swift Auth observer bridge in `iosApp`, wrapped by `iosMain`; app entries initialize Firebase. |
| Documents | Feature repositories and `SyncStatus` | Android Firestore adapter in `androidMain`; Apple Swift binding injected by `iosApp` (interop alternative unresolved). |
| Media | `MediaRepository`, opaque `MediaId` | Android native adapter; Swift Storage bridge + `iosMain` wrapper; picker remains a separate platform service. |
| Push | `PushRegistration`, `NotificationRouter` | Android notification channel/service and iOS APNs delegate; token persistence through repository. |
| Diagnostics | `Diagnostics`, `AnalyticsTracker` | Privacy-filtered Crashlytics/Analytics implementations. |
| Trusted calls | Purpose-specific gateways, e.g. `BookingGateway` | Native callable/HTTPS client only for named trusted operations. Ordinary CRUD remains direct. |

## Consequences

- Adapters must have shared contract tests and mapping tests on each target.
- No cross-platform Firebase DTO is a domain model. DTOs and snapshot parsing remain data-layer details.
- Account switching follows the canonical protocol in [offline-sync.md](offline-sync.md). Disposing Koin does not isolate Firestore persistence; pending writes must be synchronized or explicitly discarded before cache cleanup.
- Apple dependencies use Swift Package Manager; Android uses the Firebase BoM. No Firebase resources are created by this ADR.

## Mandatory spike and decision criteria

In an emulator/non-production environment, prove on both platforms:

1. Auth state observation and cancellation.
2. One Firestore document listener including cache/server and pending-write metadata.
3. One ordinary write covering local visibility, pending state, server acknowledgement, and rejected-write error propagation.
4. Listener removal with no mutation after disposal.
5. Stable error mapping into a common domain failure.
6. Account-scope disposal, late-callback epoch fencing, `waitForPendingWrites`, termination, and cleanup behavior.
7. The same repository contract scenarios on Android and iOS.

Also compare GitLive `2.6.0` for those seven requirements. ADR acceptance requires an iOS application build/launch using the Swift bridge, not only Kotlin/Native tests. If the bridge cannot meet cancellation, metadata, error, or ownership requirements cleanly, ADR-001 remains unresolved.

## Test boundaries

- `shared/commonTest` and shared Kotlin iOS tests validate pure/domain logic and `iosMain` wrappers with fake bridge implementations.
- `iosSimulatorArm64Test` compiles/runs Kotlin/Native tests; it does **not** test Swift implementations that live in the Xcode app.
- Native Swift/Xcode integration tests exercise Firebase bridge objects, callbacks, cancellation, metadata, and error mapping.
- A full iOS application build and launch verifies Swift Package Manager linkage, Firebase initialization, DI injection, Compose hosting, and lifecycle. These are separate evidence items.

## Evidence

- [GitLive 2.6.0 coordinates, coverage, and iOS linking](https://github.com/GitLiveApp/firebase-kotlin-sdk/blob/v2.6.0/README.md)
- [GitLive 2.6.0 build versions](https://github.com/GitLiveApp/firebase-kotlin-sdk/blob/v2.6.0/gradle/libs.versions.toml)
- [Firebase Android setup and BoM](https://firebase.google.com/docs/android/setup)
- [Firebase Apple setup with Swift Package Manager](https://firebase.google.com/docs/ios/setup)
- [Android Firestore lifecycle/persistence API](https://firebase.google.com/docs/reference/kotlin/com/google/firebase/firestore/FirebaseFirestore)
- [Apple Firestore lifecycle/persistence API](https://firebase.google.com/docs/reference/swift/firebasefirestore/api/reference/Classes/Firestore)
