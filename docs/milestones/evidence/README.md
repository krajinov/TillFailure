# Sanitized runtime evidence

All images are local Android/iOS simulator captures of the temporary technical foundation. They contain no user accounts, credentials, Firebase data, health data, messages, tokens, or private media.

| File | Evidence |
|---|---|
| `android-light-system-bars.png` | Rebuilt Android APK under light system appearance; light status-bar and gesture-navigation controls remain legible over the fixed dark app theme. |
| `android-dark-system-bars.png` | Rebuilt Android APK under dark system appearance; light status-bar and gesture-navigation controls remain legible over the fixed dark app theme. |
| `android-home-final.png` | Final rebuilt Android APK cold-launched to Foundation Home. |
| `android-details-final.png` | Reopened Details showing ViewModel `#2` and `Previously released: 1`. |
| `android-one-shot-effect.png` | Snackbar produced by the Home one-shot MVI effect. |
| `android-details-first.png` | First Details entry (`#1`, zero prior releases) during the initial runtime pass. |
| `android-details-second.png` | Second Details entry and released-count observation during the initial runtime pass. |
| `android-home.png` | Home state retained after returning from Details during the initial runtime pass. |
| `ios-home-final.png` | Final Xcode-built iOS app launched and rendered shared Foundation Home. |
| `ios-resumed.png` | iOS app returned after a Settings background/foreground transition. |
| `ios-m3-cold-launch.png` | Signed iOS Firebase-linked build cold-launched without the opt-in spike flag; Foundation Home remains the default UI. |
| `android-m3-cold-launch.png` | Android Firebase-linked debug build cold-launched; Foundation Home remains the default UI. |

Screenshots support runtime observations; they do not substitute for automated assertions or prove process-death restoration.

The light/dark system-bar captures were produced by Codex on 2026-09-08 using the `Pixel_4_API_34` emulator after applying the PR #1 system-bar review fix. They contain only the technical foundation UI and emulator chrome.

## User-attributed iOS verification

On 2026-09-01, the user manually passed the interactive flow on an iPhone 16e simulator. The user reported Home state retention, fresh Details state/ViewModel creation after pop and reopen, visible prior-release diagnostics, and state preservation through background/foreground. Supplied screenshots showed `Home reps: 4` and Details ViewModel `#9`, previously released `8`, with detail reps `0`.

Those supplied screenshots were not accessible as filesystem attachments during finalization, so no sanitized repository copy or broken link was added. The visible values are screenshot-supported; the transition results remain explicitly user-attributed.
