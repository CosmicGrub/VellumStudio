package com.vellum.studio.ui.academy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vellum.studio.academy.AcademyLibrary
import com.vellum.studio.academy.AcademyProgressRepository
import com.vellum.studio.academy.Course
import com.vellum.studio.academy.Instructors

/**
 * Landing screen for the Academy: every [Course], with a progress bar for how many of its lessons
 * the user has completed. This is deliberately the one place in the app that leads with "you always
 * wanted to learn this" framing rather than tool chrome — see the subtitle text below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcademyScreen(
    progressRepository: AcademyProgressRepository,
    onBack: () -> Unit,
    onOpenCourse: (String) -> Unit,
) {
    LaunchedEffect(Unit) { progressRepository.load() }
    val completedKeys by progressRepository.completedLessonKeys

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Academy") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "Courses on drawing fundamentals, built for a genuine beginner. Work through them in any order, at any pace — undo is free and nobody's grading the practice sketches.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(AcademyLibrary.all, key = { it.id }) { course ->
                    val done = course.lessons.count { "${course.id}/${it.id}" in completedKeys }
                    CourseCard(course = course, completed = done, onClick = { onOpenCourse(course.id) })
                }
            }
        }
    }
}

@Composable
private fun CourseCard(course: Course, completed: Int, onClick: () -> Unit) {
    val total = course.lessons.size
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)).padding(10.dp),
            ) {
                Icon(Icons.Filled.School, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(course.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    course.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Text(
                    if (completed == 0) "$total lessons" else "$completed of $total lessons complete",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else completed.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 4.dp),
                )
                Instructors.byId(course.instructorId)?.let {
                    InstructorBadge(it, compact = true, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
