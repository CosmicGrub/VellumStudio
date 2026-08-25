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
**compilationMode reported by Macrobenchmark:** `run-from-apk` for the `DEFAULT`-mode run below (see
"Baseline Profile" section -- as of 2026-08-25 this project now has a real checked-in one, measured
head-to-head against this same `run-from-apk` state rather than assumed to help)

### Cold app startup -- `StartupBenchmark#coldStartup` / `#coldStartupWithBaselineProfile`

10 iterations each, `StartupMode.COLD`, metric `StartupTimingMetric`, same fresh-install app state,
same device, same session (`coldStartup` ran first, `coldStartupWithBaselineProfile` immediately
after, no reinstall between the two -- the Baseline Profile was already compiled into the installed
APK before either ran, so this is a true head-to-head of `CompilationMode.DEFAULT` vs.
`CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require)` against the identical
installed binary).

| | min | median | max | coefficientOfVariation |
|---|---|---|---|---|
| `DEFAULT` (`run-from-apk`, no profile forced) | 589.7 | 632.8 | 797.7 | 0.091 |
| `Partial(Require)` (Baseline Profile forced) | 571.9 | 609.6 | 651.7 | 0.043 |

Per-iteration raw values (ms):
- `DEFAULT`: 691.4, 632.3, 621.6, 633.3, 617.2, 611.9, 589.7, 657.7, 797.7, 667.3
- `Partial(Require)`: 595.4, 651.7, 619.1, 615.6, 619.4, 571.9, 577.8, 603.6, 647.8, 601.3

**Honest read of these numbers:** the median improvement is real but modest -- 632.8ms to 609.6ms,
about 3.7% faster. The more meaningful effect is on *consistency*, not the headline median: the
worst-case iteration dropped from 797.7ms to 651.7ms (an 18.3% better tail), and
`coefficientOfVariation` roughly halved (0.091 to 0.043) -- i.e. the Baseline Profile mainly made
cold starts more predictable run-to-run, not dramatically faster on average. That's consistent with
this being a genuinely small, shallow app today (one Activity, a handful of Compose screens, no
deep DI graph or huge startup class-loading chain) -- Baseline Profiles pay off most where there's a
lot of interpreted/JIT-compiled startup code for ART to skip re-interpreting each cold launch, and
this app doesn't have much of that yet. This is a real, measured win worth keeping (it's free once
wired in, and costs nothing at runtime), but it would be overstating things to call it a dramatic
startup-time fix -- treat it as "meaningfully more consistent, modestly faster," not "much faster."

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

## Baseline Profile

`app/src/main/baseline-prof.txt` (19,349 rules, ~962 of them `com/vellum/studio/*` classes/methods,
the rest framework/library classes touched during the same journey) is generated by
`benchmark/.../BaselineProfileGenerator.kt`'s `coldStartIntoExistingProject` test, which exercises
this project's real critical user journey: cold start into Gallery, then opening an
already-existing project into the Editor. See that test class's own header comment for the full
reasoning behind two choices verified live against current (2026-08-25) Android documentation
rather than assumed from memory:

- **No separate `:baselineprofile` module / no `androidx.baselineprofile` Gradle plugin.** That
  plugin's entire purpose -- auto-managing a "release but unminified" build type so profile
  method/class names still match source -- is something this project already built by hand last
  pass (`:app`'s `benchmark` build type, see "Build type" above, is exactly Google's own documented
  "nonMinifiedRelease" shape). Adding the plugin on top would make AGP try to manage its own
  version of a build type this project has a specific, documented reason to keep hand-rolled, for
  zero behavioral gain at this project's current single-app scale.
- **Manual placement of the resulting file, not plugin-automated merging.** Confirmed current and
  fully supported (developer.android.com/topic/performance/baselineprofiles/manually-create-measure,
  verified 2026-08-25): AGP compiles and embeds `src/main/baseline-prof.txt` into any
  non-debuggable build type's APK on its own, with no Gradle plugin involved -- confirmed directly
  here too, not just from the docs, by unzipping the built `benchmark` APK and finding
  `assets/dexopt/baseline.prof` (14,435 bytes, well under the 1.5MB limit) actually present after a
  plain `:app:assembleBenchmark`. `StartupBenchmark#coldStartupWithBaselineProfile`'s use of
  `CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require)` -- which throws
  rather than silently degrading if the profile isn't actually present and usable -- is a second,
  independent runtime confirmation: that test passed, so the profile isn't just sitting in source
  unused.

**Regenerating it:** re-run `BaselineProfileGenerator` (see "How to reproduce" below), then copy the
resulting `..."-baseline-prof.txt` file over `app/src/main/baseline-prof.txt` by hand. This manual
copy step is the one piece of the officially-documented plugin automation this project deliberately
does not have -- a reasonable trade given how rarely this project's critical user journeys change
shape, weighed against the plugin's added build complexity.

## Caveats (read before comparing future numbers against these)

- **`cpuLocked: false`.** The device's CPU clocks were not pinned during this run (that requires
  root or a rooted-adb "lockClocks" script Macrobenchmark itself documents as optional). Numbers can
  vary run-to-run with thermal/DVFS state more than they would on a clock-locked device. The
  `coefficientOfVariation` values above (0.091 DEFAULT / 0.043 with the Baseline Profile for
  startup, 0.033 for pan/zoom) give a sense of how noisy each of these particular runs was --
  notably, the Baseline Profile run wasn't just faster at the median, it was also the least noisy
  of the three, consistent with more of its class-loading work being resolved ahead of time instead
  of varying with whatever the interpreter/JIT happened to prioritize that run.
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

# Regenerate the Baseline Profile itself (only needed after a real critical-user-journey change):
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.vellum.studio.benchmark.BaselineProfileGenerator
# then copy benchmark/build/outputs/connected_android_test_additional_output/benchmark/connected/
# <device>/BaselineProfileGenerator_coldStartIntoExistingProject-baseline-prof.txt over
# app/src/main/baseline-prof.txt by hand, and rebuild (:app:assembleBenchmark) before re-measuring.

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
