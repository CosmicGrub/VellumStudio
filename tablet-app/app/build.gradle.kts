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

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
