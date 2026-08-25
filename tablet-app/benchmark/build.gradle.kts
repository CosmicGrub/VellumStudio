// Google's standard Macrobenchmark module shape: a com.android.test module (Gradle's own doc --
// "tells Gradle not to include it in your application, so it can only contain testing code") that
// instruments the real :app module from a separate process via UiAutomator, rather than an
// ordinary androidTest source set living inside :app itself. Verified against the current
// (2026-08-24) developer.android.com "Write a Macrobenchmark" guide and the companion
// android-macrobenchmark-inspect codelab rather than trusted from memory, per this phase's
// instructions -- Macrobenchmark's setup APIs have genuinely moved across AGP/library versions.
plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vellum.studio.benchmark"
    compileSdk = 36

    defaultConfig {
        // Matches :app's own floor -- Macrobenchmark itself only requires API 23+, but there's no
        // reason for this module to claim a lower floor than the app it exclusively measures.
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // This module's OWN build type -- distinct from :app's "benchmark" build type (app/build.gradle.kts)
    // even though both are named "benchmark". AGP pairs a com.android.test module against its
    // targetProjectPath by matching build TYPE NAMES between the two, so the name has to match --
    // but the two serve opposite purposes and deliberately have opposite isDebuggable values:
    // this module's own instrumentation-test APK must stay debuggable (a standard Android
    // instrumentation-test requirement), while :app's "benchmark" build type must NOT be debuggable
    // (a debuggable target defeats the entire point -- ART disables real JIT/AOT optimization for
    // debuggable apps, which would make every measured number meaningless).
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            // Deliberately NOT minified. See app/build.gradle.kts's "benchmark" build type comment
            // for the full reasoning (the "nonMinifiedRelease" pattern) -- keeping :app's benchmark
            // build type unminified means AGP's :benchmark:checkTestedAppObfuscationBenchmark check
            // (which otherwise demands this module match a minified target) never triggers, so this
            // module doesn't need to R8-shrink androidx.test/benchmark-macro's own large dependency
            // graph just to satisfy that check.
        }
    }

    // Tells AGP which app module this test module measures, and (paired with the matching
    // "benchmark" build-type name on both sides above) which of its variants to instrument.
    targetProjectPath = ":app"

    // REQUIRED, confirmed by actually hitting the failure this fixes rather than assumed: without
    // this, AGP generates this test module's <instrumentation android:targetPackage> pointing at
    // ":app" (com.vellum.studio) while the manifest's own package stays this module's namespace
    // (com.vellum.studio.benchmark) -- a mismatch Macrobenchmark's own runtime preflight check
    // rejects at test-start as "NOT-SELF-INSTRUMENTING" ("Macrobenchmark instrumentation target in
    // manifest ... does not match macrobenchmark package ..."), because loading the test into the
    // SAME process as the app it's supposed to kill/recompile/relaunch would break exactly those
    // operations. This property is what makes AGP instrument the target app from this module's own
    // separate process instead, which is what "self-instrumenting" (confusingly named -- it's about
    // the test running itself as its own instrumented target process, not about targeting the same
    // package) actually refers to. Deliberately NOT using the alternative
    // (testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "NOT-SELF-INSTRUMENTING")
    // since that only silences the check while leaving the real underlying process-separation
    // problem in place -- Macrobenchmark's own docs are explicit that every suppressed error
    // compromises measurement accuracy.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Latest stable releases, verified directly against Google's Maven group index
    // (dl.google.com/dl/android/maven2/androidx/.../maven-metadata.xml) on 2026-08-24 rather than
    // assumed from memory -- benchmark-macro-junit4 1.5.x only has alpha/beta/rc releases so far,
    // making 1.4.1 the current stable line; uiautomator's is 2.4.0.
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test:runner:1.7.0")
    implementation("androidx.test.uiautomator:uiautomator:2.4.0")
}
