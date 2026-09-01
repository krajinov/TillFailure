# Milestone 1 report: verified project foundation

Date: **2026-09-01**

Branch: **`foundation/milestone-1`** (local only)

Baseline commit: **`fdf897a49fbbacc37e6e2aa8e4e26ca1658db57d`**

Result: **implementation complete; interactive iOS navigation/scoping manually verified by the user**

## Scope and safeguards

Milestone 1 replaces the generated demo with a small technical `Foundation Home -> Foundation Details -> Back` flow. It proves the client foundation without introducing a product role selector, authentication bypass, Firebase, persistence, backend code, production features, or deployments.

The initial worktree already contained a modified README and untracked planning documents. They were preserved and updated in place. No `AGENTS.md` or additional repository rules were present. A local `foundation/milestone-1` branch was created because that name did not already exist; nothing was committed, pushed, deployed, or submitted as a pull request.

The installed `compose-skill` was used. The reviewed revision was `982c240e47718b3b0525c5bbe85bf19ff0bb7bec` (2026-04-06). The complete `SKILL.md`, mandatory `references/mvi.md`, and routed navigation, Navigation 3 DI, Koin, Gradle/build, testing, architecture, accessibility, and iOS interop references were read. The resulting code follows its Route/Screen, immutable MVI, lifecycle collection, destination ViewModel scope, DI bootstrap, previewability, and testing boundaries.

## Implemented foundation

- `androidApp` remains the Android application. `TillFailureApplication` initializes Koin once at process startup; `MainActivity` remains the Compose entry point and depends on `shared`.
- `iosApp` remains a native Xcode/SwiftUI application. Its Swift `App` initializes Koin before hosting `MainViewController` from the shared framework.
- `shared` remains one KMP library using `com.android.kotlin.multiplatform.library`; no `composeApp`, `sharedLogic`, `sharedUI`, or feature Gradle modules were introduced.
- `FoundationHome` and `FoundationDetails` are typed serializable Navigation 3 keys. `rememberNavBackStack`, an explicit iOS-compatible `SavedStateConfiguration`, saveable-state decoration, and ViewModel-store decoration own the technical back stack.
- Koin provides destination-scoped `FoundationHomeViewModel` and `FoundationDetailsViewModel`. Initialization occurs in native process entry points, never inside recomposition.
- Both destinations use immutable state, sealed event/effect contracts, one `onEvent`, private `MutableStateFlow`, exposed `StateFlow`, and `Channel.BUFFERED` plus `receiveAsFlow()` effects.
- Routes obtain ViewModels, collect state with `collectAsStateWithLifecycle`, collect effects once under `repeatOnLifecycle(STARTED)`, and perform navigation/snackbar work. Screens are stateless and previewable without DI/navigation/platform services.
- Effect sending first uses `trySend`; a full buffer falls back to a cancellable ViewModel-scope send. Disposal/closed-channel races deliberately drop the transient effect. Effects are documented as non-durable and not exactly-once across process death.
- A milestone-only `FoundationScopeProbe` makes Details instance creation/release observable. It is isolated for removal with the temporary shell and is not a production analytics or global event facility.
- The generated greeting/platform sample and arithmetic placeholder tests were removed.

The implementation intentionally does not create clocks, ID generators, account scopes, repository interfaces, persistence abstractions, or product packages that this technical flow does not need.

## Files and responsibilities changed

| Area | Files/responsibility |
|---|---|
| Build/catalog | Root `build.gradle.kts`, `gradle/libs.versions.toml`, and `shared/build.gradle.kts`: serialization plugin, exact foundation dependencies, and common tests. |
| Android entry | `androidApp/src/main/AndroidManifest.xml`, `MainActivity.kt`, and new `TillFailureApplication.kt`: retain the Android app boundary and bootstrap Koin once. |
| iOS entry | `iosApp/iosApp/iOSApp.swift`: bootstrap shared Koin before the native host renders shared Compose. |
| Shared shell | `shared/.../App.kt` and new `app/FoundationNavigation.kt`: temporary theme and typed two-destination shell. |
| DI | New `shared/.../di/DependencyInjection.kt`: explicit common Koin module and entry-point initializer. |
| MVI/UI | New `shared/.../foundation/home`, `details`, `mvi`, `scoping`, and `ui` files: contracts, ViewModels, Routes, stateless Screens, transient effect helper, diagnostic scope probe, and minimal foundation theme. |
| Cleanup | Removed generated greeting/platform source and empty arithmetic tests. Shared platform source sets remain available for future genuine platform implementations. |
| Tests | New common Home/Details MVI tests and Android-host ViewModelStore disposal/recreation test. |
| Documentation/evidence | Updated README, architecture, dependency, navigation, testing, implementation-plan, and open-question status; added this report and sanitized simulator screenshots under `docs/milestones/evidence`. |

