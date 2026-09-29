package com.vellum.studio.util

import com.vellum.studio.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Properties

/**
 * Release hygiene as regression tests: what the build stamps into the app, and the wiring in the
 * build files that keeps releases traceable and fail-closed.
 *
 * The Gradle-level fail-closed behavior itself (assembleRelease refuses without a keystore, and
 * -PallowDebugSignedRelease=true opts out) cannot be run from inside a unit test -- it is a property
 * of the task graph -- so it is verified by running Gradle (recorded in docs/RELEASING.md) and pinned
 * here only at the level a unit test can honestly reach: the build script still contains the guard,
 * still reads the single version file, and the .example the guard's error points at still exists with
 * the keys the script reads.
 *
 * Unit tests run with the module directory (tablet-app/app) as working directory, so the build files
 * are one level up / alongside.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class BuildIdentityTest {

    private val moduleDir = File(System.getProperty("user.dir")!!)
    private val gradleRoot = moduleDir.parentFile!!

    private fun buildScript() = File(moduleDir, "build.gradle.kts").readText()

    @Test
    fun bannerAndAboutCarryTheGitShaAndBranchStampedIntoBuildConfig() {
        // GIT_SHA is a short hex commit, or the documented fallback when git was unavailable at build time.
        assertTrue("GIT_SHA=${BuildConfig.GIT_SHA}", Regex("[0-9a-f]{7,40}|unknown").matches(BuildConfig.GIT_SHA))
        assertTrue("BRANCH must be non-empty", BuildConfig.BRANCH.isNotEmpty())

        val banner = DiagnosticLog.deviceBanner()
        assertTrue(banner, BuildConfig.GIT_SHA in banner)
        assertTrue(banner, BuildConfig.BRANCH in banner)
        assertTrue(banner, BuildConfig.VERSION_NAME in banner)
        // Settings > About renders exactly this string, so the two cannot drift apart.
        assertEquals("${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA} on ${BuildConfig.BRANCH})", DiagnosticLog.buildIdentity())
    }

    @Test
    fun appVersionComesFromTheSingleVersionPropertiesFile() {
        val props = Properties().apply { File(gradleRoot, "version.properties").inputStream().use { load(it) } }
        assertEquals(props.getProperty("versionName").trim(), BuildConfig.VERSION_NAME)
        assertTrue(props.getProperty("versionCode").trim().toInt() > 0)

        // ...and the build script must not quietly grow its own hard-coded copy again.
        val script = buildScript()
        assertTrue("build script must read version.properties", "version.properties" in script)
        assertTrue("versionCode must come from version.properties", Regex("""versionCode\s*=\s*appVersionCode""").containsMatchIn(script))
        assertTrue("versionName must come from version.properties", Regex("""versionName\s*=\s*appVersionName""").containsMatchIn(script))
        assertTrue("hard-coded versionCode is back", !Regex("""versionCode\s*=\s*\d""").containsMatchIn(script))
    }

    @Test
    fun releaseFailsClosedAndTheErrorPointsAtAnExistingExample() {
        val script = buildScript()
        assertTrue("task-graph guard missing", "gradle.taskGraph.whenReady" in script)
        assertTrue("opt-out property name is a contract with the verify scripts", "allowDebugSignedRelease" in script)
        assertTrue("error must name the example file", "keystore.properties.example" in script)
        assertTrue("guard must cover assembleRelease", "\"assembleRelease\"" in script)
        assertTrue("guard must cover bundleRelease", "\"bundleRelease\"" in script)

        val example = File(gradleRoot, "keystore.properties.example")
        assertTrue("keystore.properties.example must exist", example.isFile)
        val keys = example.readLines().filter { !it.trimStart().startsWith("#") && '=' in it }.map { it.substringBefore('=').trim() }
        assertEquals(setOf("storeFile", "storePassword", "keyAlias", "keyPassword"), keys.toSet())
        // The template must never carry something that looks like a real secret.
        assertTrue(example.readText().contains("CHANGE_ME"))
    }

    @Test
    fun releaseBuildKeepsLineNumbersForRetrace() {
        val rules = File(moduleDir, "proguard-rules.pro").readText()
        assertTrue("-keepattributes SourceFile,LineNumberTable" in rules)
        assertTrue("-renamesourcefileattribute SourceFile" in rules)
    }
}
