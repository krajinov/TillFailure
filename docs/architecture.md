# TillFailure architecture

Status: **Milestone 1 foundation implemented; product/data architecture remains proposed**
Reviewed guidance: `compose-skill` commit `982c240e47718b3b0525c5bbe85bf19ff0bb7bec` (2026-04-06), complete `SKILL.md`, mandatory `references/mvi.md`, and routed `references/architecture.md`, `references/accessibility.md`, `references/ios-swift-interop.md`, `references/navigation-3.md`, `references/navigation-3-di.md`, `references/koin.md`, `references/gradle-build.md`, and `references/testing.md`.

## Repository and module boundaries

The official KMP structure is the target and the current three client roots already exist:

```text
TillFailure/
├── androidApp/       # Android application Gradle module; depends on shared
├── iosApp/           # Native Xcode app; consumes Shared framework
├── shared/           # KMP library: shared Compose UI + business logic
├── firebase/         # planned TypeScript/functions/rules; not a Gradle module
├── design/           # approved tillfailure.pen; do not alter without approval
├── docs/
├── gradle/libs.versions.toml
├── build.gradle.kts
├── settings.gradle.kts
└── firebase.json     # planned, absent today
```

`androidApp` owns `Application`, `MainActivity`, manifest, application ID/version/signing/build types, Android Firebase configuration/init, lifecycle/OS integration, notification channels/services, and platform dependency wiring. MainActivity never moves to `shared`.

`iosApp` is an Xcode application—not a normal Gradle app module. It owns the Swift `@main` entry, Compose hosting, Apple configuration, Firebase initialization, APNs, lifecycle/permissions/deep-link entry, and binds platform services to the framework.

`shared` uses `com.android.kotlin.multiplatform.library`, publishes a static `Shared` framework, and owns the shared app shell, role navigation, design system, MVI, domain, data contracts/implementations, validation, resources, and sync state. Interfaces are preferred when dependency injection is enough; `expect/actual` remains appropriate for small value factories or platform APIs and its actuals stay in `androidMain`/`iosMain`.

Verified current source-set names are `commonMain`, `commonTest`, `androidMain`, opt-in `androidHostTest`, planned opt-in `androidDeviceTest`, `iosMain`, and `iosTest`. `androidUnitTest`/`androidInstrumentedTest` are obsolete for this Android-KMP plugin configuration.

`firebase` will own TypeScript Cloud Functions, Firestore/Storage rules, indexes, and backend/rule tests. It is not included in Gradle. Root `firebase.json` must map `functions.source` to `firebase/functions`, rules to `firebase/firestore.rules`, indexes to `firebase/firestore.indexes.json`, and storage rules to `firebase/storage.rules`.

## Dependency direction

```text
Presentation ──▶ Domain ◀── Data
       │                     │
       └──── UI/platform effects   Firebase/native adapters
```

- Domain: pure Kotlin models, policies, validators, meaningful orchestration, repository contracts. No Compose, Firebase, Android, Apple, serialization DTO, or clock/global singleton imports.
- Data: DTOs/mappers, repository implementations, listeners, sync metadata, native Firebase adapters. It maps native errors/types into domain failures.
- Presentation: MVI contracts/ViewModels and shared Compose UI. It depends on domain contracts, not Firebase implementations.
- Use cases exist for orchestration/rules such as accepting an invitation, assigning a versioned program, completing a workout, or booking. A trivial `repository.get()` does not earn a use-case class.

Logical packages enforce boundaries initially; Gradle modules are extracted only after build-time, ownership, reuse, or isolation evidence exists.

## Proposed internal `shared` packages

```text
com.delminiusapps.tillfailure
├── app/                         # App root, bootstrap/session/account graph
├── core/
│   ├── designsystem/            # tokens, theme, components, preview fixtures
│   ├── mvi/                     # tiny helpers only; no generic reducer framework
│   ├── navigation/              # typed keys, back stacks, deep-link intents
│   ├── model/ validation/ time/ # cross-feature pure primitives
│   ├── sync/                    # SyncStatus, retry/idempotency primitives
│   ├── diagnostics/             # privacy-safe interfaces
│   └── platform/                # permissions/media/secure-store/lifecycle contracts
├── feature/
│   ├── auth/ onboarding/ home/
│   ├── clients/ exercises/ programs/
│   ├── workout/ progress/ scheduling/
│   └── messaging/ profile/
│       ├── domain/              # model, repository contract, meaningful use cases
│       ├── data/                # DTO, mapper, repository implementation
│       └── presentation/        # contract, ViewModel, Route, Screen, components
└── di/                          # common modules plus platform module composition
```

The same package names may have platform adapter files in `shared/src/androidMain` and wrappers in `shared/src/iosMain`; that does not move app entry responsibilities out of `androidApp`/`iosApp`. With ADR-001’s provisional approach, Apple Firebase implementations themselves live in Swift under `iosApp`, while `iosMain` adapts the injected narrow callback bridge to shared repositories.

Milestone 1 intentionally implements only `app` (temporary typed navigation shell), `di` (process-level Koin bootstrap), and isolated `foundation/{home,details,mvi,scoping,ui}` demonstration packages. It does not pre-create speculative domain/data/platform abstractions or product feature packages.

## MVI contract and UI boundaries

Every non-trivial destination defines immutable `FeatureState`, `sealed interface FeatureEvent`, `sealed interface FeatureEffect`, and `FeatureViewModel`. UI input enters through one `onEvent(event)` and user-facing names (`OnSaveClick`, `OnRetryClick`, `OnExerciseSelected`, `OnWeightChanged`, `OnBackClick`).