No file under a Firebase backend directory was created or modified. Existing platform Firebase configuration files, if present in the scaffold, were not wired to an SDK.

## Dependency verification

Verification date: **2026-09-01**. Exact direct versions are cataloged; both `commonMainResolvableDependenciesMetadata` and `commonTestResolvableDependenciesMetadata` resolved successfully. Android metadata/host/app and iOS simulator builds exercised the selected API shapes.

| Dependency | Exact version | Placement and reason | Target/build result |
|---|---:|---|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | `1.10.2` | `commonMain`; StateFlow, Channel, structured effect work | Android/iOS passed |
| `io.insert-koin:koin-core` | `4.2.2` | `commonMain`; explicit DI container/modules | Android/iOS passed |
| `io.insert-koin:koin-compose` | `4.2.2` | `commonMain`; Compose integration | Android/iOS passed |
| `io.insert-koin:koin-compose-viewmodel` | `4.2.2` | `commonMain`; destination ViewModel injection | Android/iOS passed |
| `org.jetbrains.androidx.navigation3:navigation3-ui` | `1.1.1` | `commonMain`; typed Android/iOS navigation | Android flow passed; iOS build/launch passed; interactive iOS flow manually passed per user verification |
| `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3` | `2.11.0-beta01` | `commonMain`; destination ViewModel store decorator | Android/iOS passed; beta exposure recorded |
| `org.jetbrains.kotlinx:kotlinx-serialization-core` | `1.9.0` | `commonMain`; serializable Navigation 3 keys/restoration config | Android/iOS passed |
| `org.jetbrains.kotlin.plugin.serialization` | `2.4.10` | build plugin matching Kotlin exactly | Android/iOS passed |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | `1.10.2` | `commonTest`; deterministic coroutine tests | Android/iOS tests passed |
| `app.cash.turbine:turbine` | `1.2.1` | `commonTest`; effect Flow assertions | Android/iOS tests passed |

`lifecycle-runtime-compose:2.11.0-beta01` and `lifecycle-viewmodel-compose:2.11.0-beta01` already existed in the catalog/configuration and are now exercised by the foundation. `koin-compose-navigation3`, JSON serialization, Firebase, databases, media, analytics, backend packages, and unrelated upgrades were not added.

Notable resolved transitive versions include Navigation runtime `1.1.1`, SavedState `1.4.0`, AndroidX Compose runtime `1.11.2` under JetBrains Compose `1.11.1`, lifecycle `2.11.0-beta01`, coroutines `1.10.2`, and serialization core `1.9.0`. Existing Material 3 `1.11.0-alpha07` and lifecycle beta exposure remain frozen and require a coordinated future upgrade decision.

Primary version/API evidence is recorded in [`../dependency-verification.md`](../dependency-verification.md), including links to the Kotlin, JetBrains Compose/Navigation, Koin, coroutines, serialization, Turbine, Android, and Apple sources consulted on the verification date.

## Environment

| Tool | Observed version |
|---|---|
| macOS | `26.6.2` (`25G83`), arm64 |
| Java launcher | Oracle JDK `21.0.8+12-LTS-250` |
| Gradle daemon | compatible Zulu Java 21 from `gradle/gradle-daemon-jvm.properties` |
| Gradle wrapper | `9.1.0` |
| Kotlin project plugin | `2.4.10` (Gradle distribution itself reports embedded Kotlin `2.2.0`) |
| Android SDK | compile/target `36`, min `29` |
| adb | `37.0.1-15733141` |
| Android Emulator | `37.1.11.0`; `Pixel_4_API_34`, API 34 used |
| Xcode | `26.2` (`17C52`) |
| iOS simulator | iPhone 17 Pro, iOS 26.2; simulator identifier omitted from committed evidence |

## Fresh command evidence

