package com.vellum.studio.ui.academy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vellum.studio.academy.Instructor

/**
 * Small avatar-and-name credit for the [Instructor] teaching a course — a colored initials
 * circle plus name/tagline, so a course reads as "taught by someone" rather than anonymous text.
 * Two sizes: [compact] for a course card in a list, full-size for the course detail header.
 */
@Composable
fun InstructorBadge(instructor: Instructor, modifier: Modifier = Modifier, compact: Boolean = false) {
    val avatarSize = if (compact) 28.dp else 44.dp
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .size(avatarSize)
                .clip(CircleShape)
                .background(Color(instructor.accentArgb)),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                instructor.initials,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 2.dp).align(Alignment.CenterHorizontally),
            )
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(
                "Taught by ${instructor.name}",
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            if (!compact) {
                Text(
                    instructor.tagline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
