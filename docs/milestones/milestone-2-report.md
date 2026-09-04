# Milestone 2 report: shared design system and visual fixtures

Date: **2026-09-02**

Verification update: **2026-09-04**

Stacked-branch update: **2026-09-08**

PR #2 P2 verification update: **2026-09-11**

PR #2 follow-up P2 verification update: **2026-09-12**

PR #2 typed-model P2 verification update: **2026-09-12**

PR #2 responsive tabs, forward callbacks, password toggle P2 verification update: **2026-09-12**

PR #2 snackbar and disabled-chip P2 verification update: **2026-09-13**

PR #2 measured-width bottom-navigation P2 verification update: **2026-09-13**

Branch: **`feature/milestone-2-design-system`** (PR #2)

Current baseline commit: **`058afe9458331dc634e0d4cf459c4f2195f21bf3`**

Result: **implementation and cross-platform catalog review complete; acceptance pending screen-reader checks**

## Scope and safeguards

Milestone 2 adds a shared dark/lime design system, reusable UI components, deterministic visual fixtures, and a development-only catalog. It does not add authentication, Firebase, networking, persistence, repositories, use cases, product ViewModels, backend resources, deployments, or Milestone 3 work. No dependency or version changed.

The work originally started from the expected clean Milestone 1 commit `3512033991bcf1b6fc7d3dff6645dba8a16ad695`. The local branch `feature/milestone-2-design-system` did not exist and was created without resetting, stashing, overwriting, committing, pushing, or opening a pull request. It was subsequently committed and opened as PR #2. On 2026-09-08, after verifying the unchanged remote Milestone 2 head, it was rebased onto the updated PR #1 head `058afe9458331dc634e0d4cf459c4f2195f21bf3`. No `AGENTS.md` was present.

The installed `compose-skill` was used at revision `982c240e47718b3b0525c5bbe85bf19ff0bb7bec`. Its complete `SKILL.md` and the routed `references/accessibility.md` were read for this component/accessibility milestone. The existing MVI Route/Screen conventions remain intact; the fixtures are intentionally stateless and do not introduce ViewModels merely to provide sample data.

## Approved design evidence

The approved `design/tillfailure.pen` was inspected read-only through Pencil tooling. Its encrypted contents were not read with filesystem tools and the design was not modified. Foundations `V95tp`, canonical components `VJuJK`, reusable masters, resolved token variables, and rendered 393 × 852 reference frames were inspected.

| Composition | Pencil frame | Role | Fixture continuity |
|---|---|---|---|
| Authentication / Sign In | `QVhze` | Both | Alex Morgan and Strength Foundation |
| Client Home | `OmHDs` | Client | Alex, Maya, Upper Body Strength, Tuesday appointment |
| Active Workout | `v66zG` | Client | Upper Body Strength, Barbell Bench Press, 72.5 kg set data |
| Trainer Dashboard | `eq0D5` | Trainer | Maya, Alex/Priya/Jon appointments and review queue |
| Trainer Client Details | `bLqqR` | Trainer | Alex, Strength Foundation week 4, adherence and trainer note |

## Implemented foundations and components

- Semantic colors reproduce approved background, two surface levels, borders, primary/secondary/disabled text, electric lime, focus, success, warning, error, info, and overlay roles.
- Central spacing (4/8/12/16/20/24/32 dp), radii (6/10/16/24 dp), border widths, icon sizes, field size, and 48 dp minimum interaction target prevent repeated feature literals.
- A single dark `TillFailureTheme` maps semantic roles into Material 3 colors, typography, and shapes. No speculative light mode or generic theme framework was added.
- Shared components cover primary/inverted/loading/disabled buttons, secondary/text/icon actions, labeled fields with support/error/disabled APIs, filter chips, non-color-only status badges, cards, top/bottom navigation, workout/exercise/logged-set rows, banners/snackbar, loading/empty/error states, confirmation dialog, and action sheet.
- `TillFailureScreenPreviews` provides small phone (320 × 700), approved viewport (393 × 852), and 200% font-scale previews for every fixture screen.
- Android edge-to-edge uses dark transparent system bars. iOS declares dark appearance; shared screens handle status/navigation safe areas, IME padding where relevant, scrolling content, and a safe-area workout action bar.

## Missing assets and documented deviations

- The repository contains no approved Manrope/Inter font binaries or license notices. The theme centralizes display/body roles but uses the platform sans-serif fallback. No font was downloaded from an unverified source. Supplying licensed Manrope and Inter assets remains a design-asset gate.
- No approved icon export is present. Development fixtures use small text glyphs with explicit control descriptions; replace them with approved shared vector resources when supplied. Decorative glyphs do not carry independent actions.
- Pencil records 44 dp/pt controls. Following the Compose accessibility guidance, interactive controls use a 48 dp effective/visible minimum. This is the smallest deliberate accessibility deviation.
- Client Home, Active Workout, and Trainer Dashboard include a visible back affordance while hosted inside the development catalog. It is catalog-only navigation and not part of the future product shell.
- At 200% Android font scale, set values wrap onto multiple lines rather than shrink or overflow horizontally. The list remains scrollable and all fixed workout actions remain visible.

## Trainer Dashboard responsive polish — 2026-09-04

The Trainer Dashboard originally assigned `Active`, `Messages`, and `Appointments` equal one-third widths while the shared chip applied 16 dp horizontal padding on both sides. The longer `Appointments` label therefore received a smaller text content box than it needed with iOS font metrics and wrapped onto two lines.

The fix is local to this control and the reusable chip API:

- The filter chip accepts an optional horizontal-content-padding value and guards labels with `maxLines = 1`; the existing 16 dp default is unchanged elsewhere.
- At normal scale, the dashboard uses 8 dp chip padding and label-length-aware weights, giving the longer label proportionally more room without changing typography.
- At font scale 1.5 and above, it uses 4 dp chip padding and 4 dp gaps, with intrinsic-width horizontal scrolling retained as a fallback for wider fonts or narrower viewports.
- Every chip keeps the shared 48 dp minimum height. Spaced, non-overlapping segment bounds and a common centered alignment preserve equal height and the approved selected lime treatment.
- No platform-specific layout, global typography change, dependency, or design-token change was introduced.

## PR #2 P2 corrections — 2026-09-11

The three remaining P2 review findings were corrected without expanding Milestone 2 scope:

- The shared five-item bottom navigation uses an equal-width compact layout only when every measured label and glyph fits its available item slot (including the 48 dp target and bar padding); otherwise it uses a content-width `LazyRow`. Labels remain single-line and unabridged; each independently selectable tab keeps at least a 48 dp target, adjacent bounds do not overlap, the row exposes horizontal scrolling, and the selected item is automatically scrolled into view when selection changes. This measured-width refinement supersedes the original font-scale-only threshold.
- `ClientHomeFixtureUiModel` now carries the full appointment date plus presentation-ready weekday and day-number fields. The appointment card reads both visible values from those fields; the Alex/Maya fixture still displays `TUE` and `18`, while a focused alternate-model test verifies `FRI` and `27` flow through the same presentation boundary without parsing localized prose.
- Every Trainer Dashboard “Needs attention” row is a single clipped, rippled `Role.Button` target with a 48 dp minimum and the existing shape, surface, spacing, content, and chevron. Its only click handler forwards `activity.title`; a focused test proves an alternate title is delivered exactly once.

Focused verification results:

| Command/check | Result |
|---|---|
| `./gradlew :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest :shared:iosSimulatorArm64Test :androidApp:assembleDebug --console=plain` | Final run exit 0, **BUILD SUCCESSFUL in 13s**; 92 actionable tasks: 17 executed, 75 up-to-date |
| Android host tests | **16 tests, 0 failures, 0 errors, 0 skipped**; includes three P2 regression tests |
| iOS simulator tests | **15 tests, 0 failures, 0 errors, 0 skipped**; includes the same three common P2 regression tests |
| Native Debug Xcode build for iPhone 16e, iOS 26.2, signing disabled | Exit 0, **BUILD SUCCEEDED**; existing inferred bundle-ID, ICU deployment-target, and always-run script warnings remain |
| Android Pixel 4 API 34 runtime inspection | Client and trainer bars passed at 1.0 and 2.0 font scale. Large-text start/end hierarchy and screenshot inspection confirmed every full label, horizontal scrolling, selected state, safe-area placement, and separated item bounds. All activity entries exposed one full-row clickable/focusable target. |
| iPhone 16e runtime inspection | Client and trainer bars passed at normal (`large`) and `accessibility-large` content sizes. Fresh relaunches applied each size; start/end screenshots and manual inspection confirmed full labels, horizontal scrolling, selected state, and safe-area placement. |
| Catalog/back navigation | Client Home and Trainer Dashboard opened from the catalog and returned successfully on Android and iPhone 16e; existing Foundation/catalog entry remained usable. |
| `git diff --check` | Exit 0 |

The large-text checks above were manual simulator and accessibility-hierarchy inspections, not automated screenshot tests. The screenshots are sanitized, deterministic fixture evidence. TalkBack and VoiceOver traversal were not performed and are not claimed.

## PR #2 follow-up P2 corrections — 2026-09-12

The three findings from review `5181210001` were corrected without expanding Milestone 2 scope:

- `TillFailurePrimaryButton` now resolves enabled, disabled, and loading colors through one semantic palette. While loading, both normal and inverted variants remain disabled and use `disabledText` for the indicator against `elevatedSurface`; the measured token contrast is greater than 3:1. Size, spacing, label behavior, and semantics are unchanged. The catalog exposes both loading variants for representative inspection.
- `CatalogScreenScaffold` accepts a nullable top-action callback and resolves no action unless glyph, description, and handler are all present. Client Home and Trainer Dashboard require and forward an explicit `onProfileClick`; the debug catalog caller responds with a long snackbar explaining that Profile navigation is outside Milestone 2, rather than installing a no-op. Existing glyphs, descriptions, and 48 dp icon-button targets remain unchanged.
- Trainer Dashboard derives `sessions` and `updates` labels directly from `model.appointments.size` and `model.activities.size`. Narrow pure helpers produce numeric zero/multiple labels and singular wording only for one, so the announced section count and visible list always share the same source.

Focused automated coverage now verifies both button palettes use the semantic loading content color with at least 3:1 contrast, a supplied profile callback fires exactly once, a scaffold without a callback resolves no action, and zero/one/multiple session and update wording. The earlier navigation, appointment-date, and activity-callback regression tests remain intact.

Representative manual runtime checks:

- Android at 1.0 and 2.0 font scale: both loading indicators were visibly distinct from their loading containers; Client and Trainer profile controls remained labeled 48 dp targets; the Trainer callback produced the explicit catalog snackbar; the bundled model displayed `3 sessions` and `3 updates`; and a temporary zero-list verification build displayed `0 sessions` and `0 updates`. The temporary model and launch override were removed before final verification.
- iPhone 16e at `large` and `accessibility-large`: both loading variants remained visible, the bundled Trainer screen displayed counts matching its three-item lists, the profile glyph retained its layout, and the existing responsive navigation behavior remained intact. A temporary direct-entry zero-list build displayed `0 sessions` and `0 updates`; all temporary entry/model/catalog-order changes were removed afterward.
- The Client Home `TUE`/`18` appointment presentation and Trainer activity-row interaction contract remained present during the Android regression walkthrough. Common tests continue to exercise alternate appointment data and exact-once activity callback forwarding.

The Android profile action was activated and its visible snackbar response inspected. macOS denied assistive access to scripted Simulator taps during this run, so the iPhone profile action was visually inspected but not interactively activated; its shared callback contract is covered by common tests. These checks were manual screenshots/hierarchy inspections, not automated screenshot tests. TalkBack and VoiceOver traversal were not performed and are not claimed.

Focused 2026-09-12 verification:

| Command/check | Result |
|---|---|
| `./gradlew :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest :shared:iosSimulatorArm64Test :androidApp:assembleDebug --console=plain` | Exit 0, **BUILD SUCCESSFUL in 6s**; 92 actionable tasks: 8 executed, 84 up-to-date. |
| Android host tests | **21 tests, 0 failures, 0 errors, 0 skipped**, including the new loading-palette, top-action, and count contracts. |
| iOS simulator tests | **20 tests, 0 failures, 0 errors, 0 skipped**, including the same common regression contracts. |
| Native Debug Xcode build for iPhone 16e, signing disabled | Exit 0, **BUILD SUCCEEDED**; shared framework and Swift host linked. Existing inferred framework bundle-ID, always-run Kotlin script, and no-AppIntents metadata warnings remain non-fatal. |
| Final Android and iOS install/launch | Passed; both restored builds opened on Foundation Home after all temporary inspection overrides were removed. |
| `git diff --check` | Exit 0. |

## PR #2 typed-model P2 corrections and five-screen audit — 2026-09-12

The three findings from review `5185282452` were corrected from one authoritative typed source per fact:

- Active Workout resolves the primary action from the set list's `Current` status. Exactly one current set enables `Complete set N`; no current set deliberately disables `No current set`; malformed multiple-current data disables `Resolve current set`. The bundled set 3 result is unchanged, and tests cover set 1, set 4, a list without set 3, no current set, and multiple-current normalization.
- Client Home replaces prose-only weekly progress with structured completed/total counts. Both `X of Y workouts`, the circular completed-count indicator, and the combined accessibility description derive from the same normalized values. Negative values clamp to zero, completed clamps to total, and a zero total produces `0 of 0` without a contradictory indicator.
- Trainer Client Details derives its completion text, progress fraction, and progress semantics from structured completed/total counts. Negative values clamp to zero, completed clamps to total, and zero total deterministically produces zero progress without division by zero or NaN. The bundled `12 of 32 completed` and `0.375` fraction are unchanged.

The requested audit covered only Authentication, Client Home, Active Workout, Trainer Dashboard, Trainer Client Details, and their fixture UI models. In addition to the three review findings, it removed these actual duplicated sample-business values:

- Authentication's member name and assigned program;
- Client Home's selected navigation item, next-workout schedule, trainer identity/note metadata, body-weight trend, and consistency period;
- Active Workout's duplicated exercise progress text/fraction, elapsed duration, and exercise set/target/rest summary;
- Trainer Dashboard's selected navigation/section, active-client and unread-message counts/semantics, and initials/glyph presentation;
- Trainer Client Details' selected tab, initials, program week, weekly target, restriction, and recent-activity content.

Static headings, field labels, and action verbs remain literal. A source scan confirmed that synthetic sample-business literals now live in `CatalogFixtures`; paired visual/semantic representations are derived through narrow pure presentation helpers. The Compose skill's accessibility guidance was applied to expose one coherent progress description plus range semantics rather than competing child values.

Focused verification results:

| Command/check | Result |
|---|---|
| `./gradlew :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest :shared:iosSimulatorArm64Test :androidApp:assembleDebug --console=plain` | Exit 0, **BUILD SUCCESSFUL in 1s**; 92 actionable tasks: 6 executed, 4 from cache, 82 up-to-date. |
| Android host tests | **36 tests, 0 failures, 0 errors, 0 skipped**; 21 focused Milestone 2 regression tests plus existing design-system/foundation coverage. |
| iOS simulator tests | **35 tests, 0 failures, 0 errors, 0 skipped**; the same 21 common Milestone 2 regression tests plus existing shared coverage. |
| Native Debug Xcode build for iPhone 16e, signing disabled | Exit 0, **BUILD SUCCEEDED**; existing inferred framework bundle-ID, ICU deployment-target, and always-run Kotlin script warnings remain non-fatal. |
| `git diff --check` and conflict-marker scan | Exit 0; no whitespace error or conflict marker. |

Representative manual runtime checks used temporary direct-entry and alternate-model overrides, all removed before the final builds:

- Android and iPhone 16e default fixtures retained `3 of 4 workouts` with indicator `3`, current set 3 with `Complete set 3`, and `12 of 32 completed` with a 37.5% bar. Alternate fixtures showed 1/4 in both weekly representations, moved the current badge and CTA together to set 4, and showed 8/32 with a 25% bar.
- Android at 200% text showed both loading indicators clearly against their loading containers. Trainer Dashboard retained its labeled 48 dp profile action, `3 sessions`, `3 updates`, appointment date/content, clickable activity rows, responsive horizontally scrollable navigation, and explicit profile-unavailable snackbar.
- iPhone 16e at `accessibility-large` retained the Trainer profile glyph, counts, single-line dashboard segments, and adaptive navigation. Existing committed evidence continues to document loading variants and the trailing large-text navigation state.
- Final Android and iOS installs launched Foundation Home after restoring all temporary model, destination, and text-size overrides.

These were manual visual and accessibility-layout checks, not screenshot automation. TalkBack, VoiceOver, physical devices, CI, iOS Release, and automated screenshot tests were not run and are not claimed. No new screenshot was committed because the existing sanitized default-state evidence remained representative and the alternate-state consistency is more directly protected by focused tests.

## PR #2 responsive tabs, forward callbacks, password toggle P2 corrections and bounded audits — 2026-09-12

The four P2 findings from review `5186014649` were corrected alongside two bounded audits across the Milestone 2 design catalog:

1. **Client-detail tabs responsiveness**:
   - Extracted and generalized `TillFailureSegmentedControl` in `TillFailureComponents.kt`. It uses a weighted row at normal font scale (< 1.5) and a scrollable `LazyRow` with `rememberLazyListState` and `LaunchedEffect(selectedIndex)` auto-scroll at large font scale (>= 1.5).
   - Refactored `TrainerClientDetailsFixtureScreen` to use this shared control with typed `TrainerClientDetailsTab` and `(TrainerClientDetailsTab) -> Unit`.
   - Labels remain unabridged and fully visible without ellipsis or clipping at 200% font scale, preserving the 48 dp minimum height, independent touch targets, and non-overlapping bounds.
2. **Dashboard section-header actions**:
   - `TillFailureSectionTitle` now exposes `onActionClick: (() -> Unit)? = null` and renders the action as interactive only when both `action != null` and `onActionClick != null`, eliminating implicit dead action buttons.
   - `TrainerDashboardFixtureScreen` introduces explicit screen callbacks `onViewScheduleClick: () -> Unit` and `onReviewActivitiesClick: () -> Unit`, forwarding them through `DashboardReadyContent` to the respective section titles as coherent accessible targets.
3. **Workout Note callback**:
   - Added an explicit `onNoteClick: () -> Unit` callback parameter to `ActiveWorkoutFixtureScreen` and forwarded it from the visible "Note" text button without leaving a no-op handler.
4. **Password visibility toggle**:
   - Added `passwordVisible: Boolean = false` to `AuthFixtureUiModel` and an explicit `onPasswordVisibilityToggle: () -> Unit` callback to `AuthFixtureScreen`.
   - Rendered an accessible trailing `Box` action with `sizeIn(48.dp, 48.dp)`, button role, accessible description (`"Show password"` / `"Hide password"`), and visual transformation (`VisualTransformation.None` / `PasswordVisualTransformation`).
   - Toggling preserves the password string without clearing or modifying it.

**Interactive-affordance audit**:
- Audited shared components and all five fixture screens:
  - Removed default empty `{}` callbacks from reusable components (`LoggedSetRow`, `WorkoutRow`, `ExerciseRow`, `TillFailureEmptyState`, `TillFailureSectionTitle`).
  - Applied `.clickable(role = Role.Button)` conditionally only when `onClick != null`, preventing elements without actions from exposing dead button roles.
  - In `DesignCatalogScreen.kt` and `FoundationNavigation.kt`, wired deliberate interactive handlers for chips, actions, tabs, note, and error state retry buttons.

**Large-text control audit**:
- Audited horizontal multi-item controls across the five screens (segmented controls, bottom navigation, side-by-side action buttons, status/action rows).
- Segmented controls switch at >= 1.5 font scale; bottom navigation now switches when measured text and target widths exceed the available equal-width slots, including at moderate font scales on narrow viewports. Both preserve single-line unabridged labels, 48 dp touch targets, and reachable selections.
- Side-by-side buttons maintain minimum touch targets and wrap or flex safely without clipping.

Focused verification results:

| Command/check | Result |
|---|---|
| `./gradlew :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest :shared:iosSimulatorArm64Test :androidApp:assembleDebug --console=plain` | Exit 0, **BUILD SUCCESSFUL in 13s**; 90 actionable tasks: 16 executed, 74 up-to-date. |
| Android host tests | **22 tests, 0 failures, 0 errors, 0 skipped**; includes segmented control layout, section action presence, password toggle helpers, and callback forwarding. |
| iOS simulator tests | **21 tests, 0 failures, 0 errors, 0 skipped**; the same common regression contracts. |
| Native Debug Xcode build for iPhone 16e, signing disabled | Exit 0, **BUILD SUCCEEDED**; shared framework and Swift host linked cleanly. |
| `git diff --check` and conflict-marker scan | Exit 0; no whitespace error or conflict marker. |

## PR #2 snackbar and disabled filter-chip P2 corrections — 2026-09-12/13

The two findings from review `5187726725` were corrected within the existing Milestone 2 catalog and shared components:

- The remembered `SnackbarHostState` remains shared with the existing buffered, lifecycle-bound effect collector. Explicit destination ownership renders its host in the root scaffold for Foundation Home, Foundation Details, and Authentication, or in exactly the active `CatalogScreenScaffold` for the catalog and its client/trainer/workout fixtures. Inactive outgoing entries receive no host. The nested scaffold lays its snackbar above its measured client/trainer navigation or workout action bar, whose navigation-bar insets remain applied; the root-owned screens use safe-drawing insets. No device-specific bottom offset or second event host was added. The effect collector still launches snackbar presentation separately so it cannot suspend navigation-effect handling.
- `TillFailureFilterChip` now resolves container, content, and border tokens from both `enabled` and `selected`. The four states have distinct treatments; disabled selected remains visibly selected without reusing enabled lime, and both disabled labels use readable semantic secondary text. Disabled semantics are explicit, `selectable` is disabled, and the callback path is also gated. The component's shape, label, responsive behavior, and 48 dp minimum are unchanged. The development catalog displays all four states.

The bounded audit covered the root and nested scaffolds, the shared host, Client and Trainer bottom navigation, Active Workout actions, and shared buttons, fields, filter chips, status chips, and navigation items. No additional Milestone 2 defects requiring correction were found. Existing disabled/loading button behavior and previously repaired callback wiring remained intact; no dependency or version changed.

| Verification | Result |
|---|---|
| Focused ownership/component tests | Passed: root versus nested ownership, only one active nested host, all four chip palettes, disabled semantics, disabled callback gating, and disabled-label contrast of at least 4.5:1. |
| `./gradlew :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest :shared:iosSimulatorArm64Test :androidApp:assembleDebug --console=plain` | Final run exit 0, **BUILD SUCCESSFUL**; 92 tasks (3 executed, 89 up-to-date). The earlier compiling run executed the changed suites: Android host **51 tests**, iOS simulator **50 tests**; zero failures, errors, or skips. |
| Native Xcode Debug build, iPhone 16e simulator, signing disabled | **BUILD SUCCEEDED**; the existing inferred bundle-ID, ICU simulator-target, and always-run script notices were non-fatal. |
| Android portrait manual checks | Foundation one-shot message, Client `Workouts`, Trainer `View schedule`, and Active Workout `Note` each produced one visible snackbar above the active screen's bottom region and gesture navigation area. Returning from Client Home while its snackbar remained visible changed the active destination without blocking the navigation action or duplicating the snackbar. |
| Android 200% text manual checks | All four chip labels remained complete and the disabled selected/unselected nodes exposed `enabled=false` with the correct selected value. Foundation snackbar expanded without colliding with system navigation. The font scale was restored to 1.0 afterward. |
| iPhone 16e manual checks | The current Debug app installed and launched on iOS 26.2; Foundation Home was visually inspected at normal and `accessibility-large` content sizes, with no clipped controls or safe-area overlap. Content size was restored to `large` afterward. Simulator tooling in this pass could capture the screen but not activate fixture actions, so iOS snackbar/bar interactions and chip states were not independently inspected; shared common tests and the native build cover their implementation contract, not an iOS visual result. |

The runtime evidence above is manual screenshot and Android accessibility-hierarchy inspection, not automated screenshot testing. No fixture override or machine-specific screenshot was retained in the repository. TalkBack, VoiceOver, physical-device, CI, iOS Release, and Xcode UI tests were not run.

## PR #2 measured-width five-item navigation correction — 2026-09-13

Review `5189575878` identified a remaining narrow-viewport case: a 320 dp bar at 125% text could still select the compact layout and clip `Dashboard` or `Messages`. The shared `TillFailureBottomNavigation` now measures each label and glyph with the rendered Material text styles and current Compose density/font scale. `BoxWithConstraints` supplies the actual width left after the scaffold's system-navigation insets. The resolver subtracts the compact bar's horizontal padding, divides the remaining pixel width across its five equal slots, and selects `Weighted` only if every slot fits its measured content and the 48 dp minimum target. Otherwise the existing intrinsic-width `LazyRow` supplies item padding, gaps, full accessibility labels, and selected-item auto-scroll. There is no device breakpoint, shortened label, reduced text size, or platform-specific branch.

Focused common resolver tests use representative pixel-width inputs for client and trainer label order and normal-width/normal-font, 320 dp/125%, and large-font cases, plus the one-pixel boundary after outer padding. Actual Compose text measurement and layout were checked separately in the Android manual pass. The full shared/Android/iOS simulator verification command passed: common metadata compiled, Android host **53 tests** and iOS simulator **52 tests** completed with zero failures/errors/skips, and Android Debug assembled. The native iPhone 16e Xcode Debug build succeeded. `git diff --check` and the conflict-marker scan passed.

Android manual inspection used a temporary 320 dp viewport at 125% and 200% text. On both Client Home and Trainer Dashboard, the bar scrolled, `Dashboard` and `Messages` appeared in full when brought into view, the separate item bounds did not overlap, and their heights met the 48 dp target. Client navigation and Trainer `View schedule` still produced a single snackbar above the bar at 125%. The emulator's original 1080 × 2280 resolution and 100% text setting were restored. The current iPhone 16e Debug build installed and launched, but this Simulator session exposed no interactive device window, so the new iOS bar layout was not manually inspected; the shared iOS tests and native build are not substitutes for that visual check. No temporary screenshots or overrides were committed.

At the intentionally combined 320 dp/200% Android setting, the separate Trainer `View schedule` section action visibly wraps vertically. That pre-existing fixture issue is outside this bottom-navigation P2 correction and is not claimed fixed. TalkBack, VoiceOver, physical-device, CI, automated screenshot, and iOS Release checks were not run.

## PR #2 Authentication catalog input correction — 2026-09-13

Review `5191112238` found that the enabled Authentication email and password fields were passed no-op change callbacks by the development catalog. The `AuthFixtureScreen` remains stateless and callback-driven. Its `CatalogAuthentication` entry now remembers a local `AuthFixtureDraft` initialized from `CatalogFixtures.auth`; each field callback replaces only that draft value, and Show/Hide replaces only the visibility flag. The rendered `AuthFixtureUiModel` is copied from the original fixture with the current draft values. Compose's String-valued text field receives each updated value synchronously, so typing, deletion, and cursor-positioned edits remain visible. The draft survives recomposition and Show/Hide within the open entry, including ordinary background/foreground transitions while the process remains alive. Back removes that entry; reopening creates a fresh draft from the original fixture, with the original email/password and hidden password. Activity or process recreation also resets the unsaved draft. It is deliberately not saved to authentication, storage, or another screen, and no real sign-in was added.

The bounded audit found no editable text fields in the other four reference fixture screens. The standalone Authentication Compose preview had the same enabled-field no-ops and now keeps preview-local model state. The catalog's enabled `Weight` error-field sample also discarded edits; it now uses local saveable field state, like the adjacent `Client name` sample. Its error styling remains a visual example, not validation.

Focused common tests cover successive email/password updates, exact middle-of-string replacements, immutable fixture defaults, Show/Hide without input loss, and fresh-entry reset. Existing password transformation/accessible-label tests remain passing. The full shared/Android/iOS simulator command passed: common metadata compiled, Android host **55 tests** and iOS simulator **54 tests** completed with no failures/errors/skips, and Android Debug assembled. The native iPhone 16e Xcode Debug build passed. `git diff --check` passed.

Android emulator manual input confirmed immediate email updates, an insertion before the final character without cursor jumping, password entry while hidden, exact edited password after Show, the Hide/Show accessibility labels, hiding again without loss, and fixture defaults after Back and reopen. The catalog's `Weight` example also retained a typed character while its error support text stayed visible. Sign in still displayed only the development-only snackbar. The updated iOS app installed, launched, and rendered Foundation Home, but the booted Simulator exposed no interactive window in this session; iPhone email/password entry was **not** manually verified. These are manual observations, not automated UI or screenshot tests. No temporary input or screenshot was committed. TalkBack, VoiceOver, physical devices, CI, and iOS Release were not run.

## Development catalog access and release isolation

- Android Debug: launch the app, then choose **Open development UI catalog** on Foundation Home. `MainActivity` passes `BuildConfig.DEBUG` to shared Compose.
- iOS Debug: launch the Xcode `iosApp` scheme, then choose the same entry. Swift passes `_isDebugAssertConfiguration()` to `MainViewController`.
- The catalog reuses the existing typed Navigation 3 back stack and decorators. It does not create a second navigation framework or role selector.
- Release hosts pass `false`; no release catalog button or production deep link was added. Android Release assembled successfully and its generated `BuildConfig.DEBUG` is `false`. No release runtime was installed because signing changes were outside scope; iOS release isolation remains code-inspected rather than runtime-tested.
- Foundation Home, Foundation Details, and their destination-scoped Koin ViewModels remain available and unchanged in responsibility.

## Files and responsibilities changed

| Area | Responsibility |
|---|---|
| `shared/.../core/designsystem` | Semantic tokens, Material theme, preview matrix, shared components, status/accessibility contracts |
| `shared/.../designcatalog` | Deterministic models, five stateless fixture screens, safe-area scaffold, component catalog |
| `shared/.../app/FoundationNavigation.kt` | Typed debug-catalog destinations within the existing Navigation 3 back stack |
| Foundation Home MVI | One debug-catalog user event/effect and conditional entry; original Details/effect flow retained |
| Android host | Debug flag, generated BuildConfig enablement, dark transparent system bars |
| iOS host | Debug flag passed into shared Compose and dark appearance configuration |
| Tests | Catalog navigation effect plus touch-target, visible status-label, and fixture-continuity contracts |
| Documentation/evidence | README/status updates, this report, and sanitized screenshots under `evidence/milestone-2` |

## Dependencies

**None added or upgraded.** The implementation uses the verified Milestone 1 Compose Material 3, Navigation 3, lifecycle, Koin, serialization, coroutines, and testing stack already present in `gradle/libs.versions.toml`. No Firebase, database, network, media, analytics, screenshot-test, or backend dependency was introduced.

## Environment

| Tool | Observed version |
|---|---|
| macOS | `26.6.2` (`25G83`), arm64 |
| Java | Oracle JDK `21.0.8+12-LTS-250` |
| Gradle wrapper | `9.1.0`; configured daemon is compatible Zulu Java 21 |
| Kotlin project plugin | `2.4.10` (Gradle reports embedded Kotlin `2.2.0`) |
| Android SDK | compile/target 36, min 29; Pixel 4 API 34 emulator |
| Android CLI | `1.0.16261425` |
| adb | `1.0.41`, package version `33.0.2-8557947` |
| Xcode | `26.2` (`17C52`) |
| iOS simulator | iPhone 16e, iOS 26.2; identifier omitted from repository evidence |

## Fresh command and test evidence

| Command/check | Result |
|---|---|
| Initial branch/HEAD/status checks | Expected `foundation/milestone-1`, exact baseline commit, clean tree; local feature branch created |
| Pencil variable/frame reads and screenshots | Foundations/components and five exact frames inspected successfully; `.pen` unchanged |
| `./gradlew :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest :androidApp:assembleDebug --console=plain` | Exit 0, **BUILD SUCCESSFUL**; metadata executed, host tests passed, debug APK assembled |
| Final `./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug --console=plain` after safe-area correction | Exit 0, **BUILD SUCCESSFUL in 17s**; 72 actionable: 10 executed, 62 up-to-date |
| `./gradlew :androidApp:assembleRelease --console=plain` | Exit 0, **BUILD SUCCESSFUL in 46s**; 80 actionable: 51 executed, 7 from cache, 22 up-to-date; generated release `BuildConfig.DEBUG = false` verified |
| `./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:iosSimulatorArm64Test --console=plain` | Exit 0, **BUILD SUCCESSFUL in 16s**; 24 actionable: 7 executed, 17 up-to-date |
| `xcodebuild -list -json -project iosApp/iosApp.xcodeproj` | Exit 0; project/target/scheme `iosApp`; still no Xcode test target |
| Native Debug Xcode build for iPhone 16e simulator, signing disabled | Exit 0, **BUILD SUCCEEDED**; Swift host compiled and shared framework linked |
| Android CLI install/launch | Exit 0; debug APK installed; final cold launch `Status: ok`, `TotalTime: 1327 ms` |
| iOS `simctl` install/launch | Exit 0; final app launch succeeded on iPhone 16e simulator |
| 2026-09-08 stacked rebase verification: `./gradlew :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest :androidApp:assembleDebug :shared:compileKotlinIosSimulatorArm64 --console=plain` | Exit 0, **BUILD SUCCESSFUL in 10s**; 78 actionable: 16 executed, 2 from cache, 60 up-to-date |

Focused 2026-09-04 polish checks:

| Command/check | Result |
|---|---|
| `./gradlew :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest :androidApp:compileDebugKotlin :shared:compileKotlinIosSimulatorArm64 :shared:iosSimulatorArm64Test --console=plain` | Exit 0, **BUILD SUCCESSFUL in 22s**; 59 actionable: 14 executed, 45 up-to-date |
| `./gradlew :shared:testAndroidHostTest :shared:iosSimulatorArm64Test :androidApp:assembleDebug --console=plain` after the final responsive adjustment | Exit 0, **BUILD SUCCESSFUL in 12s**; 90 actionable: 16 executed, 74 up-to-date |
| Native Debug `xcodebuild` for iPhone 16e, iOS 26.2, signing disabled | Exit 0, **BUILD SUCCEEDED**; existing ICU deployment and always-run script warnings remain |
| Android install/launch and Trainer Dashboard inspection | Passed at normal and 200% font scale; all three labels are single-line and fully visible |
| iPhone 16e install/launch and Trainer Dashboard inspection | Passed at normal and `accessibility-large` content size; all three labels are single-line and fully visible |

Android host tests after the polish: **13 tests, 0 failures, 0 errors, 0 skipped**. This is 12 common tests (including four design-system contracts and the catalog navigation effect) plus the Android-only ViewModel-store scoping test.

iOS simulator tests after the polish: **12 tests, 0 failures, 0 errors, 0 skipped**. These are the common design-system and MVI tests. They do not constitute native Swift UI tests.

Resource conversion/accessor tasks with no input reported `NO-SOURCE`; they are not counted as checks. Kotlin plugin configuration, SwiftPM umbrella, dSYM/copy, and related coordination tasks reported `SKIPPED`; they are not claimed as executed tests.

The first sandboxed Gradle invocation could not open the existing user Gradle-cache lock. It was rerun with approved cache access and passed. The first Android CLI screenshot attempt used a nonexistent `--device` option; with one emulator attached, the documented command without that option succeeded.

## Runtime, navigation, and lifecycle results

### Android

- The debug entry opened the component catalog and all five typed visual destinations. Each destination opened and returned to the catalog with Android back.
- The original Foundation regression passed after a cold launch: Home rep changed from 0 to 1; Details opened as ViewModel #1; system back retained Home rep 1; reopening Details showed ViewModel #2, previously released 1, detail reps 0.
- The semantics hierarchy exposed descriptive Back/profile/workout-option/navigation labels and selected bottom-navigation state. Touch targets are centralized at 48 dp.
- At system font scale 2.0, Active Workout remained vertically scrollable, had no unintended horizontal overflow, retained non-color status labels, and kept Note/Complete/Finish above the navigation inset.

### iOS

- The native Xcode app built, installed, and launched. Foundation Home rendered with the development-catalog button and white system status content on the approved dark background.
- Moving to Settings and relaunching TillFailure returned the same app PID, providing a foreground/background smoke check without a crash.
- At iOS `accessibility-large` content size, the Foundation Home controls reflowed without clipping; the catalog label wrapped instead of truncating.
- The user completed the interactive five-screen catalog walkthrough described below. During the responsive polish, the agent additionally opened Trainer Dashboard through the visible Simulator UI and inspected it at normal and `accessibility-large` content sizes.

## User-performed manual verification — 2026-09-04

The following results are attributed to the user, not to automated tests or an agent-run test harness:

- All five catalog screens opened successfully, and back navigation worked from each screen.
- Authentication was inspected with the software keyboard open; the primary action remained accessible.
- Client Home, Active Workout, Trainer Dashboard, and Trainer Client Details were visually compared across Android and iOS.
- The previously suspected missing Active Workout progress/timer was a scroll-position misunderstanding; the content is present and works correctly.
- Trainer Dashboard and Trainer Client Details opened successfully on iOS.
- The user identified the Trainer Dashboard `Appointments` wrapping issue during this review. The shared fix was subsequently compiled and manually rechecked by the agent on Android and iPhone 16e.

## Agent-performed responsive checks — 2026-09-04

- On Android at normal font scale, UI hierarchy and screenshot inspection showed the three labels centered on one line with distinct, equally high segment surfaces and no overlap.
- On Android at 200% font scale, all three full labels remained visible on one line. Compact spacing allowed them to fit in the tested viewport; horizontal scrolling remains available as a fallback.
- On iPhone 16e at normal content size, all three full labels rendered on one line and the selected lime segment retained the approved shape and alignment.
- After relaunching at iOS `accessibility-large`, all three labels still rendered fully on one line at equal segment height. The simulator setting was restored to `large` afterward.
- The four post-fix screenshots were captured and visually inspected manually. They are evidence images, not automated screenshot tests.

## Visual comparison findings

The Android screenshots were manually compared by the agent against Pencil renderings at the comparable 393 dp viewport. This was not automated screenshot/golden testing.

- **Authentication:** hierarchy, dark field surfaces, lime action, title, and spacing align; approved font/icon assets remain the documented gap.
- **Client Home:** lime next-workout card, black inverse action, appointment/note/metric hierarchy, and five-item navigation align; the catalog-only back action is an intentional difference.
- **Active Workout:** exercise hierarchy, progress, set rows/status language, timer banner, and fixed actions align. Safe-area and 48 dp requirements make the composition slightly taller and scrollable.
- **Trainer Dashboard:** date/greeting, attention badge, filters, appointments, activity queue, and trainer navigation align. The local content-aware segment allocation prevents the iOS `Appointments` wrap while retaining the approved compact label and selected state.
- **Trainer Client Details:** profile/program/metric hierarchy, injury and trainer-only note boundaries, activity, and assignment action align.

Evidence: [`evidence/milestone-2/README.md`](evidence/milestone-2/README.md).

## Accessibility results

- Automated contract checks enforce a minimum 48 dp target and a distinct visible label for every logged-set status.
- Headings, button/tab roles, selected state, and explicit icon-button descriptions are applied; status uses glyph plus text, never color alone.
- Android hierarchy inspection passed for representative labels and selected navigation state. TalkBack was not installed, so an actual TalkBack traversal was **not run**.
- Android 200% text inspection passed for Active Workout and the polished Trainer Dashboard. Other fixture previews include the same 200% configuration but were not all manually launched at that scale.
- iOS accessibility-large Foundation and Trainer Dashboard checks passed. The user opened all five iOS fixture screens, but VoiceOver traversal was **not run**.

## Warnings and remaining limitations

- Licensed Manrope/Inter binaries and approved vector icon exports remain missing.
- The Shared framework still warns that its bundle ID is inferred; no unrelated build-identity change was made.
- Xcode still warns that an ICU object targets iOS Simulator 18.5 while the app deployment target is 18.2. The existing warning was not silently resolved by changing deployment targets.
- Android tooling still reports SDK XML version 4 versus supported version 3 during host-test resource processing.
- There is no Xcode test target, Android device-test run, CI run, physical-device test, automated screenshot test, TalkBack run, or VoiceOver run.
- Release catalog isolation is implemented through native compile-time debug flags. Android Release assembly passed and generated `BuildConfig.DEBUG = false`; no release runtime was installed because signing changes were outside scope. iOS isolation is code-inspected but not release-runtime-tested.
- These fixtures are isolated technical compositions for replacement/integration by later approved product milestones. They contain no durable state or business behavior.

## Acceptance assessment

| Milestone 2 criterion | Result |
|---|---|
| Central dark/lime semantic tokens, spacing/radii/borders, theme | Satisfied, with documented font-asset fallback |
| Required reusable components and important states | Satisfied |
| Five exact, stateless, deterministic Pencil-mapped compositions | Satisfied in shared code; Android agent inspection and user-attributed cross-platform comparison passed |
| Small/normal/200% previews | Satisfied in source; Trainer Dashboard large-text checks passed on Android and iPhone 16e |
| Debug-only catalog on Android/iOS using existing navigation | Implemented; Android agent pass and user-verified five-screen iOS walkthrough passed |
| Release entry unavailable | Android Release assembled with generated `BuildConfig.DEBUG = false`; iOS compile-time flag inspected; release runtime not launched |
| Foundation navigation/scoping regression | Satisfied on Android and existing host-test layers |
| Shared/Android/iOS builds and tests | Satisfied with recorded warnings |
| Native Xcode app build/launch | Satisfied |
| Interactive iOS catalog and five-screen visual pass | User-verified on 2026-09-04; agent independently rechecked Trainer Dashboard after the fix |
| TalkBack and VoiceOver spot checks | **Not run; unavailable in the current tooling** |

Milestone 2 is implemented and ready for review, but is not marked fully accepted while TalkBack and VoiceOver checks remain unverified. This report does not authorize a deployment, Firebase work, or Milestone 3.
