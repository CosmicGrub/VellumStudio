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
        versionCode = 1
        versionName = "0.1.0"

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
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Foldable-aware layout (hinge/fold-state signal for the Z Fold5 tabletop posture).
    implementation("androidx.window:window:1.5.1")

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
}
