# Milestone 2 visual evidence

Sanitized simulator screenshots captured during local Milestone 2 verification on 2026-09-01, 2026-09-02, the focused responsive recheck on 2026-09-04, and the first two PR #2 P2 rechecks on 2026-09-11 and 2026-09-12. Later P2 rechecks used temporary local screenshots and accessibility-layout inspection. The 2026-09-12/13 snackbar and disabled-chip recheck included Android portrait and 200% text captures of all four chip states and snackbar placement above foundation, client, trainer, and workout bottom regions, plus iPhone 16e Foundation Home at normal and accessibility-large sizes. No additional image was committed: focused tests are the durable ownership/state evidence, while these captures supported manual inspection. These fixtures contain deterministic synthetic design data only (including fictional account, workout, and health-note copy); no real credential, Firebase, message, private-media, device identifier, local path, or production data is present. Screenshots are manually inspected evidence, not automated screenshot tests.

Pencil reference images remain in the approved encrypted design file and were inspected read-only through Pencil tooling; they are not copied here.

The 2026-09-13 measured-width navigation recheck used temporary Android captures and accessibility-layout bounds at a 320 dp viewport with 125% and 200% text. Both client and trainer bars showed full trailing labels after scrolling and non-overlapping targets; the 125% snackbar checks remained above the bars. These captures were not committed. The current iPhone 16e Debug host was built and launched, but no interactive Simulator window was available to inspect its bars in this pass.

## Index

- `android-catalog.png`: debug-only catalog entry and reference list.
- `android-auth.png`: Authentication fixture mapped to Pencil `QVhze`.
- `android-client-home.png`: Client Home fixture mapped to `OmHDs`.
- `android-loading-buttons-large-text.png`: normal and inverted primary-button loading variants at Android 200% font scale; both semantic disabled-content spinners remain visible against the loading container.
- `android-client-home-large-text-nav-end.png`: Client Home at Android 200% font scale after horizontally scrolling the adaptive bottom navigation to its final destinations; full `Schedule`, `Progress`, and `Messages` labels remain visible.
- `android-active-workout.png`: Active Workout fixture mapped to `v66zG`.
- `android-trainer-dashboard.png`: Trainer Dashboard fixture mapped to `eq0D5`.
- `android-trainer-dashboard-responsive.png`: post-fix Trainer Dashboard at normal Android font scale.
- `android-trainer-dashboard-large-text.png`: post-fix Trainer Dashboard at Android 200% font scale.
- `android-trainer-dashboard-large-text-nav-end.png`: Trainer Dashboard at Android 200% font scale after horizontally scrolling the adaptive bottom navigation; full `Programs`, `Schedule`, and `Messages` labels remain visible.
- `android-trainer-client-details.png`: Trainer Client Details fixture mapped to `bLqqR`.
- `android-active-workout-large-text.png`: Android 200% text smoke check.
- `ios-foundation-home.png`: native iOS host launch and debug-catalog entry evidence.
- `ios-foundation-home-large-text.png`: iOS accessibility content-size host smoke check.
- `ios-client-home-accessibility-large-nav-end.png`: Client Home on iPhone 16e at `accessibility-large` after horizontally scrolling the adaptive bottom navigation; full trailing destination labels remain visible.
- `ios-trainer-dashboard-responsive.png`: post-fix Trainer Dashboard on iPhone 16e at normal content size.
- `ios-trainer-dashboard-derived-counts.png`: Trainer Dashboard on iPhone 16e using the bundled three-item model; the visible `3 sessions` and `3 updates` labels agree with their rendered lists and the profile action remains present.
- `ios-trainer-dashboard-large-text.png`: post-fix Trainer Dashboard on iPhone 16e at `accessibility-large` content size.
- `ios-trainer-dashboard-accessibility-large-nav-end.png`: Trainer Dashboard on iPhone 16e at `accessibility-large` after horizontally scrolling the adaptive bottom navigation; full `Programs`, `Schedule`, and `Messages` labels remain visible.
