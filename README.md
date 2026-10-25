# ADPF Performance Lab

Android diagnostics prototype for observing this app's own frame pacing during sustained runs and Android thermal signals.

## v1.1 diagnostics
- Select a 60, 90, or 120 FPS frame-time budget for analysis.
- Record this app's `Choreographer` callback intervals each second.
- Calculate p95 from raw individual frame intervals across each comparison window.
- Count intervals exceeding the selected frame-time budget.
- Compare the first 10 seconds with the latest 10 seconds after a 20-second run.
- Read Android thermal status and thermal headroom when the OS exposes them.
- Unit tests cover percentile calculations, readiness, and target-budget behavior.

**Important:** the selected target is a measurement threshold, not an FPS cap or performance boost. This app measures its own UI callback pacing, not BGMI, Free Fire MAX, or another app's private rendering FPS. It cannot prove thermal throttling caused a decline. Thermal headroom may be unavailable. For fair comparisons, keep display refresh, brightness, workload, and ambient conditions similar.

## Build and test
Use JDK 17 and Android SDK platform 35. GitHub Actions runs `testDebugUnitTest` and builds the debug APK.

No root or special permissions are required. Thermal APIs are queried only when supported. ADPF performance hints are not yet used to claim a boost.

## Next steps
1. Validate the metrics and thermal API behavior on physical devices.
2. Add CSV export and repeatable test labels.
3. Add optional ADPF hint-session instrumentation, reporting availability separately from performance outcomes.
