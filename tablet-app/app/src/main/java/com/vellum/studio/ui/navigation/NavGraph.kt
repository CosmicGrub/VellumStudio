package com.vellum.studio.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
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
import com.vellum.studio.ui.gallery.GalleryScreen
import com.vellum.studio.ui.settings.SettingsScreen

object Routes {
    const val GALLERY = "gallery"
    const val EDITOR = "editor/{projectId}"
    const val CONNECT = "connect"
    const val SETTINGS = "settings"
    const val COLORING_BOOK = "coloring_book"
    const val ACADEMY = "academy"
    const val COURSE_DETAIL = "academy/course/{courseId}"
    const val LESSON = "academy/course/{courseId}/lesson/{lessonId}"
    fun editor(projectId: String) = "editor/$projectId"
    fun courseDetail(courseId: String) = "academy/course/$courseId"
    fun lesson(courseId: String, lessonId: String) = "academy/course/$courseId/lesson/$lessonId"
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
                onOpenProject = { id -> navController.navigate(Routes.editor(id)) },
                onOpenConnect = { navController.navigate(Routes.CONNECT) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenColoringBook = { navController.navigate(Routes.COLORING_BOOK) },
                onOpenAcademy = { navController.navigate(Routes.ACADEMY) },
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
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.CONNECT) {
            ConnectScreen(repository = repository, onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(settingsRepository = settingsRepository, onBack = { navController.popBackStack() })
        }
        composable(Routes.COLORING_BOOK) {
            ColoringBookScreen(
                repository = repository,
                userPhotoTemplateRepository = userPhotoTemplateRepository,
                onBack = { navController.popBackStack() },
                onOpenProject = { id -> navController.navigate(Routes.editor(id)) { popUpTo(Routes.GALLERY) } },
            )
        }
        composable(Routes.ACADEMY) {
            AcademyScreen(
                progressRepository = academyProgressRepository,
                onBack = { navController.popBackStack() },
                onOpenCourse = { courseId -> navController.navigate(Routes.courseDetail(courseId)) },
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
                onBack = { navController.popBackStack() },
                onOpenLesson = { lessonId -> navController.navigate(Routes.lesson(courseId, lessonId)) },
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
                onBack = { navController.popBackStack() },
            )
        }
    }
}
