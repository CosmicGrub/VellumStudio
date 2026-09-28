package com.vellum.studio.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression coverage for [safePop] and [navigateSingle], the guards behind the navigation
 * double-tap bugs (Gallery blanking on a fast double tap of Back; two editors stacked by a
 * double-tapped card).
 *
 * These drive a REAL NavHost with the same route strings and start destination as
 * [VellumNavGraph] (using [Routes]) but stub screens, because VellumNavGraph itself needs seven
 * repositories and full-size screens; what is under test is the NavController behaviour, which
 * does not depend on what the screens draw. A "double tap" is two calls made inside ONE
 * runOnUiThread block, i.e. before the NavHost has recomposed or any transition has advanced,
 * which is exactly the situation a second tap arriving a few milliseconds after the first
 * creates. (Two separate performClick calls would let compose settle in between and so would
 * test a slow double tap, which was never the bug.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavGuardsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var nav: NavHostController

    private fun setUpNavHost() {
        composeRule.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = Routes.GALLERY) {
                composable(Routes.GALLERY) { Text("gallery", Modifier.testTag("gallery")) }
                composable(Routes.CONNECT) { Text("connect", Modifier.testTag("connect")) }
                composable(Routes.SETTINGS) { Text("settings", Modifier.testTag("settings")) }
                composable(
                    route = Routes.EDITOR,
                    arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
                ) { Text("editor", Modifier.testTag("editor")) }
            }
        }
        composeRule.waitForIdle()
    }

    /** Back-stack size excluding the NavGraph entry itself. */
    private fun depth() = nav.currentBackStack.value.count { it.destination.route != null && it.destination !is androidx.navigation.NavGraph }

    @Test
    fun `double-tapping Back from a screen one deep leaves Gallery showing`() {
        setUpNavHost()
        composeRule.runOnUiThread { nav.navigateSingle(Routes.CONNECT) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("connect").assertIsDisplayed()

        var popped = emptyList<Boolean>()
        composeRule.runOnUiThread { popped = listOf(nav.safePop(), nav.safePop()) }
        composeRule.waitForIdle()

        assertEquals("only the first tap may pop", listOf(true, false), popped)
        composeRule.onNodeWithTag("gallery").assertIsDisplayed()
        assertEquals(Routes.GALLERY, nav.currentDestination?.route)
    }

    @Test
    fun `an unguarded double popBackStack blanks the NavHost, which is the bug safePop closes`() {
        // Control for the test above: same setup, plain popBackStack twice. If this ever stops
        // leaving an empty back stack (a Navigation upgrade that fixes the race itself), the
        // guard is no longer load-bearing and this test is the one to revisit.
        setUpNavHost()
        composeRule.runOnUiThread { nav.navigate(Routes.CONNECT) }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            nav.popBackStack()
            nav.popBackStack()
        }
        composeRule.waitForIdle()

        assertNull("Gallery was popped: nothing left to show", nav.currentDestination)
    }

    @Test
    fun `double-tapping Back on a deeper stack pops exactly one level`() {
        setUpNavHost()
        composeRule.runOnUiThread { nav.navigateSingle(Routes.CONNECT) }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { nav.navigateSingle(Routes.SETTINGS) }
        composeRule.waitForIdle()
        assertEquals(3, depth())

        composeRule.runOnUiThread {
            nav.safePop()
            nav.safePop()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("connect").assertIsDisplayed()
        assertEquals("one Back tap's worth of popping, not two", 2, depth())
    }

    @Test
    fun `safePop on the start destination does nothing`() {
        setUpNavHost()

        var popped = true
        composeRule.runOnUiThread { popped = nav.safePop() }
        composeRule.waitForIdle()

        assertFalse(popped)
        composeRule.onNodeWithTag("gallery").assertIsDisplayed()
    }

    @Test
    fun `Back still works normally once the transition has settled`() {
        // The guard must not turn into "Back never works": a deliberate second Back after the
        // first pop has finished animating has to go through.
        setUpNavHost()
        composeRule.runOnUiThread { nav.navigateSingle(Routes.CONNECT) }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { nav.navigateSingle(Routes.SETTINGS) }
        composeRule.waitForIdle()

        composeRule.runOnUiThread { assertTrue(nav.safePop()) }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { assertTrue(nav.safePop()) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("gallery").assertIsDisplayed()
        assertEquals(1, depth())
    }

    @Test
    fun `double-tapping a project card opens one editor`() {
        setUpNavHost()

        composeRule.runOnUiThread {
            nav.navigateSingle(Routes.editor("p1"))
            nav.navigateSingle(Routes.editor("p1"))
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("editor").assertIsDisplayed()
        assertEquals("Gallery + ONE editor", 2, depth())

        composeRule.runOnUiThread { nav.safePop() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("gallery").assertIsDisplayed()
    }

    @Test
    fun `plain navigate stacks two editors on a double tap, which navigateSingle prevents`() {
        // Control for the test above.
        setUpNavHost()

        composeRule.runOnUiThread {
            nav.navigate(Routes.editor("p1"))
            nav.navigate(Routes.editor("p1"))
        }
        composeRule.waitForIdle()

        assertEquals("Gallery + TWO editors", 3, depth())
    }
}
