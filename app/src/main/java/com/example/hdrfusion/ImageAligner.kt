package com.example.hdrfusion

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Compensates for hand shake *between* bracket frames. [MotionMonitor]'s per-frame IMU
 * check guards against blur *within* a single exposure, but the hand can still drift a
 * few pixels between shots over the second or two a multi-step bracket takes. Since
 * [SaturationFusion] is a strict per-pixel argmax across frames, that drift shows up as
 * ghosting/misregistration — most visible as fringing at high-contrast edges.
 *
 * This does a translation-only (no rotation/scale) coarse-to-fine search for the pixel
 * offset that best aligns each frame to the first ("reference") frame, using normalized
 * cross-correlation over downsampled luma, then crops every frame to the region common
 * to all of them post-shift so the fused output has no ragged/invalid edges.
 *
 * What this does NOT fix: a moving subject in an otherwise static scene. That needs
 * per-object motion estimation (optical flow, or feature-based deghosting), which is a
 * different and much larger problem than a single global 2D shift — out of scope here,
 * and inherent to any per-pixel argmax fusion, not something a global alignment step
 * could paper over.
 */
object ImageAligner {

    private const val COARSE_SEARCH_RADIUS = 8
    private const val REFINE_SEARCH_RADIUS = 3
    private const val PYRAMID_BASE_DIM = 48
    private const val PYRAMID_TOP_DIM = 384

    /** Aligns every frame to frames[0] and crops all of them to the shared overlap region. */
    fun alignAndCrop(frames: List<Bitmap>): List<Bitmap> {
        if (frames.size <= 1) return frames
        val reference = frames[0]

        val shifts = frames.map { frame ->
            if (frame === reference) 0 to 0 else estimateShift(reference, frame)
        }

        val w = reference.width
        val h = reference.height
        val maxNegDx = shifts.maxOf { max(0, -it.first) }
        val maxPosDx = shifts.maxOf { max(0, it.first) }
        val maxNegDy = shifts.maxOf { max(0, -it.second) }
        val maxPosDy = shifts.maxOf { max(0, it.second) }

        val x0 = maxNegDx
        val y0 = maxNegDy
        val cropW = w - maxNegDx - maxPosDx
        val cropH = h - maxNegDy - maxPosDy
        // Shake large enough to leave no common region at all: fuse the frames unaligned
        // rather than fail the whole shoot over it.
        if (cropW <= 0 || cropH <= 0) return frames

        return frames.mapIndexed { i, frame ->
            val (dx, dy) = shifts[i]
            Bitmap.createBitmap(frame, x0 + dx, y0 + dy, cropW, cropH)
        }
    }

    /**
     * Coarse-to-fine search: starts on a tiny (~48px) downsample with a wide search radius,
     * then refines on progressively larger downsamples with a narrow radius around the
     * previous estimate. Full-resolution exhaustive search would be far too slow (the
     * search space is quadratic in radius); this pyramid keeps total cost to a few million
     * correlation samples per frame regardless of the source resolution.
     */
    private fun estimateShift(reference: Bitmap, moving: Bitmap): Pair<Int, Int> {
        val maxDim = max(reference.width, reference.height)
        val dims = mutableListOf<Int>()
        var dim = PYRAMID_BASE_DIM
        while (dim < min(maxDim, PYRAMID_TOP_DIM)) {
            dims.add(dim)
            dim *= 2
        }
        dims.add(min(maxDim, PYRAMID_TOP_DIM))

        var dx = 0
        var dy = 0
        var lastScale = 1f
        for ((i, levelDim) in dims.withIndex()) {
            val scale = levelDim.toFloat() / maxDim
            val refL = downsampleLuma(reference, scale)
            val movL = downsampleLuma(moving, scale)
            val radius = if (i == 0) COARSE_SEARCH_RADIUS else REFINE_SEARCH_RADIUS
            val centerDx = if (i == 0) 0 else dx * 2
            val centerDy = if (i == 0) 0 else dy * 2

            var bestScore = Double.NEGATIVE_INFINITY
            var bestDx = centerDx
            var bestDy = centerDy
            for (cdy in (centerDy - radius)..(centerDy + radius)) {
                for (cdx in (centerDx - radius)..(centerDx + radius)) {
                    val score = correlationAt(refL, movL, cdx, cdy)
                    if (score > bestScore) {
                        bestScore = score
                        bestDx = cdx
                        bestDy = cdy
                    }
                }
            }
            dx = bestDx
            dy = bestDy
            lastScale = scale
        }

        return (dx / lastScale).roundToInt() to (dy / lastScale).roundToInt()
    }

    private class LumaImage(val w: Int, val h: Int, val data: FloatArray)

    /** Downsampled grayscale copy used only for the correlation search, never for output pixels. */
    private fun downsampleLuma(bmp: Bitmap, scale: Float): LumaImage {
        val w = max(1, (bmp.width * scale).toInt())
        val h = max(1, (bmp.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        val luma = FloatArray(w * h) { i ->
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            0.299f * r + 0.587f * g + 0.114f * b
        }
        if (small !== bmp) small.recycle()
        return LumaImage(w, h, luma)
    }

    /** Normalized cross-correlation between reference and moving-shifted-by-(dx,dy), over their overlap. */
    private fun correlationAt(reference: LumaImage, moving: LumaImage, dx: Int, dy: Int): Double {
        val w = min(reference.w, moving.w)
        val h = min(reference.h, moving.h)
        val xStart = max(0, -dx)
        val xEnd = min(w, w - dx)
        val yStart = max(0, -dy)
        val yEnd = min(h, h - dy)
        if (xEnd <= xStart || yEnd <= yStart) return Double.NEGATIVE_INFINITY

        var sumA = 0.0; var sumB = 0.0; var sumAB = 0.0; var sumA2 = 0.0; var sumB2 = 0.0; var n = 0
        for (y in yStart until yEnd) {
            val refRow = y * reference.w
            val movRow = (y + dy) * moving.w
            for (x in xStart until xEnd) {
                val a = reference.data[refRow + x].toDouble()
                val b = moving.data[movRow + x + dx].toDouble()
                sumA += a; sumB += b; sumAB += a * b; sumA2 += a * a; sumB2 += b * b
                n++
            }
        }
        if (n == 0) return Double.NEGATIVE_INFINITY
        val meanA = sumA / n
        val meanB = sumB / n
        val cov = sumAB / n - meanA * meanB
        val varA = sumA2 / n - meanA * meanA
        val varB = sumB2 / n - meanB * meanB
        val denom = sqrt(varA * varB)
        return if (denom < 1e-6) 0.0 else cov / denom
    }
}
