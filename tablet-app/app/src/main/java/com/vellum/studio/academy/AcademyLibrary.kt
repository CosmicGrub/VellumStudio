package com.vellum.studio.academy

/** Aggregates every hand-authored [Course] into the single list the Academy UI reads from. */
object AcademyLibrary {
    val all: List<Course> = listOf(
        CourseFoundations.course,
        CoursePerspective.course,
        CourseShading.course,
        CourseColorTheory.course,
        CourseAnatomy.course,
        CourseWatercolor.course,
        CourseDigitalFundamentals.course,
        CourseWetOnWet.course,
        CourseGraffiti.course,
        CoursePhotoReference.course,
    )

    fun byId(id: String): Course? = all.firstOrNull { it.id == id }

    fun lesson(courseId: String, lessonId: String): Lesson? =
        byId(courseId)?.lessons?.firstOrNull { it.id == lessonId }
}
