package com.vellum.studio.ui.editor

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.vellum.studio.model.LoadResult
import com.vellum.studio.util.DiagnosticLog

/**
 * What the editor shows instead of a canvas when [LoadResult] was not Ok: why, plus the two things
 * a user can do -- go Back (the same top-bar/system Back, which with no autosaver just leaves) and
 * Export log (the cause was already written to the [DiagnosticLog]). There is deliberately no
 * canvas, engine or autosaver behind this card, so nothing on screen can edit or save the project
 * that failed to open; a spinner would have hidden the failure and a blank canvas would have invited
 * a save over the real files.
 */
@Composable
fun ProjectOpenErrorCard(
    failure: LoadResult.Failed,
    onBack: () -> Unit,
    onExportLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.testTag(PROJECT_OPEN_ERROR_TAG).padding(32.dp).widthIn(max = 480.dp)) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(
                failure.title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                failure.message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Back")
                }
                OutlinedButton(onClick = onExportLog) {
                    Icon(Icons.Filled.IosShare, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Export log")
                }
            }
        }
    }
}

/** Test tag on the card, so a UI test can find it without matching on wording. */
const val PROJECT_OPEN_ERROR_TAG = "project-open-error"

/**
 * Opens the share sheet for the diagnostic log -- the same intent Settings > Diagnostics > Export
 * builds. A no-op if there is nothing to send or no app can take it: this runs from an error card,
 * where a second failure must not become a crash.
 */
fun exportDiagnosticLog(context: Context) {
    runCatching {
        val file = DiagnosticLog.file(context)
        if (!file.exists() || file.length() == 0L) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Vellum Studio diagnostic log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(sendIntent, "Export diagnostic log"))
    }
}
