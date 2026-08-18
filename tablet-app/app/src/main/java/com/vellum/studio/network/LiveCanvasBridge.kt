package com.vellum.studio.network

import com.vellum.studio.canvas.CanvasEngine
import com.vellum.studio.model.ProjectMeta

/**
 * Process-wide pointer to whichever project is currently open in the editor, so [SyncServer] can
 * serve live state (the mirror-frame snapshot) without the network layer holding a hard reference
 * into the UI layer. Set by the editor screen on enter/leave.
 */
object LiveCanvasBridge {
    @Volatile
    var activeMeta: ProjectMeta? = null
        private set

    @Volatile
    var activeEngine: CanvasEngine? = null
        private set

    fun set(meta: ProjectMeta?, engine: CanvasEngine?) {
        activeMeta = meta
        activeEngine = engine
    }
}
