# ADPF Performance Lab

Android diagnostics prototype for observing this app's frame pacing during sustained runs and Android thermal signals.

## v1.2 platform diagnostics
- Select a 60, 90, or 120 FPS frame-time budget for analysis.
- Record this app's `Choreographer` callback intervals each second.
- Calculate p95 from raw individual frame intervals across each comparison window.
- Count intervals exceeding the selected frame-time budget.
- Compare the first 10 seconds with the latest 10 seconds after a 20-second run.
- Listen for thermal-status changes on API 29+ and sample thermal headroom no more than once per second where supported.
- Optionally request sustained performance mode for this app's Activity window.
- Report Performance Hint API service availability and the selected Game Mode for this app when the APIs exist.

## API limitations (intentional)
- PerformanceHintManager is not opened as a hint session in this UI-only diagnostics workload. A session should report measured work duration for long-lived threads owned by this process; Choreographer callback intervals are wall-clock frame intervals, not the callback's actual work duration. A future dedicated workload test can exercise hint sessions honestly.
- Sustained performance mode is a request for this app's window only. Actual behavior is device/OEM-dependent; it does not change another game's mode.
- GameManager reports the game mode for this application only. This diagnostic utility is not marked as a game and does not control BGMI, Free Fire MAX, or other apps.
- WorkManager/JobScheduler should be used for deferred tasks such as exporting or processing run data, not the real-time frame loop.
- The target FPS is a measurement threshold, not a cap or performance boost. This app measures its own UI callback pacing, not a game's private rendering FPS. It cannot prove thermal throttling caused a decline.

## Build and test
Use JDK 17 and Android SDK platform 35. GitHub Actions runs `testDebugUnitTest` and builds the debug APK.

No root or special permissions are required. Thermal APIs are queried only when supported.

## Next steps
1. Validate API behavior on physical devices.
2. Add CSV export and repeatable test labels.
3. Add an optional, separate CPU workload benchmark with correctly measured work durations for PerformanceHintManager.
