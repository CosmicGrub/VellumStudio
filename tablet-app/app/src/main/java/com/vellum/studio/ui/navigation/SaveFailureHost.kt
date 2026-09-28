package com.vellum.studio.ui.navigation

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.vellum.studio.model.ProjectRepository

/**
 * The one place "your save failed" is shown, hosted ABOVE the nav graph (see MainActivity) so it
 * outlives every screen. It cannot live in the editor: Back saves and navigates away in the same
 * click, so the editor's composition scope and its own SnackbarHost are already gone by the time a
 * disk-full encode/fsync finally fails -- the message used to be dropped and the user left believing
 * the work was saved.
 *
 * Driven by [ProjectRepository.saveFailures], a STATE that is acknowledged only after the snackbar
 * was actually shown: a failure that lands mid-navigation-transition or across an Activity
 * recreation (this effect cancelled before showSnackbar returns) is simply shown again by the next
 * host that starts collecting, rather than lost. Notices are shown one at a time, oldest first.
 */
@Composable
fun SaveFailureHost(repository: ProjectRepository, modifier: Modifier = Modifier) {
    val hostState = remember { SnackbarHostState() }
    val next = repository.saveFailures.collectAsState().value.firstOrNull()
    LaunchedEffect(next) {
        val notice = next ?: return@LaunchedEffect
        hostState.showSnackbar(notice.message, duration = SnackbarDuration.Long)
        repository.acknowledgeSaveFailure(notice)
    }
    // The app is edge-to-edge (MainActivity.enableEdgeToEdge), so keep the snackbar off the nav bar.
    SnackbarHost(hostState, modifier.navigationBarsPadding()) { Snackbar(it) }
}
