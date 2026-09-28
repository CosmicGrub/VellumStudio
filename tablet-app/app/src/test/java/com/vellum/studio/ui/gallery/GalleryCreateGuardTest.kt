package com.vellum.studio.ui.gallery

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import com.vellum.studio.VellumApp
import com.vellum.studio.model.ProjectRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression coverage for the Gallery fixes in the navigation double-tap guard item: a double-
 * tapped Create used to run createProject twice (an orphan "Untitled" project) and navigate
 * twice, and the last grid row's Options button sat underneath the New Canvas FAB.
 *
 * Runs against a real [ProjectRepository] (so "how many projects exist" is the real answer, not
 * a mock's), which is why [VellumApp] is the Robolectric application -- same reason as
 * ProjectRepositoryTest: persisting a project flattens the canvas, which reads the paper-texture
 * setting off VellumApp.instance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = VellumApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryCreateGuardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val repository = ProjectRepository(RuntimeEnvironment.getApplication())

    private fun setGallery(opened: AtomicInteger) {
        composeRule.setContent {
            GalleryScreen(
                repository = repository,
                onOpenProject = { opened.incrementAndGet() },
                onOpenConnect = {},
                onOpenSettings = {},
                onOpenColoringBook = {},
                onOpenAcademy = {},
            )
        }
    }

    /** Invokes a node's onClick semantics action [times] times inside one UI-thread task. */
    private fun SemanticsNodeInteraction.clickRepeatedlyInOneFrame(times: Int) {
        val action = fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        composeRule.runOnUiThread { repeat(times) { action() } }
    }

    @Test
    fun `double-tapping Create makes one project and opens one editor`() {
        val opened = AtomicInteger(0)
        setGallery(opened)
        composeRule.onNodeWithContentDescription("New Canvas").performClick()
        composeRule.waitForIdle()

        // Two taps delivered before compose can recompose (and so before the Create button could
        // be disabled for the second one): only the `creating` guard in the onCreate handler can
        // stop the second createProject here.
        composeRule.onNode(hasText("Create") and hasClickAction()).clickRepeatedlyInOneFrame(2)

        composeRule.waitUntil(timeoutMillis = 30_000) { opened.get() >= 1 }
        // Give a (buggy) second create/navigate time to land before asserting there was none.
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            Thread.sleep(50)
        }

        assertEquals("one editor navigation", 1, opened.get())
        assertEquals("one project on disk, no orphan 'Untitled'", 1, runBlocking { repository.listProjects().size })
    }

    @Test
    fun `Create shows a progress indicator and is disabled while the project is being made`() {
        // The Create label is replaced by the spinner for as long as `creating` is true, so its
        // disappearance proves the state flipped, and the button must not be re-enabled until the
        // dialog goes away.
        val opened = AtomicInteger(0)
        setGallery(opened)
        composeRule.onNodeWithContentDescription("New Canvas").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Create").assertIsEnabled()

        composeRule.onNodeWithText("Create").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) { opened.get() >= 1 }
        composeRule.waitForIdle()

        // After navigation the dialog is gone entirely (the state reset in `finally` must not
        // leave a stuck dialog behind either).
        composeRule.onAllNodesWithContentDescription("New Canvas") // FAB still there
        assertTrue(composeRule.onAllNodes(hasText("Create")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `last grid row's Options button clears the New Canvas FAB`() {
        runBlocking { repeat(3) { repository.createProject("P$it", 64, 64) } }
        setGallery(AtomicInteger(0))
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithContentDescription("Options").fetchSemanticsNodes().isNotEmpty()
        }
        // scrollToIndex only brings the last card to the top edge; the fling then drives the grid
        // to its true end, which is the position where the bottom padding matters.
        composeRule.onNode(hasScrollAction()).performScrollToIndex(2)
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        val fab = composeRule.onNodeWithContentDescription("New Canvas").getBoundsInRoot()
        val lastOptions = composeRule.onAllNodesWithContentDescription("Options").onLast().getBoundsInRoot()
        val overlaps = lastOptions.left < fab.right && lastOptions.right > fab.left &&
            lastOptions.top < fab.bottom && lastOptions.bottom > fab.top
        assertTrue("last card's Options $lastOptions is under the FAB $fab", !overlaps)
    }
}
