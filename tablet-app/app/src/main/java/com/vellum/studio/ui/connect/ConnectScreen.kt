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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vellum.studio.model.ProjectRepository
import com.vellum.studio.network.NetworkUtils
import com.vellum.studio.network.SyncServer
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(repository: ProjectRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    var server by remember { mutableStateOf<SyncServer?>(null) }
    var running by remember { mutableStateOf(false) }
    var startError by remember { mutableStateOf<String?>(null) }
    // Why sync last went away when the user didn't press Stop (idle timer, app left the screen), so a
    // vanished server is explained instead of mysterious.
    var notice by remember { mutableStateOf<String?>(null) }
    // The address the server actually bound, not a separately computed "display" address.
    var address by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf<String?>(null) }
    var idleRemainingMs by remember { mutableStateOf(0L) }
    var lockedOut by remember { mutableStateOf(false) }
    var lastClient by remember { mutableStateOf<String?>(null) }

    fun stopSync(reason: String?) {
        server?.stop()
        server = null
        running = false
        pin = null
        lockedOut = false
        lastClient = null
        notice = reason
    }

    DisposableEffect(Unit) {
        onDispose { server?.stop() }
    }

    // ON_STOP, not just leaving composition: pressing Home or switching apps leaves this screen
    // composed, and until now the server kept serving canvases from a backgrounded app.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && server != null) {
                stopSync("Wi-Fi sync stopped because Vellum Studio left the screen. Start it again for a new PIN.")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Drives the idle countdown and notices when the server's own idle timer shut it down. That
    // happens on a background thread, so the screen finds out by looking rather than by callback.
    LaunchedEffect(server) {
        val s = server ?: return@LaunchedEffect
        while (true) {
            if (!s.isAlive) {
                stopSync(
                    if (s.stoppedForIdle) {
                        "Wi-Fi sync stopped after ${SyncServer.DEFAULT_IDLE_TIMEOUT_MS / 60_000} minutes without use. Start it again for a new PIN."
                    } else {
                        "Wi-Fi sync stopped."
                    },
                )
                break
            }
            idleRemainingMs = s.idleRemainingMs()
            lockedOut = s.isLockedOut
            lastClient = s.lastClient
            delay(1000)
        }
    }

    fun toggle() {
        if (server != null && running) {
            stopSync(null)
            return
        }
        notice = null
        // No Wi-Fi/LAN address means no server: binding anything else would expose it on mobile data
        // or a VPN, which is exactly what this screen promises it doesn't do.
        val lan = NetworkUtils.lanIpAddress(context)
        if (lan == null) {
            startError = "No Wi-Fi network found. Connect this tablet to Wi-Fi first; sync never runs over mobile data."
            return
        }
        val fresh = SyncServer(repository, context.applicationContext, lan)
        val result = runCatching { fresh.start() }
        if (result.isSuccess) {
            server = fresh
            running = true
            startError = null
            address = "$lan:${fresh.listeningPort}"
            pin = fresh.pin
            idleRemainingMs = fresh.idleRemainingMs()
            lockedOut = false
            lastClient = null
        } else {
            fresh.stop()
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
                            address ?: "",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "and enter this PIN:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            pin ?: "",
                            style = MaterialTheme.typography.headlineLarge.copy(letterSpacing = 6.sp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (lockedOut) {
                            Text(
                                "Locked: too many wrong PINs were tried. Tap Stop, then Start for a new PIN.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            lastClient?.let {
                                Text("Last connection from $it", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        val secs = (idleRemainingMs / 1000).coerceAtLeast(0)
                        Text(
                            "Stops by itself in ${secs / 60}:${"%02d".format(secs % 60)} unless it is used.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "Start sync, then enter this tablet's address and the PIN it shows in the Vellum Companion app on your PC to browse and download your canvases over Wi-Fi.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        notice?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
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
                            "• No account, no cloud. Only reachable over this tablet's Wi-Fi, and only with the PIN\n" +
                            "• Stops by itself after ${SyncServer.DEFAULT_IDLE_TIMEOUT_MS / 60_000} minutes without use, or when you leave the app",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "This is plain HTTP, not encrypted. Anyone on the same network who has the PIN can browse your canvases, " +
                            "and someone snooping on that network could read what is sent, PIN included. Use it on a network you " +
                            "trust, not on public Wi-Fi such as a café, hotel or campus.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Not built (yet): using this tablet as a real second display for your PC, with the S Pen driving the cursor " +
                            "inside apps like Photoshop. That needs a signed virtual-display + pen driver on Windows — a much bigger, " +
                            "riskier undertaking that we've deliberately left out.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
