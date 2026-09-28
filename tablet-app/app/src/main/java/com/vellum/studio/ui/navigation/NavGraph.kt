package com.vellum.studio.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vellum.studio.academy.AcademyProgressRepository
import com.vellum.studio.model.CustomBrushRepository
import com.vellum.studio.model.PaletteRepository
import com.vellum.studio.model.ProjectRepository
import com.vellum.studio.model.SettingsRepository
import com.vellum.studio.model.UserPhotoTemplateRepository
import com.vellum.studio.ui.academy.AcademyScreen
import com.vellum.studio.ui.academy.CourseDetailScreen
import com.vellum.studio.ui.academy.LessonScreen
import com.vellum.studio.ui.coloringbook.ColoringBookScreen
import com.vellum.studio.ui.connect.ConnectScreen
import com.vellum.studio.ui.editor.EditorScreen
import com.vellum.studio.ui.editor.QuickSketchScreen
import com.vellum.studio.ui.gallery.GalleryScreen
import com.vellum.studio.ui.settings.SettingsScreen

object Routes {
    const val GALLERY = "gallery"
    const val EDITOR = "editor/{projectId}"
    const val QUICK_SKETCH = "quick_sketch/{projectId}"
    const val CONNECT = "connect"
    const val SETTINGS = "settings"
    const val COLORING_BOOK = "coloring_book"
    const val ACADEMY = "academy"
    const val COURSE_DETAIL = "academy/course/{courseId}"
    const val LESSON = "academy/course/{courseId}/lesson/{lessonId}"
    fun editor(projectId: String) = "editor/$projectId"
    fun quickSketch(projectId: String) = "quick_sketch/$projectId"
    fun courseDetail(courseId: String) = "academy/course/$courseId"
    fun lesson(courseId: String, lessonId: String) = "academy/course/$courseId/lesson/$lessonId"
}

/**
 * Pops the current screen, but only when doing so cannot leave the NavHost empty or race a pop
 * that is already in flight. Every onBack lambda used to call popBackStack() bare: a fast double
 * tap on Back pops the screen and then, on the second tap, the start destination (Gallery) too,
 * which is the well-known Navigation-Compose blank-screen failure -- an empty NavHost with
 * nothing to go back to. Two conditions close it:
 *  - `previousBackStackEntry != null` means there is something underneath to land on, so the
 *    start destination itself can never be popped from a back button.
 *  - the current entry must be RESUMED. While a pop (or push) transition is animating, the entry
 *    the user is looking at is at most STARTED, so the second tap of a double tap is dropped
 *    instead of popping a second level. A pop that has already been applied but is still
 *    animating out leaves the destination underneath as `currentBackStackEntry`, which is what
 *    makes this check sufficient on a stack deeper than two.
 * The cost is that a Back tap during the ~300ms enter/exit transition is ignored, which is the
 * standard trade-off and indistinguishable from a missed tap.
 * Returns whether a pop was performed.
 */
fun NavController.safePop(): Boolean {
    val current = currentBackStackEntry ?: return false
    if (previousBackStackEntry == null) return false
    if (current.lifecycle.currentState != Lifecycle.State.RESUMED) return false
    return popBackStack()
}

/**
 * navigate() with launchSingleTop, so two quick taps on a card / toolbar icon push ONE entry.
 * Without it the second tap stacks a second copy of the destination, and for the editor that
 * means two full-canvas CanvasEngines (scratch + layer bitmaps, tens of MB each at 4096 squared)
 * alive at once. Extra [builder] options (e.g. popUpTo) are applied on top.
 */
fun NavController.navigateSingle(route: String, builder: NavOptionsBuilder.() -> Unit = {}) {
    navigate(route) {
        launchSingleTop = true
        builder()
    }
}

@Composable
fun VellumNavGraph(
    repository: ProjectRepository,
    paletteRepository: PaletteRepository,
    academyProgressRepository: AcademyProgressRepository,
    settingsRepository: SettingsRepository,
    customBrushRepository: CustomBrushRepository,
    userPhotoTemplateRepository: UserPhotoTemplateRepository,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Routes.GALLERY) {
        composable(Routes.GALLERY) {
            GalleryScreen(
                repository = repository,
                onOpenProject = { id -> navController.navigateSingle(Routes.editor(id)) },
                onOpenQuickSketch = { id -> navController.navigateSingle(Routes.quickSketch(id)) },
                onOpenConnect = { navController.navigateSingle(Routes.CONNECT) },
                onOpenSettings = { navController.navigateSingle(Routes.SETTINGS) },
                onOpenColoringBook = { navController.navigateSingle(Routes.COLORING_BOOK) },
                onOpenAcademy = { navController.navigateSingle(Routes.ACADEMY) },
            )
        }
        composable(
            route = Routes.EDITOR,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
            EditorScreen(
                repository = repository,
                paletteRepository = paletteRepository,
                settingsRepository = settingsRepository,
                customBrushRepository = customBrushRepository,
                projectId = projectId,
                onBack = { navController.safePop() },
            )
        }
        composable(
            route = Routes.QUICK_SKETCH,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
            QuickSketchScreen(
                repository = repository,
                paletteRepository = paletteRepository,
                settingsRepository = settingsRepository,
                projectId = projectId,
                onBack = { navController.safePop() },
            )
        }
        composable(Routes.CONNECT) {
            ConnectScreen(repository = repository, onBack = { navController.safePop() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(settingsRepository = settingsRepository, onBack = { navController.safePop() })
        }
        composable(Routes.COLORING_BOOK) {
            ColoringBookScreen(
                repository = repository,
                userPhotoTemplateRepository = userPhotoTemplateRepository,
                onBack = { navController.safePop() },
                onOpenProject = { id -> navController.navigateSingle(Routes.editor(id)) { popUpTo(Routes.GALLERY) } },
            )
        }
        composable(Routes.ACADEMY) {
            AcademyScreen(
                progressRepository = academyProgressRepository,
                onBack = { navController.safePop() },
                onOpenCourse = { courseId -> navController.navigateSingle(Routes.courseDetail(courseId)) },
            )
        }
        composable(
            route = Routes.COURSE_DETAIL,
            arguments = listOf(navArgument("courseId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val courseId = backStackEntry.arguments?.getString("courseId").orEmpty()
            CourseDetailScreen(
                courseId = courseId,
                progressRepository = academyProgressRepository,
                onBack = { navController.safePop() },
                onOpenLesson = { lessonId -> navController.navigateSingle(Routes.lesson(courseId, lessonId)) },
            )
        }
        composable(
            route = Routes.LESSON,
            arguments = listOf(
                navArgument("courseId") { type = NavType.StringType },
                navArgument("lessonId") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val courseId = backStackEntry.arguments?.getString("courseId").orEmpty()
            val lessonId = backStackEntry.arguments?.getString("lessonId").orEmpty()
            LessonScreen(
                courseId = courseId,
                lessonId = lessonId,
                progressRepository = academyProgressRepository,
                onBack = { navController.safePop() },
            )
        }
    }
}
