package com.vellum.studio.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vellum.studio.model.ProjectRepository
import com.vellum.studio.model.TrashedProject
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * The "Recently deleted" view: every soft-deleted project still inside the 30-day window, newest
 * first, each with Restore and (after a confirmation, since it cannot be undone) Delete forever.
 *
 * Re-lists on every [ProjectRepository.libraryRevision] tick, which every trash mutation (restore,
 * delete forever, purge, a delete made while this is open) emits -- so the list never needs manual
 * refresh bookkeeping here. Opening it also runs the purge first, so a long-lived process never
 * shows an entry that is already past its 30 days.
 */
@Composable
internal fun RecentlyDeletedDialog(repository: ProjectRepository, onDismiss: () -> Unit) {
    var trash by remember { mutableStateOf<List<TrashedProject>?>(null) }
    var confirmForever by remember { mutableStateOf<TrashedProject?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val revision by repository.libraryRevision.collectAsState()

    LaunchedEffect(revision) {
        repository.purgeExpiredTrash()
        trash = repository.listTrash()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recently deleted") },
        text = {
            val items = trash
            when {
                items == null -> CircularProgressIndicator()
                items.isEmpty() -> Text(
                    "Nothing here. A deleted canvas is kept for ${ProjectRepository.TRASH_RETENTION_MS.days()} days, then removed for good.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> Column {
                    Text(
                        "Kept for ${ProjectRepository.TRASH_RETENTION_MS.days()} days after deletion.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    notice?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(items, key = { it.trashId }) { entry ->
                            TrashRow(
                                entry = entry,
                                onRestore = {
                                    scope.launch {
                                        notice = if (repository.restoreProject(entry.trashId)) {
                                            null
                                        } else {
                                            "Couldn't restore \"${entry.name}\": a canvas with the same id already exists."
                                        }
                                    }
                                },
                                onDeleteForever = { confirmForever = entry },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )

    confirmForever?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmForever = null },
            title = { Text("Delete forever?") },
            text = { Text("\"${entry.name}\" and all of its layers will be permanently deleted. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmForever = null
                    scope.launch { repository.deleteTrashedProject(entry.trashId) }
                }) { Text("Delete forever", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmForever = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TrashRow(entry: TrashedProject, onRestore: () -> Unit, onDeleteForever: () -> Unit) {
    val daysLeft = TimeUnit.MILLISECONDS.toDays(entry.deletedAt + ProjectRepository.TRASH_RETENTION_MS - System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1) - 1).coerceAtLeast(0)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            ThumbnailImage(entry.thumbnailFile, Modifier.size(56.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "Deleted ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.deletedAt))} – " +
                    if (daysLeft == 1L) "1 day left" else "$daysLeft days left",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRestore) { Text("Restore") }
        IconButton(onClick = onDeleteForever) {
            Icon(Icons.Filled.DeleteForever, contentDescription = "Delete forever", tint = MaterialTheme.colorScheme.error)
        }
    }
}

private fun Long.days(): Long = TimeUnit.MILLISECONDS.toDays(this)
