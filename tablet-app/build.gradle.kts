plugins {
    id("com.android.application") version "8.7.2" apply false
    // Macrobenchmark's ":benchmark" module (see benchmark/build.gradle.kts) -- a com.android.test
    // module, Google's standard vehicle for instrumented Macrobenchmark tests, sharing AGP's
    // version with com.android.application above since they're the same AGP artifact family.
    id("com.android.test") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
