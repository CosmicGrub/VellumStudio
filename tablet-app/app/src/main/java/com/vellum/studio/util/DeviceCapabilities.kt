package com.vellum.studio.util

import android.app.ActivityManager
import android.content.Context
import com.vellum.studio.VellumApp

/**
 * Lightweight, cached device-capability probe used to scale a few memory-sensitive knobs (undo
 * history depth, the "New Canvas" resolution ceiling) to what the actual running device can
 * comfortably support, instead of the flat constants those were previously hard-coded to — a
 * 12GB-RAM tablet gets meaningfully deeper undo and higher-resolution canvas options than a
 * 4GB budget device would, and a genuinely low-RAM device gets protected from options that would
 * likely OOM it.
 */
object DeviceCapabilities {
    private val activityManager: ActivityManager by lazy {
        VellumApp.instance.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }

    /**
     * The app's actual declared per-process heap ceiling in MB. Prefers `largeMemoryClass` since
     * this app requests `android:largeHeap="true"` in the manifest — that's the real ceiling the
     * process runs under, not the smaller default `memoryClass`.
     */
    val memoryClassMb: Int by lazy {
        activityManager.largeMemoryClass.takeIf { it > 0 } ?: activityManager.memoryClass
    }

    val isLowRamDevice: Boolean by lazy { activityManager.isLowRamDevice }

    /** Heap MB actually usable for budgeting, after clamping a low-RAM device down further. */
    private fun usableMemoryClassMb(): Int = if (isLowRamDevice) memoryClassMb.coerceAtMost(128) else memoryClassMb

    /**
     * A safe undo-history memory budget in bytes, scaled to the device's actual declared heap
     * (previously a flat 180MB constant regardless of device). ~15% of the declared heap,
     * clamped to a floor that still gives a low-end device reasonable undo depth and a ceiling
     * that avoids one project hoarding an unreasonable share of a very generous heap.
     */
    fun undoBudgetBytes(): Long {
        val budgetMb = (usableMemoryClassMb() * 0.15f).coerceIn(48f, 512f)
        return (budgetMb * 1024 * 1024).toLong()
    }

    /**
     * The largest single-layer pixel count (width * height) considered safe to offer as a "New
     * Canvas" resolution preset. Budgets half the declared heap for canvas-related bitmaps
     * (layers + undo snapshots + the shared stroke scratch all need to coexist at once), divided
     * across a conservative nominal concurrent working set — not the hard undo-depth cap, just a
     * "how many full-canvas ARGB_8888 bitmaps might realistically be alive simultaneously" figure.
     */
    fun maxSafeCanvasPixels(): Long {
        val canvasBudgetBytes = usableMemoryClassMb().toLong() * 1024L * 1024L / 2L
        val nominalConcurrentBitmaps = 7L
        val bytesPerPixel = 4L // ARGB_8888
        return canvasBudgetBytes / (nominalConcurrentBitmaps * bytesPerPixel)
    }
}
