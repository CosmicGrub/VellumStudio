package com.vellum.studio.canvas

import android.graphics.Bitmap

/**
 * Paint-bucket fill implemented as an iterative scanline flood fill: from a seed pixel we walk
 * left/right along the current row to fill the entire contiguous span in one pass, then seed at
 * most one follow-up point per contiguous run found in the rows directly above and below. This
 * keeps the work stack proportional to the number of spans rather than the number of pixels, so
 * a fill covering a large fraction of a multi-megapixel canvas stays fast and never recurses
 * (a naive per-pixel recursive fill would blow the call stack long before that). All pixel
 * reads/writes go through IntArrays pulled once via getPixels/setPixels, since per-pixel
 * Bitmap.getPixel/setPixel calls are far too slow at 2048x2048+.
 */
object FloodFillTool {

    fun fill(
        target: Bitmap,
        boundary: Bitmap,
        startX: Int,
        startY: Int,
        fillColorArgb: Int,
        alpha: Float = 1f,
        wallAlphaThreshold: Int = 40,
    ): Boolean {
        val width = target.width
        val height = target.height

        if (boundary.width != width || boundary.height != height) return false
        if (startX < 0 || startX >= width || startY < 0 || startY >= height) return false

        // Pull the boundary layer's pixels once; only its alpha channel matters, used purely as
        // a read-only "is this pixel a wall" mask.
        val boundaryPixels = IntArray(width * height)
        boundary.getPixels(boundaryPixels, 0, width, 0, 0, width, height)

        fun isWall(x: Int, y: Int): Boolean =
            ((boundaryPixels[y * width + x] ushr 24) and 0xFF) >= wallAlphaThreshold

        // Tapped directly on a line - nothing to do.
        if (isWall(startX, startY)) return false

        // Pull target's pixels once; this is the buffer we mutate in place and write back at the
        // very end via a single setPixels call.
        val targetPixels = IntArray(width * height)
        target.getPixels(targetPixels, 0, width, 0, 0, width, height)

        val effectiveAlpha = (alpha * 255f).toInt().coerceIn(0, 255)
        val srcR = (fillColorArgb ushr 16) and 0xFF
        val srcG = (fillColorArgb ushr 8) and 0xFF
        val srcB = fillColorArgb and 0xFF

        // Standard SRC_OVER compositing of the fill color onto whatever is already at a pixel,
        // worked out in straight (non-premultiplied) alpha since that's what a packed
        // ARGB_8888 int represents. Guards the outA == 0 case to avoid dividing by zero.
        fun compositeOver(dst: Int): Int {
            val dstA = (dst ushr 24) and 0xFF
            val dstR = (dst ushr 16) and 0xFF
            val dstG = (dst ushr 8) and 0xFF
            val dstB = dst and 0xFF

            val outA = effectiveAlpha + dstA * (255 - effectiveAlpha) / 255
            if (outA <= 0) return 0

            val outR = (srcR * effectiveAlpha + dstR * dstA * (255 - effectiveAlpha) / 255) / outA
            val outG = (srcG * effectiveAlpha + dstG * dstA * (255 - effectiveAlpha) / 255) / outA
            val outB = (srcB * effectiveAlpha + dstB * dstA * (255 - effectiveAlpha) / 255) / outA

            return (outA.coerceIn(0, 255) shl 24) or
                (outR.coerceIn(0, 255) shl 16) or
                (outG.coerceIn(0, 255) shl 8) or
                outB.coerceIn(0, 255)
        }

        // Separate visited marker rather than relying on target pixel equality - the composited
        // result depends on each pixel's original color, so it can't be used as a "filled"
        // sentinel on its own.
        val visited = BooleanArray(width * height)

        // Explicit stack of (x, y) seed pairs - iterative by construction, no recursion.
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(startX, startY))

        // Scans row [row] across columns [lx, rx] and pushes one seed per contiguous run of
        // unvisited, non-wall pixels found there. A single interior point is enough per run:
        // when that seed is popped, the main loop re-derives the run's true full left/right
        // extent before filling it.
        fun seedAdjacentRow(lx: Int, rx: Int, row: Int) {
            if (row < 0 || row >= height) return
            val rowOffset = row * width
            var x = lx
            while (x <= rx) {
                val idx = rowOffset + x
                if (!visited[idx] && !isWall(x, row)) {
                    stack.addLast(intArrayOf(x, row))
                    while (x <= rx && !visited[rowOffset + x] && !isWall(x, row)) x++
                } else {
                    x++
                }
            }
        }

        var modified = false

        while (stack.isNotEmpty()) {
            val seed = stack.removeLast()
            val sx = seed[0]
            val sy = seed[1]
            val rowOffset = sy * width

            // Already covered by a previously filled span, or blocked - skip.
            if (visited[rowOffset + sx] || isWall(sx, sy)) continue

            // Find the full contiguous, unvisited, non-wall span on this row.
            var lx = sx
            while (lx > 0 && !visited[rowOffset + lx - 1] && !isWall(lx - 1, sy)) lx--
            var rx = sx
            while (rx < width - 1 && !visited[rowOffset + rx + 1] && !isWall(rx + 1, sy)) rx++

            for (x in lx..rx) {
                val idx = rowOffset + x
                visited[idx] = true
                targetPixels[idx] = compositeOver(targetPixels[idx])
            }
            modified = true

            seedAdjacentRow(lx, rx, sy - 1)
            seedAdjacentRow(lx, rx, sy + 1)
        }

        if (modified) {
            target.setPixels(targetPixels, 0, width, 0, 0, width, height)
        }
        return modified
    }
}