The ViewModel owns a private `MutableStateFlow`, exposes `StateFlow`, and delivers single-consumer one-shots via `Channel<FeatureEffect>(Channel.BUFFERED).receiveAsFlow()`. Events make state transitions, start/cancel structured work, or emit semantic effects. There is no global event bus or generic reducer framework in the MVP.

`FeatureRoute` obtains the Koin ViewModel, collects state with multiplatform `collectAsStateWithLifecycle()`, collects effects through one lifecycle-bound collector, and connects navigation, snackbar, permissions, media pickers, haptics, and platform APIs. Milestone 1 integrates this boundary on both targets. `FeatureScreen` is stateless and accepts state plus `onEvent`; previews/tests use fixtures and no Firebase. Leaf composables receive narrow data and named callbacks, own only focus/scroll/animation/expansion state, and never receive ViewModels.

Raw input such as `"72."` is distinct from validated domain weight. Screen state contains no controllers, mutable Compose state, Firebase snapshots, platform objects, or lambdas. Persisted completion, assignment, booking, and send outcomes are durable entities/statuses; navigation/snackbar is only an effect.

## State and lifecycle ownership

- Repository data is the persistent source of truth; each screen ViewModel projects it into UI state.
- Refresh retains prior content and exposes a refresh indicator rather than blanking the screen.
- Derived values are computed from canonical state unless asynchronous/persistent/performance evidence requires storage.
- ViewModels are destination scoped. Shell/session/account state is held above feature destinations; switching account destroys that graph.
- Stable IDs key lists and all retryable writes. Listener jobs are cancelled on ViewModel/account teardown.
- Destroying an account graph isolates in-memory objects only. Persistent Firebase cache, pending writes, app-owned mutation journals/manifests/uploads, and switch-recovery markers follow the canonical protocol in [offline-sync.md](offline-sync.md).

## Platform separation

| Concern | Shared contract/UI | Android app/shared actual | iOS app/shared actual |
|---|---|---|---|
| Firebase | repositories/domain failures | app init; adapters in `androidMain` | Swift SDK implementations/narrow bridge in `iosApp`; callback/coroutine repository wrappers in `iosMain`; mandatory parity spike |
| Notifications | registration/router contracts | FCM service/channels/deep-link Intent | APNs/FCM delegate/URL routing |
| Media | opaque selected-media descriptor | Photo Picker/permissions | PhotosUI/permissions |
| Secure storage | token/key-value interface; do not duplicate Firebase credentials | Keystore-backed implementation where needed | Keychain-backed implementation where needed |
| Lifecycle | shared Lifecycle/ViewModel | Activity/Application hooks | UIKit/SwiftUI hooks |
| Deep links | normalized `ExternalRouteIntent` | intent-filter parser | universal/custom URL parser |

The iOS bridge uses concrete DTOs, completion callbacks and explicit cancellation handles. `iosApp` constructs and injects the bridge after Firebase initialization; `iosMain` maps async delivery, cancellation, and stable error categories into shared contracts. Listener ownership is account-scoped and protected by UID/account-epoch fencing so late Swift callbacks cannot update a disposed or replacement account.

## Canonical persistence policies

- [offline-sync.md](offline-sync.md) owns offline eligibility, downloaded-workout proof, the proposed mutation journal, process recovery, and safe sign-out/account switching.
- [firestore-schema.md](firestore-schema.md) owns shared server business records and the internal-versus-trainer-visible invitation model.
- [firestore-security.md](firestore-security.md) owns server authorization. Local offline eligibility never changes Rules/Functions decisions.

## Design system and accessibility

Map Pencil semantic variables to one `TillFailureTheme`: dark surfaces, electric-lime accent, semantic status colors, Manrope display, Inter body, type roles, spacing/radius, motion, and component state tokens. Reuse canonical component IDs listed in [design-inventory.md](design-inventory.md); feature screens do not hardcode repeated values.

The Pencil audit specifies at least 44×44 targets, while the required Compose accessibility guidance requires 48×48 dp. Preserve visual geometry but give custom controls an effective 48×48 hit/semantic target with `minimumInteractiveComponentSize()`; approving a visible token change remains an open design decision. Provide localized descriptions, headings/roles/state descriptions, logical semantic grouping, text/icon labels in addition to color, WCAG AA contrast validation, 200% text reflow/scroll, visible focus, keyboard support, reduced motion, and TalkBack/VoiceOver tests.

## Discovery contradictions and disposition

- The repository already contained sample `androidApp`, `iosApp`, and `shared` scaffolding despite README text saying scaffolding was not finalized. README now reflects the verified foundation.
- Firebase configuration plist/JSON files are tracked, but there are no Firebase SDK dependencies, initialization, rules, functions, or deployed-resource evidence; their presence is not an implemented integration.
- The README named obsolete `androidUnitTest`; the plugin and current source use `androidHostTest`.
- The sample wizard UI and greeting/platform code were removed. `App.kt` now hosts only the isolated temporary MVI foundation flow.
- At initial discovery the README contained duplicated KMP wizard boilerplate; the planning pass removed that duplicate and preserved the TillFailure content.
- No `AGENTS.md`, `docs/`, `firebase/`, root `firebase.json`, or Android `Application` class existed at discovery time. Planning docs and the minimal Koin-owning Android `Application` now exist; Firebase paths remain deliberately absent.

Milestone 1 foundation changes were explicitly authorized. This document does not authorize Milestone 2, Firebase, or product implementation.

## Primary sources

- [Recommended KMP project structure](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html)
- [Android-KMP library plugin and source sets](https://developer.android.com/kotlin/multiplatform/plugin)
- [Compose platform entry points](https://kotlinlang.org/docs/multiplatform/compose-platform-specifics.html)
