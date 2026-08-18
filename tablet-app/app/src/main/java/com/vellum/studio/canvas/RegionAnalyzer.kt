package com.vellum.studio.canvas

import android.graphics.Bitmap

/** One numbered, pre-colored region in paint-by-number mode. */
data class PaintRegion(
    val number: Int,
    val colorArgb: Int,
    val centroidX: Float,
    val centroidY: Float,
    val pixelCount: Int,
)

/**
 * Per-pixel region lookup for paint-by-number mode, produced once by [RegionAnalyzer.analyze] and
 * cached on [CanvasEngine] until the boundary layers change.
 */
class RegionMap(
    private val labels: IntArray,
    val width: Int,
    val height: Int,
    val regions: List<PaintRegion>,
    private val labelToRegionIndex: IntArray,
) {
    /** The numbered region at a canvas pixel, or null if it's a wall or a too-small sliver. */
    fun regionAt(x: Int, y: Int): PaintRegion? {
        if (x < 0 || x >= width || y < 0 || y >= height) return null
        val label = labels[y * width + x]
        if (label < 0) return null
        val regionIndex = labelToRegionIndex[label]
        if (regionIndex < 0) return null
        return regions.getOrNull(regionIndex)
    }
}

/**
 * Discovers every enclosed "open" region bounded by a boundary mask (same convention as
 * [FloodFillTool] / [CanvasEngine.boundaryMaskAbove]: a pixel with boundary alpha >=
 * [wallAlphaThreshold] is a wall) and assigns each one a sequential number plus a color drawn
 * from a fixed rotating palette — the actual "paint by numbers" data model for a coloring page.
 *
 * Iterative scanline connected-component labeling — same shape and performance rationale as
 * [FloodFillTool]'s fill: an explicit stack of row spans, not a per-pixel or recursive walk, so
 * this stays fast and safe on a multi-megapixel canvas. Every blob gets an internal label as it's
 * found; blobs smaller than [minRegionPixels] (antialiasing slivers, tiny gaps between linework)
 * are dropped from the exposed, numbered [RegionMap.regions] list — but the label array still
 * accounts for every pixel, so there's no id collision between a dropped blob and a later real one.
 */
object RegionAnalyzer {
    private val PALETTE = intArrayOf(
        0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
        0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFF00ACC1.toInt(), 0xFFD81B60.toInt(),
        0xFF6D4C41.toInt(), 0xFF7CB342.toInt(), 0xFFFFB300.toInt(), 0xFF5E35B1.toInt(),
    )

    fun analyze(boundary: Bitmap, wallAlphaThreshold: Int = 40, minRegionPixels: Int = 400): RegionMap {
        val width = boundary.width
        val height = boundary.height

        val boundaryPixels = IntArray(width * height)
        boundary.getPixels(boundaryPixels, 0, width, 0, 0, width, height)
        fun isWall(x: Int, y: Int): Boolean = ((boundaryPixels[y * width + x] ushr 24) and 0xFF) >= wallAlphaThreshold

        val labels = IntArray(width * height) { -1 }
        val blobPixelCounts = ArrayList<Int>()
        val blobSumX = ArrayList<Long>()
        val blobSumY = ArrayList<Long>()
        var nextLabel = 0

        val stack = ArrayDeque<IntArray>()

        fun seedAdjacentRow(lx: Int, rx: Int, row: Int) {
            if (row < 0 || row >= height) return
            val rowOffset = row * width
            var x = lx
            while (x <= rx) {
                if (labels[rowOffset + x] == -1 && !isWall(x, row)) {
                    stack.addLast(intArrayOf(x, row))
                    while (x <= rx && labels[rowOffset + x] == -1 && !isWall(x, row)) x++
                } else {
                    x++
                }
            }
        }

        for (startY in 0 until height) {
            var startX = 0
            while (startX < width) {
                val startIdx = startY * width + startX
                if (labels[startIdx] != -1 || isWall(startX, startY)) {
                    startX++
                    continue
                }

                val label = nextLabel
                nextLabel++
                var sumX = 0L
                var sumY = 0L
                var count = 0
                stack.addLast(intArrayOf(startX, startY))

                while (stack.isNotEmpty()) {
                    val seed = stack.removeLast()
                    val sx = seed[0]
                    val sy = seed[1]
                    val rowOffset = sy * width
                    if (labels[rowOffset + sx] != -1 || isWall(sx, sy)) continue

                    var lx = sx
                    while (lx > 0 && labels[rowOffset + lx - 1] == -1 && !isWall(lx - 1, sy)) lx--
                    var rx = sx
                    while (rx < width - 1 && labels[rowOffset + rx + 1] == -1 && !isWall(rx + 1, sy)) rx++

                    for (x in lx..rx) {
                        val idx = rowOffset + x
                        labels[idx] = label
                        sumX += x
                        sumY += sy
                    }
                    count += (rx - lx + 1)

                    seedAdjacentRow(lx, rx, sy - 1)
                    seedAdjacentRow(lx, rx, sy + 1)
                }

                blobPixelCounts.add(count)
                blobSumX.add(sumX)
                blobSumY.add(sumY)
                startX++
            }
        }

        // Second pass: promote large-enough blobs to numbered regions, in discovery order, and
        // build the label->region-index map (−1 for blobs that didn't make the cut).
        val labelToRegionIndex = IntArray(nextLabel) { -1 }
        val regions = ArrayList<PaintRegion>()
        for (label in 0 until nextLabel) {
            val count = blobPixelCounts[label]
            if (count < minRegionPixels) continue
            val cx = (blobSumX[label] / count).toFloat()
            val cy = (blobSumY[label] / count).toFloat()
            val number = regions.size + 1
            labelToRegionIndex[label] = regions.size
            regions.add(PaintRegion(number, PALETTE[regions.size % PALETTE.size], cx, cy, count))
        }

        return RegionMap(labels, width, height, regions, labelToRegionIndex)
    }
}
