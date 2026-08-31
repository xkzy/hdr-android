package com.example.hdrfusion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Compensates for hand shake *between* bracket frames. [MotionMonitor]'s per-frame IMU
 * check guards against blur *within* a single exposure, but the hand can still drift and
 * twist a few pixels/degrees between shots over the second or two a multi-step bracket
 * takes. Since [SaturationFusion] blends per-pixel across frames, that drift shows up as
 * ghosting/misregistration — most visible as fringing at high-contrast edges.
 *
 * This corrects two kinds of motion, in order:
 * 1. **Small-angle rotation** ([correctRotation]) — a two-patch shift-difference estimate
 *    (see its doc) catches the wrist-twist component of handshake that a pure translation
 *    search cannot, applied and clamped conservatively since it's a coarse estimate.
 * 2. **Translation** ([estimateShift]) — coarse-to-fine normalized cross-correlation search,
 *    same as before, now run on the already-derotated frame.
 *
 * Frames are then cropped to the region common to all of them post-correction so the fused
 * output has no ragged/invalid edges.
 *
 * What this still does NOT fix: scale/perspective changes (e.g. the phone drifting closer/
 * farther, or tilting off-axis rather than twisting in-plane), or a moving subject in an
 * otherwise static scene. Those need a full homography or per-object motion estimation
 * (optical flow / feature-based registration) — a different and much larger problem than the
 * small-angle rigid correction here, and inherent to any per-pixel fusion, not something a
 * global alignment step could paper over.
 */
object ImageAligner {

    private const val COARSE_SEARCH_RADIUS = 8
    private const val REFINE_SEARCH_RADIUS = 3
    private const val PYRAMID_BASE_DIM = 48
    private const val PYRAMID_TOP_DIM = 384

    /** Downsampled size used only for the two-patch rotation estimate. */
    private const val ROTATION_ANALYSIS_DIM = 240
    private const val ROTATION_SEARCH_RADIUS = 6
    /** Below this, treat the estimate as measurement noise rather than real rotation. */
    private const val ROTATION_MIN_DEGREES = 0.15f
    /** Above this, distrust the (coarse, linear) estimate rather than risk overcorrecting. */
    private const val ROTATION_MAX_DEGREES = 4.0f

    /** Aligns every frame to frames[0] and crops all of them to the shared overlap region. */
    fun alignAndCrop(frames: List<Bitmap>): List<Bitmap> {
        if (frames.size <= 1) return frames
        val reference = frames[0]

        val derotated = frames.map { frame ->
            if (frame === reference) frame else correctRotation(reference, frame)
        }

        val shifts = derotated.map { frame ->
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

        return derotated.mapIndexed { i, frame ->
            val (dx, dy) = shifts[i]
            Bitmap.createBitmap(frame, x0 + dx, y0 + dy, cropW, cropH)
        }
    }

    /**
     * Corrects the in-plane rotation component of handshake (the wrist naturally twists
     * slightly during a multi-frame bracket, not just translates) that a translation-only
     * search cannot represent at all — no (dx,dy) can align a rotated frame to its reference
     * away from the center of rotation.
     *
     * Estimated via two independent translation searches on a left-side and right-side patch
     * of the frame: for a pure rotation by angle θ around the image center, a point offset by
     * +r from center moves tangentially by ~r·θ in the opposite sense to a point at -r — so
     * the *difference* in vertical shift between a left patch and a right patch, divided by
     * the horizontal distance between them, approximates θ (small-angle: tan θ ≈ θ). This is
     * a coarse, linear approximation — it ignores any true translation-vs-rotation coupling
     * beyond first order, and treats the two patches' independent noise as if it were signal —
     * so the result is clamped to a small range and estimates below the noise floor are
     * ignored entirely (see [ROTATION_MIN_DEGREES]/[ROTATION_MAX_DEGREES]) rather than trusted
     * outright. This is not a substitute for proper feature-based homography estimation; it
     * only targets the common case of a few tenths to a few degrees of handshake twist.
     */
    private fun correctRotation(reference: Bitmap, moving: Bitmap): Bitmap {
        val maxDim = max(reference.width, reference.height)
        val scale = min(1f, ROTATION_ANALYSIS_DIM.toFloat() / maxDim)
        val refL = downsampleLuma(reference, scale)
        val movL = downsampleLuma(moving, scale)

        val patchW = refL.w / 3
        val yStart = refL.h / 4
        val yEnd = refL.h - yStart
        if (patchW < 8 || yEnd <= yStart) return moving

        val leftShift = searchRegionShift(refL, movL, 0, patchW, yStart, yEnd, ROTATION_SEARCH_RADIUS)
        val rightShift = searchRegionShift(refL, movL, refL.w - patchW, refL.w, yStart, yEnd, ROTATION_SEARCH_RADIUS)

        val patchCenterSeparationPx = (refL.w - patchW / 2) - (patchW / 2)
        if (patchCenterSeparationPx <= 0) return moving

        val dyDiff = (rightShift.second - leftShift.second).toDouble()
        val angleDeg = Math.toDegrees(atan2(dyDiff, patchCenterSeparationPx.toDouble())).toFloat()

        if (abs(angleDeg) < ROTATION_MIN_DEGREES) return moving
        val clamped = angleDeg.coerceIn(-ROTATION_MAX_DEGREES, ROTATION_MAX_DEGREES)

        val matrix = Matrix().apply {
            postRotate(-clamped, moving.width / 2f, moving.height / 2f)
        }
        val output = Bitmap.createBitmap(moving.width, moving.height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(moving, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return output
    }

    /** Brute-force best (dx,dy) within [radius] that maximizes correlation over one sub-region. */
    private fun searchRegionShift(
        reference: LumaImage,
        moving: LumaImage,
        xStart: Int,
        xEnd: Int,
        yStart: Int,
        yEnd: Int,
        radius: Int
    ): Pair<Int, Int> {
        var bestScore = Double.NEGATIVE_INFINITY
        var bestDx = 0
        var bestDy = 0
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val score = regionCorrelationAt(reference, moving, dx, dy, xStart, xEnd, yStart, yEnd)
                if (score > bestScore) {
                    bestScore = score
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        return bestDx to bestDy
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
        return regionCorrelationAt(reference, moving, dx, dy, 0, min(reference.w, moving.w), 0, min(reference.h, moving.h))
    }

    /** Normalized cross-correlation restricted to [xStart,xEnd) x [yStart,yEnd) of the reference frame. */
    private fun regionCorrelationAt(
        reference: LumaImage,
        moving: LumaImage,
        dx: Int,
        dy: Int,
        xStart: Int,
        xEnd: Int,
        yStart: Int,
        yEnd: Int
    ): Double {
        val movW = moving.w
        val movH = moving.h
        val clampedXStart = max(xStart, -dx)
        val clampedXEnd = min(xEnd, movW - dx)
        val clampedYStart = max(yStart, -dy)
        val clampedYEnd = min(yEnd, movH - dy)
        if (clampedXEnd <= clampedXStart || clampedYEnd <= clampedYStart) return Double.NEGATIVE_INFINITY

        var sumA = 0.0; var sumB = 0.0; var sumAB = 0.0; var sumA2 = 0.0; var sumB2 = 0.0; var n = 0
        for (y in clampedYStart until clampedYEnd) {
            val refRow = y * reference.w
            val movRow = (y + dy) * movW
            for (x in clampedXStart until clampedXEnd) {
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
