# ADPF Performance Lab

Android diagnostics prototype for observing sustained workload performance and Android thermal signals.

## What it measures
- Per-second synthetic-loop FPS and frame-budget misses.
- p95 frame time from a rolling sample.
- Android thermal status and thermal headroom where the device/API exposes them.
- Baseline (first 10 seconds) versus recent (last 10 seconds) comparison after a 20-second run.

**Important:** this loop is not a game's render loop. It cannot read another game's private FPS data, guarantee higher FPS, or prove that thermal conditions caused a slowdown. Thermal headroom may be unavailable. Results should be compared across repeatable runs with similar brightness, game load, and ambient temperature.

## Build
Use Android Studio, or run a Gradle Android build with JDK 17 and Android SDK platform 35. GitHub Actions builds the debug APK on pushes and pull requests.

No root or special permissions are required. Thermal APIs are queried only when supported. This diagnostics-first build does not claim ADPF hints will boost performance.

## Planned next steps
1. Validate UI and thermal API behavior on physical devices.
2. Add CSV export and repeatable test labels.
3. Add optional ADPF hint-session instrumentation, reporting availability separately from performance outcomes.
