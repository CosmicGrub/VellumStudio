# Macrobenchmark baseline

Real, on-device numbers from the `:benchmark` module (see `benchmark/src/main/java/com/vellum/studio/benchmark/`),
recorded here so a future run has something concrete to diff against instead of a vague "should
benchmark this sometime." Re-run both tests and update this file (new numbers + new date) whenever
you want to check for a regression -- see "How to reproduce" at the bottom.

## Measured

**Date:** 2026-08-25
**Device:** Samsung Galaxy Tab S9 FE (SM-X518U, codename `gts9fe`), serial `R52X101MB6W`
**OS:** Android 16 (API 36), build `BP4A.251205.006`, ART mainline `371000140`
**Hardware:** 8 CPU cores, max 2.4 GHz, ~5.3 GB RAM, `cpuLocked=false` (thermal/frequency not pinned
-- see Caveats)
**App state:** fresh install (`adb uninstall com.vellum.studio` immediately before each run), the
`benchmark` build type (see "Build type" below)
**compilationMode reported by Macrobenchmark:** `run-from-apk` (no Baseline Profile exists yet for
this project -- see Caveats)

### Cold app startup -- `StartupBenchmark#coldStartup`

10 iterations, `StartupMode.COLD`, `CompilationMode.DEFAULT`, metric `StartupTimingMetric`.

| | min | median | max |
|---|---|---|---|
| `timeToInitialDisplayMs` | 629.2 | 635.9 | 783.9 |

Per-iteration raw values (ms): 783.9, 698.8, 634.1, 631.0, 629.2, 652.6, 669.2, 637.2, 633.3, 634.7
-- note the first iteration (783.9) is the high outlier; iterations 1-9 cluster tightly around
630-670ms. `coefficientOfVariation` = 0.074.

### Canvas pan/zoom frame timing -- `PanZoomFrameTimingBenchmark#panAndZoomCanvas`

5 iterations (kept modest -- see the test class's own doc comment: each iteration creates one real
"Untitled" project via the actual Gallery UI, which piles up within a run before being wiped by
`connectedBenchmarkAndroidTest`'s own uninstall-on-completion), `StartupMode.WARM`, metric
`FrameTimingMetric`. Each iteration performs 3 pan swipes (left+right) and 2 pinch zoom
open/close gestures on the real `DrawingCanvasView`, driven via genuine synthesized
`MotionEvent.TOOL_TYPE_FINGER` multi-touch gestures (`UiObject2.swipe`/`pinchOpen`/`pinchClose`),
the same finger-touch path `DrawingCanvasView.onTouchEvent()`'s `handleFingerDown`/
`handleFingerMove` already branch on for real pan/zoom/rotate handling.

| metric | P50 | P90 | P95 | P99 |
|---|---|---|---|---|
| `frameDurationCpuMs` | 6.2 | 8.1 | 9.4 | 13.2 |
| `frameOverrunMs` | -2.5 | 1.5 | 2.2 | 5.1 |

(`frameOverrunMs` is time over/under the device's actual per-frame deadline; negative P50 means the
median frame finished comfortably inside its budget.) `frameCount` per iteration: 567, 568, 596,
605, 606 (`coefficientOfVariation` = 0.033).

For reference against the informal number this replaces: the Fold5-branch-only debug overlay
(`DrawingCanvasView.drawDebugFrameOverlay`, `device/galaxy-z-fold5`, never merged to main) counts a
frame as "missed" past a 120Hz budget of 8.33ms. By that same bar, this run's P90 (8.1ms) is just
under it and P95/P99 (9.4ms / 13.2ms) are past it -- i.e. this canvas is comfortably keeping up with
90Hz+ during pan/zoom on this device, but does drop some frames past a strict 120Hz budget under
sustained gesture load. That's a real, specific, actionable number in a way "seemed smooth while I
was testing it" never was.

## Build type

Both tests run against `:app`'s `benchmark` build type (`app/build.gradle.kts`) -- built on
`release` (same signing, same manifest/packaging/`<profileable>` tag) but with
`isMinifyEnabled`/`isShrinkResources` explicitly forced back to `false`, i.e. Google's own
"nonMinifiedRelease" pattern. This was a deliberate, discovered-not-assumed choice: a straight
`initWith(release)` copy (minification ON, inherited as-is) makes AGP's
`:benchmark:checkTestedAppObfuscationBenchmark` task require the `:benchmark` test module to also
be minified to match, which sends R8 into shrinking `androidx.test`/`benchmark-macro`'s own large,
reflection-heavy dependency graph and failing on missing optional transitive classes
(`androidx.arch.core.*`, `com.google.errorprone.annotations.*`) that would need their own
hand-written keep/dontwarn rules -- entirely to shrink a test-harness APK whose own size/obfuscation
has zero bearing on the accuracy of anything actually being measured. `isDebuggable` stays `false`
(real ART JIT/AOT behavior, not the de-optimized debug path); the one real difference from a true
`release` build is the absence of R8 minification's effect on dex size/class-loading overhead --
called out here rather than hidden.

## Caveats (read before comparing future numbers against these)

- **No Baseline Profile.** This project has never generated one (`compilationMode: "run-from-apk"`
  in the raw JSON output confirms it). A checked-in Baseline Profile would very likely improve cold
  startup further; these numbers are the honest "as-is" baseline, not a best-case one.
- **`cpuLocked: false`.** The device's CPU clocks were not pinned during this run (that requires
  root or a rooted-adb "lockClocks" script Macrobenchmark itself documents as optional). Numbers can
  vary run-to-run with thermal/DVFS state more than they would on a clock-locked device. The
  `coefficientOfVariation` values above (0.07 and 0.03) give a sense of how noisy this particular
  run was.
- **Real device, single run.** These are one real run each, not an average of many runs across
  days/thermal states. Treat a future re-run as "regressed" only if it's meaningfully outside these
  ranges, not for single-digit-percent drift.
- **`PanZoomFrameTimingBenchmark` measures a brand-new blank canvas**, not a real multi-layer
  drawing -- see the test class's own doc comment for why (there's no non-invasive way to reach an
  existing real project from a cold Gallery launch through the actual UI). A heavily-layered project
  would likely show worse frame timing than this baseline; this number is a floor, not a ceiling.

## How to reproduce

```
export GRADLE_USER_HOME=/z/Dev/gradle-home
export ANDROID_SERIAL=<device-serial>
cd tablet-app
adb uninstall com.vellum.studio   # optional but recommended -- see "App state" above
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.vellum.studio.benchmark.StartupBenchmark
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.vellum.studio.benchmark.PanZoomFrameTimingBenchmark
```

Human-readable summaries land in
`benchmark/build/outputs/connected_android_test_additional_output/benchmark/connected/<device>/additionaltestoutput.benchmark.message_<Class>.<method>.txt`;
full machine-readable data (including per-run raw samples and device context) is the sibling
`com.vellum.studio.benchmark-benchmarkData.json` in that same directory. Perfetto traces per
iteration are alongside both, and can be opened directly at https://ui.perfetto.dev/ for a detailed
frame-by-frame breakdown.