| Command/check | Exit/result |
|---|---|
| `git status --short --branch` before edits | `0`; existing README/planning changes identified and preserved |
| branch lookup then `git switch -c foundation/milestone-1` | `0`; local branch created, no overwrite/push |
| `./gradlew :shared:tasks --all --console=plain` | `0`; real Android-KMP/iOS task names inspected |
| `./gradlew :shared:dependencies --configuration commonMainResolvableDependenciesMetadata --console=plain` | `0`; requested runtime dependencies resolved |
| `./gradlew :shared:dependencies --configuration commonTestResolvableDependenciesMetadata --console=plain` | `0`; coroutine-test `1.10.2` and Turbine `1.2.1` resolved |
| `./gradlew :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest :androidApp:assembleDebug --console=plain` | `0`; **BUILD SUCCESSFUL in 19s**, 72 actionable: 14 executed, 58 up-to-date |
| `./gradlew :shared:testAndroidHostTest --console=plain` after the final disposal assertion | `0`; **BUILD SUCCESSFUL in 3s**, 32 actionable: 3 executed, 29 up-to-date |
| `./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:iosSimulatorArm64Test --console=plain` | `0`; **BUILD SUCCESSFUL in 14s**, 24 actionable: 7 executed, 17 up-to-date |
| `xcodebuild -list -json -project iosApp/iosApp.xcodeproj` | `0`; project/target/scheme `iosApp`; no Xcode test target |
| `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'platform=iOS Simulator,id=<SIMULATOR_UDID>' -derivedDataPath /private/tmp/tillfailure-m1-derived CODE_SIGNING_ALLOWED=NO build` | `0`; **BUILD SUCCEEDED**; Swift compiled, shared framework linked; simulator identifier sanitized in committed evidence |
| `adb install -r .../androidApp-debug.apk` and `am start -W .../.MainActivity` | `0`; install successful, cold launch `Status: ok`, `TotalTime: 1305 ms` on final run |
| `xcrun simctl install ...TillFailure.app` and `simctl launch --terminate-running-process ...` | `0`; final cold launch PID `78399` |
| final iOS Settings/background then app foreground | `0`; resumed the same TillFailure PID `78399` |

The first aggregate metadata attempt used `:shared:compileKotlinMetadata`; Gradle marked that aggregate task `SKIPPED`, although downstream Android compilation passed. The final evidence therefore calls and executes `:shared:compileCommonMainKotlinMetadata` directly.

Representative generated/resource tasks such as value-resource conversion and Java source processing reported `NO-SOURCE`; they are not counted as tests. `checkKotlinGradlePluginConfigurationErrors` and some SwiftPM/resource coordination tasks reported `SKIPPED`; they are not claimed as executed checks.

Superseded failures were retained in the audit rather than presented as passes:

- The first iOS compilation exposed a JVM-only Koin `GlobalContext` reference in common code. The initializer was corrected to use portable `startKoin`; the final Android, iOS, and Xcode checks above then passed.
- `:shared:resolvableConfigurations` failed with a Gradle `ConcurrentModificationException`. The supported `dependencies --configuration ...` reports succeeded and were used instead; this is a reporting-task limitation, not a compile/test failure.
- One sandboxed Gradle invocation could not open the user's Gradle-cache lock. The same repository command was rerun with approved cache access and passed.

## Test results

- Android host: **8 tests, 0 failures, 0 errors, 0 skipped**. This is seven common MVI tests plus one Android-host ViewModelStore integration test.
- iOS simulator: **7 tests, 0 failures, 0 errors, 0 skipped**. These are the common Home/Details MVI tests compiled and executed as Kotlin/Native tests.
- Covered behavior: initial states, event-driven rep updates, single navigation effect, transient message effect, deterministic `runTest`/Turbine behavior, initial Details scope registration, back effect, same-store retention, store-clear disposal, and fresh-store recreation.
- No Firebase is used by tests. No empty/framework-construction-only test remains.
- No Xcode application test target exists, so `xcodebuild test` was correctly reported **not available**, not passed.

## Runtime and lifecycle evidence

### Android

The final APK installed and cold-launched on `Pixel_4_API_34`. The flow was exercised through UI input and UI hierarchy inspection:

