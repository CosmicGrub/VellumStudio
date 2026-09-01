package com.vellum.studio.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vellum.studio.model.ProjectRepository
import com.vellum.studio.network.NetworkUtils
import com.vellum.studio.network.SyncServer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(repository: ProjectRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    var server by remember { mutableStateOf<SyncServer?>(null) }
    var running by remember { mutableStateOf(false) }
    var startError by remember { mutableStateOf<String?>(null) }
    var ip by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { ip = NetworkUtils.localIpAddress(context) }

    DisposableEffect(Unit) {
        onDispose { server?.stop() }
    }

    fun toggle() {
        val current = server
        if (current != null && running) {
            current.stop()
            server = null
            running = false
            return
        }
        val fresh = SyncServer(repository)
        val result = runCatching { fresh.start() }
        if (result.isSuccess) {
            server = fresh
            running = true
            startError = null
        } else {
            startError = result.exceptionOrNull()?.message ?: "Couldn't start the server"
            running = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect to PC") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (running) "Wi-Fi sync is ON" else "Wi-Fi sync is off", style = MaterialTheme.typography.titleMedium)
                    if (running) {
                        Text(
                            "Point Vellum Companion on your PC at:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${ip ?: "(no Wi-Fi IP found)"}:${server?.listeningPort ?: SyncServer.DEFAULT_PORT}",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            "Start sync, then enter this tablet's address in the Vellum Companion app on your PC to browse and download your canvases over Wi-Fi.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    startError?.let {
                        Text("Couldn't start: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Button(onClick = { toggle() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (running) "Stop Wi-Fi Sync" else "Start Wi-Fi Sync")
            }

            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("What this does today", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "• Browse and download finished canvases from your PC, over your local Wi-Fi\n" +
                            "• A still-frame \"mirror\" endpoint your PC can poll for a rough live preview\n" +
                            "• No account, no cloud — stays on your network",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Not built (yet): using this tablet as a real second display for your PC, with the S Pen driving the cursor " +
                            "inside apps like Photoshop. That needs a signed virtual-display + pen driver on Windows — a much bigger, " +
                            "riskier undertaking that we've deliberately left out. See PC_CONNECTION.md in the project for the plan.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
