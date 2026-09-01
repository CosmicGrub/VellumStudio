plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.vellum.studio"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vellum.studio"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.1"

        // First real use of app/src/androidTest -- see PhotoConverterGoldenMasterInstrumentedTest,
        // the one piece of the PhotoConverter golden-master fixture that needs a live OpenCV
        // native call chain and so can only run on a real device/emulator, not testDebugUnitTest.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        // Both real target devices (Tab S9 FE, Z Fold5) are arm64 -- restrict the OpenCV AAR's
        // bundled native libs (armeabi-v7a/arm64-v8a/x86/x86_64, ~150MB unpacked) to just the one
        // ABI we actually ship to during this development phase, to keep build time and APK size
        // sane. KNOWN LIMITATION: a real release build (Play Store / arbitrary hardware) would
        // need to drop this filter and either ship all ABIs or move to an Android App Bundle so
        // Play delivers only the matching ABI per device.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            // Real code shrinking/obfuscation for release builds. Verified end-to-end against a
            // real :app:assembleRelease install + manual smoke pass on R52X101MB6W (Tab S9 FE) --
            // see proguard-rules.pro for the specific keep rules this required (kotlinx.serialization
            // reflection, OpenCV JNI, ML Kit pose detection).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // KNOWN GAP, flagged not fixed here: this project has no production signing config at
            // all yet -- an actual Play Store release would need a real keystore before this build
            // type could ship. Signing with the pre-existing debug keystore is a deliberate,
            // temporary stand-in so a real minified/shrunk *release build type* can actually be
            // built and adb-installed on a physical device (an unsigned release APK can't be
            // installed at all) -- which is exactly what this pass needs in order to prove
            // minification doesn't break anything at runtime. Swap this for a real release
            // signingConfig before ever distributing a build made with this config.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isDebuggable = true
        }
        // For Macrobenchmark (see the ":benchmark" module) -- built on release (same signing,
        // same manifest/resources/packaging) but with minification deliberately turned back OFF,
        // i.e. Google's documented "nonMinifiedRelease" pattern (the exact name/output-path
        // convention their own Baseline Profile tooling uses for this same build-type shape) rather
        // than a straight initWith(release) copy. This isn't a shortcut -- it's the correct choice,
        // discovered by actually hitting the alternative's real failure: a plain initWith(release)
        // copy (release's isMinifyEnabled=true carried through as-is) makes AGP's own
        // :benchmark:checkTestedAppObfuscationBenchmark task demand the ":benchmark" test module
        // *also* be minified to match, which in turn sends R8 into shrinking androidx.test's/
        // benchmark-macro's own large, reflection-heavy dependency graph and failing on missing
        // optional transitive classes (androidx.arch.core, com.google.errorprone annotations, etc.)
        // that would need their own hand-written keep/dontwarn rules to fix -- entirely to shrink a
        // test harness APK whose own size/obfuscation has zero bearing on the accuracy of any
        // metric actually being measured (only the TARGET app's realism matters for that). Losing
        // R8 minification here does mean this build type doesn't reflect release's exact dex
        // layout/size, but isDebuggable stays false (still real ART JIT/AOT behavior, still
        // profileable, still not the de-optimized debug path) -- the one thing minification would
        // have added on top is dex-verification/class-loading overhead from a larger unshrunk dex,
        // which is a real but secondary difference call out explicitly in benchmark-baseline.md
        // rather than hidden.
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // CI (see .github/workflows/android-ci.yml) runs `:app:lint` and fails the build on any
        // lint error. Wiring that up for the first time surfaced 6 pre-existing errors (the same
        // ProduceStateDoesNotAssignValue Compose lint warning repeated across
        // ColoringBookScreen/GalleryScreen/LayersPanel/LessonScreen's async-bitmap-loading code, in
        // 5 files) plus 41 warnings that predate this pass and are unrelated to it -- fixing that
        // Compose idiom across 5 unrelated screens is its own real piece of work, not something to
        // fold into a CI/release-health pass. A baseline is the standard, honest way to reconcile
        // "lint must gate CI" with "don't silently retroactively block on debt this pass didn't
        // create": everything already in lint-baseline.xml (generated via `gradlew
        // :app:updateLintBaseline` against the pre-existing code) is grandfathered in and reported
        // but non-fatal, while any *new* issue -- including a regression in one of these same 5
        // files -- still fails the build. Deliberately not `abortOnError = false`, which would
        // silence lint's exit code entirely and defeat the point of gating CI on it at all.
        baseline = file("lint-baseline.xml")
    }

    testOptions {
        unitTests {
            // RegionAnalyzer/ShapeAssist/UndoManager's tests construct real android.graphics
            // Bitmap/Canvas/PointF/RectF objects via Robolectric rather than the plain
            // "mockable android.jar" AGP otherwise substitutes (which stubs every method to throw
            // or return a default) -- isReturnDefaultValues is deliberately left at its default
            // (false) so any accidental non-Robolectric call into a real android.* method in a
            // *plain* JVM test still fails loudly instead of silently returning 0/false/null.
            isIncludeAndroidResources = false
        }
    }

    // The PhotoConverter golden-master fixture photos (see PhotoConverterGoldenMasterFixtureTest /
    // PhotoConverterGoldenMasterInstrumentedTest) are checked in exactly ONCE, under
    // src/test/resources/photoconverter-golden/, and shared with androidTest here rather than
    // duplicated -- androidTest needs them as real assets (to open via a real Context.assets +
    // BitmapFactory on-device), while the JVM test module reads the identical bytes as a plain
    // classpath resource. Same source photos tools/masterart_pipeline/source/ already has
    // checked in for the Python pipeline; reused here rather than re-downloaded.
    sourceSets {
        getByName("androidTest") {
            assets.srcDirs("src/test/resources/photoconverter-golden")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // 16 KB native-library page-size alignment (investigated 2026-08-23, per Android's own
        // developer.android.com/guide/practices/page-sizes guidance): AGP zip-aligns bundled .so
        // entries to 16 KB automatically once on AGP 8.5.1+ (this project is on 8.7.2), and that's
        // confirmed actually happening -- `zipalign -c -v -P 16 4` against the built debug APK
        // reports every lib/arm64-v8a/*.so entry OK with zero extra config here. That's zip-level
        // alignment only, though; it can't retroactively fix a prebuilt .so's own ELF LOAD-segment
        // alignment (its PT_LOAD p_align, baked in at link time), which is the separate thing a
        // "not 16 KB page aligned" runtime compatibility warning is actually about. Direct ELF
        // program-header inspection of what's actually bundled today found:
        //   - libopencv_java4.so (org.opencv:opencv:4.14.0, below) -- ALREADY 16384-byte aligned.
        //   - libandroidx.graphics.path.so (androidx.graphics:graphics-path, pulled in transitively
        //     at 1.0.1) -- ALREADY 16384-byte aligned.
        //   - libxeno_native.so, bundled by com.google.mlkit:pose-detection-accurate:17.0.0's own
        //     native runtime (below) -- still 4096-byte aligned, i.e. the one real offender left.
        // So the two libs a warning dialog would historically have been attributed to are already
        // fine as currently pinned; nothing here needed a packaging change. The ML Kit one is a
        // closed-source prebuilt (re-linking it from source is exactly as out-of-scope as re-linking
        // OpenCV's own .so would be), and Google's Maven index has no newer STABLE release of that
        // artifact to try -- only 17.0.1-beta*/18.0.0-beta* prereleases exist, unverified for this
        // and not something to take on for a "cost-free AI" feature without a stable line to pin to.
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Lightweight embedded HTTP server for the LAN sync bridge to the PC companion.
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // System print dialog (Wi-Fi printers, "save as PDF", etc.) for exporting art and coloring pages.
    implementation("androidx.print:print:1.0.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // On-device computer vision for the photo-to-activity conversion engine (canvas/PhotoConverter.kt):
    // tone-quantize + closed-contour line-art extraction, ported from tools/masterart_pipeline's
    // proven cv2 technique. Published directly to Maven Central as a plain AAR (with a bundled
    // native lib, loaded via OpenCVLoader.initLocal()) since OpenCV 4.9.0 -- no separate OpenCV
    // Manager APK or native-loader dance needed. Verified the coordinate + version list directly
    // against Maven Central (repo1.maven.org/maven2/org/opencv/opencv/maven-metadata.xml) on
    // 2026-08-18: 4.9.0 through 4.14.0, then 5.0.0/5.0.0.1. Pinned to 4.14.0 (latest 4.x) rather
    // than the newer 5.0.x line deliberately -- 5.0 reorganized the Java API surface (e.g.
    // Imgproc.arcLength moved to a new org.opencv.geometry.Geometry class), which broke this
    // file's contour-tracing calls against the standard, widely-documented 4.x Java API this port
    // was written against. 4.14.0 is still current, still a plain zero-cost on-device AAR, and
    // avoids that migration risk for this expansion. Runs fully on-device, zero network calls,
    // zero API cost -- satisfies the project's "cost-free AI" constraint.
    implementation("org.opencv:opencv:4.14.0")

    // On-device pose detection for the "Pose Reference Overlay" figure-drawing teaching aid
    // (canvas/PoseOverlay.kt): draws a non-destructive skeleton overlay on top of an imported
    // reference-photo layer, purely in DrawingCanvasView's onDraw() -- never touches layer pixels.
    // This is the BUNDLED artifact (as opposed to the Play-Services-unbundled
    // com.google.android.gms:play-services-mlkit-pose-detection, which downloads its model on
    // first use over the network) -- the model ships inside this AAR itself, so detection runs
    // fully offline with zero network calls and zero API cost, satisfying the project's
    // "cost-free AI" constraint outright with no separate download step to reason about. The
    // "-accurate" variant (vs. the streaming-tuned base model) is the right choice since this
    // always runs once against a single still reference image, never a live camera feed. Verified
    // the coordinate + version list directly against the Google Maven group index
    // (dl.google.com/dl/android/maven2/com/google/mlkit/pose-detection-accurate/maven-metadata.xml)
    // on 2026-08-18: latest STABLE release is 17.0.0 (18.0.0-beta5 also exists but is a
    // prerelease) -- pinned to the stable line deliberately, same reasoning as the OpenCV pin
    // above.
    implementation("com.google.mlkit:pose-detection-accurate:17.0.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Required by the "benchmark" build type / the ":benchmark" Macrobenchmark module: writes the
    // ahead-of-time compiler profile installed alongside the app so CompilationMode.DEFAULT has
    // something real to reflect (and is a prerequisite for ever shipping a checked-in Baseline
    // Profile later, though this project doesn't have one yet). Verified current stable release
    // directly against Google's Maven group index (dl.google.com/dl/android/maven2/androidx/
    // profileinstaller/profileinstaller/maven-metadata.xml) on 2026-08-24: latest stable is 1.4.1.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // JVM unit test module (app/src/test) -- see that directory for what's covered and why.
    testImplementation("junit:junit:4.13.2")
    // RegionAnalyzer takes a real android.graphics.Bitmap (needs actual pixel/Canvas behavior,
    // not just a non-throwing stub), and ShapeAssist/UndoManager similarly build on real
    // PointF/RectF/Bitmap/Canvas objects -- none of which the plain "mockable android.jar" AGP
    // otherwise substitutes for local unit tests can provide. Robolectric runs real android-all
    // framework bytecode (with pixel-accurate native-graphics-backed Bitmap/Canvas since 4.9+) on
    // the JVM instead, which is the standard, well-supported way to unit-test this kind of
    // Android-shaped-but-actually-pure logic without a device/emulator. Pinned to 4.16.1 (latest
    // stable, non-beta) specifically because it's the first stable line with a published
    // android-all-instrumented artifact for API 36 (this project's compileSdk/targetSdk).
    testImplementation("org.robolectric:robolectric:4.16.1")

    // app/src/androidTest -- currently just PhotoConverterGoldenMasterInstrumentedTest, the one
    // half of the PhotoConverter golden-master fixture that needs a live OpenCV native call chain
    // (only available on a real device/emulator, never in a JVM/Robolectric process -- see that
    // test's own doc). Same versions as the :benchmark module's own androidx.test deps, verified
    // there against Google's Maven group index on 2026-08-24 -- reused here rather than
    // re-verified, since they're the same artifacts pinned for the same reason.
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