1. Home started at rep `0`; `OnAddRepClick` changed it to `1`.
2. Details opened as ViewModel `#1`, previously released `0`.
3. Android system back popped Details and returned to Home with rep `1`, demonstrating retained Home destination state.
4. Reopening Details showed ViewModel `#2`, previously released `1`, and detail reps reset to `0`, demonstrating popped-store disposal and fresh destination scope.
5. The Details button emitted the MVI back effect and returned Home.
6. The one-shot message produced one snackbar. Waiting/back/relaunch did not replay it.
7. Back from root returned to the launcher. A subsequent forced process restart returned to Home rep `0`, intentionally distinguishing retained back-stack ViewModel state from process-death/durable restoration.

Koin initialization is in `Application.onCreate`, outside composition, so recomposition does not recreate the container.

### iOS

The actual Xcode project and scheme built, installed, and launched on the iPhone 17 Pro simulator. The shared Foundation Home rendered. Launching Settings moved the app to the background; relaunching returned the same app process and rendered the shell without a crash or an additional visible collector effect.

No compatible iOS input driver (`idb`, Maestro, AppleSimUtils, or equivalent) was installed, so Codex did not independently perform or automate the interactive Home/Details flow.

#### User-verified manual iOS test — passed

Date: **2026-09-01**

Device: **iPhone 16e simulator**

The following transition results are attributed to the user:

- Home counter remains unchanged after navigating to Details and returning.
- After leaving Details and reopening it, the Details counter resets and a new ViewModel instance is created.
- The visible diagnostics identify the previously released Details ViewModel.
- Background -> foreground preserves the current state.

The supplied screenshots show `Home reps: 4` and `Details ViewModel #9`, `Previously released: 8`, `Detail reps: 0`. The screenshots support those visible values; the user's report confirms the navigation, disposal/recreation, and lifecycle transitions. The screenshots were not accessible as filesystem attachments during finalization, so no repository image or broken link was fabricated.

### Saved state limits

Navigation keys/back-stack serialization and saveable-state/ViewModel-store decorators are configured. Full Android process-death restoration, iOS state restoration, and durable business-state restoration were not implemented or claimed. Durable workout recovery belongs to Milestone 8.

## Warnings and remaining limitations

- Xcode links successfully but warns that the Shared framework's ICU object was built for iOS Simulator 18.5 while the app deployment target is 18.2. No deployment target was silently changed; resolve this in a coordinated toolchain/deployment-target review.
- Kotlin/Native also warns that it cannot infer the Shared framework bundle ID and falls back to the framework name. Set an explicit framework bundle ID in a reviewed build-configuration change rather than guessing one during this milestone.
- The Android command-line tools emitted an SDK XML version 4 versus supported version 3 warning. Build/launch passed; align Android Studio/command-line tools separately without coupling it to this milestone.
- Interactive iOS navigation/scoping is manually user-verified but was not independently automated by Codex.
- There is no Xcode test target, Android device-test execution, CI run, physical-device check, signing change, or production device check.
- Foundation counters and navigation state are intentionally memory/back-stack scoped, not durable.
- Existing unresolved product/security policies—offline eligibility, revoked-data handling, shared-device persistence, invitation binding, trainer provisioning, and Apple Firebase bridging—remain open and were not finalized.

## Sanitized evidence

The evidence directory contains simulator-only screenshots with no account, Firebase, credential, message, health, or user data. Its inventory is in [`evidence/README.md`](evidence/README.md).

## Acceptance assessment

| Milestone 1 criterion | Result |
|---|---|
| Dedicated `androidApp`, native `iosApp`, Android-KMP `shared`; thin entry points | Satisfied |
| Shared minimal shell launches from Android and Xcode hosts | Satisfied |
| Typed navigation, Koin, destination ViewModels, MVI Route/Screen boundary | Satisfied |
| Home retention, pop disposal, fresh Details scope, Android back, non-replayed effects | Satisfied on Android and host-test layers |
| Metadata, Android host tests/app assembly, iOS compile/tests, native Xcode build | Satisfied with warnings recorded |
| iOS host cold launch and foreground/background smoke | Satisfied |
| Interactive Home -> Details -> Back, state retention, disposal/recreation, and foregrounding on iOS | Satisfied by user-verified manual test on iPhone 16e simulator; not independently automated by Codex |
| Process-death/durable state restoration | Out of scope and not claimed |
| No Firebase/product/deployment/extra modules | Satisfied |

Milestone 1 acceptance criteria are satisfied using the fresh automated/build evidence and the explicitly attributed user manual iOS verification. Process-death/durable restoration remains out of scope and unverified, the Xcode project still has no test target, and the recorded Xcode warnings remain. This report does not authorize or begin Milestone 2.
