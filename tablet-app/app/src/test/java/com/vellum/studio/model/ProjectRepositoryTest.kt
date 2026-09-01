package com.vellum.studio.model

import android.graphics.Canvas
import com.vellum.studio.VellumApp
import com.vellum.studio.art.ColoringTemplate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Regression coverage for the duplicate-Gallery-project bug found during v0.2.0 release testing:
 * tapping the SAME "My Photos" card a second time used to call
 * [ProjectRepository.createFromTemplate] unconditionally, creating a second, separate project
 * every time -- instead of reopening the one project already made from that photo. A bundled
 * template (masterworks, kids, geometric, ...) is meant to keep creating a fresh project on every
 * tap, like grabbing a new physical copy of the page; only a user-photo-backed template should
 * behave like reopening a document.
 *
 * See [ProjectMeta.sourceTemplateId] (the additive field that makes "was a project already made
 * from this template" answerable at all) and [ProjectRepository.openOrCreateFromTemplate] (the
 * dedup logic itself) for the fix these tests lock in. Both are exercised here exactly as
 * [com.vellum.studio.ui.coloringbook.ColoringBookScreen]'s own tap handler (`startProject`) calls
 * them, without needing Compose.
 *
 * Runs under Robolectric (not a plain JVM test) because [ProjectRepository] needs a real
 * [android.content.Context] (`getExternalFilesDir`) and [ProjectRepository.createFromTemplate]
 * builds real Bitmap/Canvas layers -- same justification as UndoManagerTest/RegionAnalyzerTest.
 *
 * Uses [VellumApp] itself as the Robolectric application (not a plain [android.app.Application]
 * the way most other tests in this suite do): [ProjectRepository.persist]'s thumbnail step calls
 * [com.vellum.studio.canvas.CanvasEngine.flatten], which reads the paper-texture setting off
 * [VellumApp.instance] -- only set by [VellumApp.onCreate], which Robolectric only runs when the
 * configured application class actually IS [VellumApp].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = VellumApp::class)
class ProjectRepositoryTest {

    private val repository = ProjectRepository(RuntimeEnvironment.getApplication())

    /**
     * A no-op [ColoringTemplate.draw] is enough here -- these tests are about project bookkeeping
     * (how many projects exist, which id a tap resolves to), never about rendered pixels.
     * [userPhotoBacked] toggles exactly the one signal [ProjectRepository.openOrCreateFromTemplate]
     * actually branches on: [ColoringTemplate.referenceFilePath] non-null, matching how
     * [UserPhotoTemplateRepository.toColoringTemplate] builds a real "My Photos" entry.
     */
    private fun template(id: String, userPhotoBacked: Boolean) = ColoringTemplate(
        id = id,
        name = "Test Template $id",
        category = if (userPhotoBacked) "My Photos" else "Masterworks",
        draw = { _: Canvas, _: Int -> },
        referenceFilePath = if (userPhotoBacked) "/fake/path/$id.jpg" else null,
    )

    @Test
    fun `tapping the same user-photo template twice reopens one project instead of creating two`() = runBlocking {
        val photoTemplate = template(id = "photo_abc", userPhotoBacked = true)

        val firstTapId = repository.openOrCreateFromTemplate(photoTemplate)
        val secondTapId = repository.openOrCreateFromTemplate(photoTemplate)

        assertEquals("second tap on the same My Photos card should resolve to the SAME project", firstTapId, secondTapId)
        assertEquals("only one project should exist after two taps on the same photo template", 1, repository.listProjects().size)
    }

    @Test
    fun `tapping the same bundled template twice still creates two separate projects`() = runBlocking {
        val bundledTemplate = template(id = "masterwork_xyz", userPhotoBacked = false)

        val firstTapId = repository.openOrCreateFromTemplate(bundledTemplate)
        val secondTapId = repository.openOrCreateFromTemplate(bundledTemplate)

        assertNotEquals("a bundled template must keep creating a fresh project on every tap", firstTapId, secondTapId)
        assertEquals("two taps on a bundled template should leave two separate projects", 2, repository.listProjects().size)
    }

    @Test
    fun `createFromTemplate stamps sourceTemplateId for both bundled and user-photo templates`() = runBlocking {
        val bundledTemplate = template(id = "masterwork_stamp", userPhotoBacked = false)
        val photoTemplate = template(id = "photo_stamp", userPhotoBacked = true)

        val (bundledMeta, bundledEngine) = repository.createFromTemplate(bundledTemplate)
        val (photoMeta, photoEngine) = repository.createFromTemplate(photoTemplate)
        bundledEngine.layers.forEach { it.bitmap.recycle() }
        photoEngine.layers.forEach { it.bitmap.recycle() }

        assertEquals(bundledTemplate.id, bundledMeta.sourceTemplateId)
        assertEquals(photoTemplate.id, photoMeta.sourceTemplateId)
    }
}
