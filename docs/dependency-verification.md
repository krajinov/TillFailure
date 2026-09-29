# Dependency and toolchain verification

Status date: **2026-09-20**
Milestone 3 Firebase dependencies are integrated and freshly compiled/run against local emulators on Android and iOS. Production Firebase remains unconfigured.

## Status legend

- **Build-verified (fresh)**: exact version is configured and resolved, and the listed repository build/test commands succeeded on the status date.
- **Build-verified (reported)**: historical result without durable evidence from the current implementation pass.
- **Source-verified**: exact release/coordinates/target support were checked in primary project/vendor sources; integration still needs a repository build spike.
- **Unresolved**: evidence or a compatible exact version is not sufficient; do not add until resolved.

Selecting the newest artifact independently is explicitly rejected. The proposed baseline freezes the already compatible AGP/Kotlin/Compose cluster and adds candidates only through milestone-1 compile/test gates.

## Existing mutually compatible baseline

| Component | Exact version/configuration | Targets/source set | Status and API suitability | Source |
|---|---|---|---|---|
| JDK | Oracle JDK `21.0.8+12` launcher; Gradle daemon selects compatible Zulu 21 | Gradle host | **Build-verified (fresh)** with Android and Kotlin/Native tasks | [AGP 9.0 compatibility](https://developer.android.com/build/releases/past-releases/agp-9-0-0-release-notes) |
| Gradle wrapper | `9.1.0` | Build | **Build-verified (fresh)**; required/default pairing for AGP 9.0 | [AGP 9.0 compatibility](https://developer.android.com/build/releases/past-releases/agp-9-0-0-release-notes) |
| Android Gradle Plugin | `9.0.1` | `androidApp`; shared Android target | **Build-verified (fresh)** for host tests/assembly with compile/target SDK 36 and min 29; debug app cold-launched on API 34 | [AGP 9.0.1 release](https://developer.android.com/build/releases/past-releases/agp-9-0-0-release-notes) |
| Kotlin/KGP | `2.4.10` | All KMP source sets | **Build-verified (fresh)** | [Kotlin releases](https://kotlinlang.org/docs/releases.html) |
| Android-KMP library plugin | `com.android.kotlin.multiplatform.library` via AGP `9.0.1` | `shared/androidMain`, `androidHostTest`, `androidDeviceTest` | **Build-verified (fresh)**; host and API 34 device-test suites executed | [Android-KMP plugin](https://developer.android.com/kotlin/multiplatform/plugin) |
| Compose Multiplatform plugin/runtime | `1.11.1` | `commonMain`, Android, iOS | **Build-verified (fresh)** for metadata, Android app, Kotlin/Native tests, Xcode host build, and both launches | [Compose releases](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.11.1) |
| Material 3 | `org.jetbrains.compose.material3:material3:1.11.0-alpha07` | `commonMain` | **Build-verified (fresh)**; prerelease exposure remains frozen rather than broadened | [Compose-to-Jetpack mapping](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-and-jetpack-compose.html) |
| Lifecycle Compose | `lifecycle-viewmodel-compose`, `lifecycle-runtime-compose`, `lifecycle-viewmodel-navigation3` at `2.11.0-beta01` | `commonMain` | **Build-verified (fresh)** on Android/iOS; prerelease exposure remains and must be upgraded only as a coordinated toolchain change | [JetBrains Lifecycle](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-lifecycle.html) |
| AndroidX Activity Compose | `androidx.activity:activity-compose:1.13.0` | `androidApp` | **Build-verified (fresh)** for assembly and emulator launch | [AndroidX Activity release notes](https://developer.android.com/jetpack/androidx/releases/activity) |
| Xcode | `26.2` (`17C52`) | iOS build host | **Build-verified (fresh)** for scheme `iosApp` and iPhone 16e/iOS 26.2 simulator launch. Warnings: ICU object min iOS Simulator 18.5 versus app target 18.2, and Shared framework bundle ID inference fallback. | Local `xcodebuild -version`; [Kotlin/Apple requirements](https://kotlinlang.org/docs/multiplatform-compatibility-guide.html) |

Fresh commands successful on branch `foundation/milestone-1`:

```text
./gradlew :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest :androidApp:assembleDebug --console=plain
./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:iosSimulatorArm64Test
```

The aggregate `:shared:compileKotlinMetadata` task was also observed as `SKIPPED`; `:shared:compileCommonMainKotlinMetadata` is therefore the actual metadata compilation command recorded above. The full Xcode host build separately compiled Swift and linked/launched the application; Kotlin/Native compilation alone is not treated as host proof.

## Milestone 1 shared dependencies and deferred candidates

| Coordinates | Exact version | Source set/targets | Status, API availability, and compatibility notes | Source |
|---|---:|---|---|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | `1.10.2` | `commonMain`; Android/iOS | **Build-verified (fresh)**; Flow, StateFlow, buffered Channel and structured concurrency power the MVI shell. | [Coroutines 1.10.2](https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.10.2) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | `1.10.2` | `commonTest` | **Build-verified (fresh)** on Android host and iOS simulator. | [Coroutines test API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/) |
| `io.insert-koin:koin-core` | `4.2.2` | `commonMain`; Android/iOS | **Build-verified (fresh)**; explicit modules, no annotation/compiler plugin. | [Koin 4.2.2 release](https://github.com/InsertKoinIO/koin/releases/tag/4.2.2) |
| `io.insert-koin:koin-compose` | `4.2.2` | `commonMain`; Android/iOS | **Build-verified (fresh)** Compose integration. | [Koin Compose Multiplatform](https://insert-koin.io/docs/reference/koin-compose/compose/) |
| `io.insert-koin:koin-compose-viewmodel` | `4.2.2` | `commonMain`; Android/iOS | **Build-verified (fresh)**; `koinViewModel()` is used in decorated Navigation 3 entries. | [Koin Compose Multiplatform](https://insert-koin.io/docs/reference/koin-compose/compose/) |
| `org.jetbrains.androidx.navigation3:navigation3-ui` | `1.1.1` | `commonMain`; Android/iOS | **Build-verified (fresh)** with typed keys, Android navigation/back/scoping, and iOS host compile/launch; interactive iOS scoping subsequently passed by user manual verification. | [Compose Navigation 3](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-navigation-3.html) |
| `org.jetbrains.kotlin:kotlin-serialization` Gradle plugin | `2.4.10` | Build plugin | **Build-verified (fresh)** and matched to Kotlin; used for serializable navigation keys. | [Kotlin serialization setup](https://kotlinlang.org/docs/serialization.html) |
| `org.jetbrains.kotlinx:kotlinx-serialization-core` | `1.9.0` | `commonMain`; Android/iOS | **Build-verified (fresh)**; supports Navigation 3 key/restoration serialization. | [Serialization 1.9.0](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.9.0) |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | `1.9.0` | `commonMain`; Android/iOS | **Build-verified (fresh)**; encodes the versioned Milestone 3 persistence envelopes. | [Serialization 1.9.0](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.9.0) |
| `org.jetbrains.kotlinx:kotlinx-datetime` | `0.8.0` | future `commonMain`; Android/iOS | **Source-verified, not added**; no Milestone 1 clock/date requirement. Store IANA zone IDs and authoritative instants when scheduling enters scope; integration/DST tests remain required. | [DateTime 0.8.0](https://github.com/Kotlin/kotlinx-datetime/releases/tag/v0.8.0) |
| `app.cash.turbine:turbine` | `1.2.1` | `commonTest`; Android/iOS tests | **Build-verified (fresh)** for MVI effect tests on Android host and iOS simulator. | [Turbine 1.2.1](https://github.com/cashapp/turbine/releases/tag/1.2.1) |
| `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose` | `2.11.0-beta01` | `commonMain`; Android/iOS | **Build-verified (fresh)**; already cataloged/configured before Milestone 1 and now exercised by route collection. | [Compose lifecycle API](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-lifecycle.html) |
| `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3` | `2.11.0-beta01` | `commonMain`; Android/iOS | **Build-verified (fresh)**; supplies the destination ViewModel store decorator. | [Compose Navigation 3](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-navigation-3.html) |

Optional `io.insert-koin:koin-compose-navigation3:4.2.2` was **not added**: the plain Navigation 3 decorators plus `koinViewModel()` satisfy the foundation scope requirement. Reconsider only if a later API requirement demonstrates a gap.

Fresh Gradle resolution selected the requested direct versions. Notable transitive alignment is `androidx.compose.runtime:runtime:1.11.2` beneath JetBrains Compose `1.11.1`, Navigation runtime `1.1.1`, SavedState `1.4.0`, coroutines `1.10.2`, lifecycle `2.11.0-beta01`, and serialization core `1.9.0`. The alpha Material 3 and beta lifecycle versions are existing deliberate exposure and remain frozen for this milestone.

## Selected official Firebase client SDKs

| Platform | Coordinates/package | Exact version | Support/source-set placement | Status and integration gate | Source |
|---|---|---:|---|---|---|
| Android alignment | `com.google.firebase:firebase-bom` | `34.19.0` | `shared/androidMain` adapter dependencies | **Build/runtime-verified** with AGP 9.0.1/Kotlin 2.4.10 and API 34 emulator | [Firebase Android BoM release notes](https://firebase.google.com/support/release-notes/android) |
| Android products | `firebase-auth`, `firebase-firestore`, `firebase-storage` | Managed by BoM `34.19.0` | `shared/androidMain` | **Build/runtime-verified** adapter surface; Storage SDK is linked but native upload recovery was not evaluated. No Messaging/Crashlytics/Analytics added. | [Firebase Android setup](https://firebase.google.com/docs/android/setup) |
| Android config plugin | `com.google.gms.google-services` | `4.5.0` | `androidApp` only | **Source-verified**; absent today | [Google services plugin](https://developers.google.com/android/guides/google-services-plugin) |
| Android Crashlytics plugin | `com.google.firebase.crashlytics` | `3.0.8` | `androidApp` only | **Source-verified**; absent today | [Crashlytics Android setup](https://firebase.google.com/docs/crashlytics/get-started?platform=android) |
| Apple package | `https://github.com/firebase/firebase-ios-sdk` products `FirebaseAuth`, `FirebaseFirestore` | `12.19.2` | Swift Package Manager in `iosApp`; iOS 15+ | **Build/runtime-verified** through the signed Swift bridge harness. Storage/Messaging/Crashlytics/Analytics were not added. | [Firebase Apple setup](https://firebase.google.com/docs/ios/setup), [Apple SDK releases](https://github.com/firebase/firebase-ios-sdk/releases/tag/12.19.2) |

The Android BoM does not provide one visible per-library version in the Gradle catalog. Apple’s Swift SDK is not a `commonMain`/`iosMain` dependency. The verified Swift callback bridge is injected by the iOS app and adapted in `iosMain`; it keeps Apple Firebase types out of common code.

## Firebase backend toolchain

| Package/tool | Exact version | Runtime/placement | Status | Source |
|---|---:|---|---|---|
| Node.js | `22.23.2` | isolated local runtime for `firebase/functions`; Node 22 line required in CI | **Build/emulator-verified**; `npm ci`, TypeScript and all 17 emulator tests passed, and Functions reported `Using node@22 from host` | [Cloud Functions runtime support](https://firebase.google.com/docs/functions/manage-functions#set_nodejs_version) |
| `firebase-functions` | `7.4.0` | Functions production dependency | **Build/emulator-verified** | [npm package](https://www.npmjs.com/package/firebase-functions/v/7.4.0) |
| `firebase-admin` | `14.4.0` | Functions production dependency | **Build/emulator-verified** | [npm package](https://www.npmjs.com/package/firebase-admin/v/14.4.0) |
| `@firebase/rules-unit-testing` | `5.0.2` | Rules dev dependency | **Emulator-verified** Firestore and Storage Rules API | [npm package](https://www.npmjs.com/package/@firebase/rules-unit-testing/v/5.0.2) |
| `firebase-functions-test` | `3.5.0` | Functions dev dependency if needed | **Source-verified**; prefer emulator integration for auth/security behavior | [npm package](https://www.npmjs.com/package/firebase-functions-test/v/3.5.0) |
| Firebase CLI | `15.30.2` | Locked local developer/CI tool | **Emulator-verified** clean start/stop | [Firebase CLI releases](https://github.com/firebase/firebase-tools/releases/tag/v15.30.2) |
| TypeScript | `5.9.3` | Functions dev dependency | **Build-verified** exact patch | [Firebase Functions TypeScript guide](https://firebase.google.com/docs/functions/typescript) |

Milestone 3 added local packages, Rules, emulator configuration, and native SDKs. The acceptance run used npm `11.12.1`; eight moderate audit findings and transitive deprecation warnings were retained without `npm audit fix`. It did not create or modify a Firebase cloud project or change the lockfile in the correction pass.

## Explicitly not selected

- GitLive `dev.gitlive:firebase-*:2.6.0` was source-compared and is not selected or added. Its common API is credible, but its exact stable build aligns Kotlin 2.2.21, Android BoM 34.17.0, and CocoaPods Apple SDK 11.8.0; ADR-001 accepts the directly verified official SDK path.
- Ktor, Room, SQLDelight, a custom server, and a second database have no demonstrated MVP requirement.
- A DI compiler plugin, generic MVI framework, and dozens of Gradle feature modules add risk without evidence.

## Required dependency gates

1. **Completed for Milestone 1:** add only required candidates through the version catalog, without an unreviewed latest sweep.
2. **Completed for Milestone 1:** compile common metadata, Android host tests/app, and iOS simulator tests.
3. **Completed:** capture locked npm and SwiftPM graphs. The Xcode workspace contains `Package.resolved` for Firebase Apple `12.19.2`.
4. **Completed:** verify typed Navigation 3, lifecycle collection, Koin ViewModel scoping, and Android/Xcode host build/launch without Firebase. Interactive iOS navigation/scoping passed by user manual verification; process-death restoration remains unverified and is not claimed.
5. **Completed for Auth/Firestore:** Milestone 3 proved emulator connection, actual Swift bridge behavior, and Android/Apple parity; GitLive was source-compared. Native Storage recovery remains deferred because it was not evaluated.
6. Record API deviations and freeze exact lockfiles/tool versions. Any failure remains unresolved rather than prompting unrelated upgrades.
