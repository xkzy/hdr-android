package com.example.hdrfusion

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Generates synthetic exposures from a burst of identically-exposed aligned frames.
 * Rather than capturing different exposures with different ISO/shutter settings (and
 * incurring motion blur risk during each frame), this captures a rapid burst at a
 * single, well-metered exposure (peak brightness ~127 for headroom), then synthesizes
 * multiple "virtual" exposures by intelligently combining the frames.
 *
 * The key insight: after alignment, frames are pixel-identical except for sensor noise.
 * Averaging reduces noise (SNR improves as sqrt(N)). Weighted blending of subsets
 * synthesizes different effective brightness levels for the fusion algorithm.
 */
object SyntheticExposure {

    /**
     * Generates N synthetic exposures from a burst of M aligned frames (M >= N).
     * Produces exposures that span a range equivalent to a traditional bracket,
     * but with better SNR (from averaging) and no shutter variation (minimal motion blur).
     *
     * Strategy: divide the burst into overlapping groups, average each to get a
     * synthetic frame, then optionally brighten/darken subsets to create bracketing diversity.
     */
    fun synthesizeExposures(alignedFrames: List<Bitmap>, desiredOutputCount: Int): List<Bitmap> {
        if (alignedFrames.size < desiredOutputCount) {
            return alignedFrames.padEnd(desiredOutputCount)
        }
        if (desiredOutputCount <= 1) return listOf(averageFrames(alignedFrames))

        val syntheticFrames = mutableListOf<Bitmap>()

        // Divide the burst into overlapping windows, average each window, then
        // apply brightness variations to create the "bracket" effect.
        val windowSize = (alignedFrames.size + desiredOutputCount - 1) / desiredOutputCount
        val step = maxOf(1, windowSize / 2) // Overlapping windows for smoother progression

        for (i in 0 until desiredOutputCount) {
            val startIdx = minOf(alignedFrames.size - windowSize, i * step)
            val endIdx = minOf(alignedFrames.size, startIdx + windowSize)
            val window = alignedFrames.subList(startIdx, endIdx)

            // Average the frames in this window to reduce noise
            var averaged = averageFrames(window)

            // Synthesize exposure variation: modulate brightness to create bracket
            // Center frame (i == desiredOutputCount/2) stays at original brightness
            // Earlier frames: slightly darkened (simulate underexposure)
            // Later frames: slightly brightened (simulate overexposure)
            val centerIdx = desiredOutputCount / 2
            val offsetFromCenter = i - centerIdx
            val brightnessScale = 1.0f + (offsetFromCenter * 0.15f) // ±15% per step

            if (brightnessScale != 1.0f) {
                averaged = adjustBrightness(averaged, brightnessScale)
            }

            syntheticFrames.add(averaged)
        }

        return syntheticFrames
    }

    /**
     * Averages multiple frames pixel-by-pixel, reducing noise via temporal accumulation.
     */
    private fun averageFrames(frames: List<Bitmap>): Bitmap {
        if (frames.isEmpty()) throw IllegalArgumentException("Need at least one frame")
        if (frames.size == 1) return frames[0]

        val w = frames[0].width
        val h = frames[0].height
        val pixels = Array(frames.size) { IntArray(w * h) }
        frames.forEachIndexed { i, frame ->
            frame.getPixels(pixels[i], 0, w, 0, 0, w, h)
        }

        val out = IntArray(w * h)
        val n = frames.size
        for (idx in out.indices) {
            var sumR = 0L; var sumG = 0L; var sumB = 0L; var sumA = 0L
            for (f in 0 until n) {
                val p = pixels[f][idx]
                sumA += (p shr 24) and 0xFF
                sumR += (p shr 16) and 0xFF
                sumG += (p shr 8) and 0xFF
                sumB += p and 0xFF
            }
            val a = (sumA / n).toInt() and 0xFF
            val r = (sumR / n).toInt() and 0xFF
            val g = (sumG / n).toInt() and 0xFF
            val b = (sumB / n).toInt() and 0xFF
            out[idx] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(out, 0, w, 0, 0, w, h)
        }
    }

    /**
     * Simulates a brighter/darker exposure of the same scene by scaling in *linear light*,
     * not directly on the stored sRGB-gamma-encoded pixel values. A camera's exposure control
     * (shutter/ISO/aperture) scales the actual light hitting the sensor — a linear quantity —
     * before the sRGB gamma curve is applied for display/storage. Multiplying the gamma-
     * encoded 0..255 values directly (as a naive "brightness slider" does) applies the wrong
     * curve: because sRGB compresses shadows and expands highlights, a flat multiply pushes
     * midtones and highlights too far relative to shadows, giving a synthetic exposure that
     * looks unnaturally flat/washed rather than like a genuinely longer or shorter exposure.
     * Decoding to linear, scaling by the exposure factor, then re-encoding reproduces the
     * actual relationship between exposure and brightness — the same round-trip
     * [RawDemosaic] uses in the other direction when it encodes demosaiced linear samples.
     */
    private fun adjustBrightness(bitmap: Bitmap, scale: Float): Bitmap {
        if (scale == 1.0f) return bitmap

        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Small lookup table: sRGB-encoded byte -> linear 0..1 float.
        val toLinear = FloatArray(256) { srgbDecode(it / 255f) }

        for (idx in pixels.indices) {
            val p = pixels[idx]
            val a = (p shr 24) and 0xFF
            val r = toLinear[(p shr 16) and 0xFF] * scale
            val g = toLinear[(p shr 8) and 0xFF] * scale
            val b = toLinear[p and 0xFF] * scale

            val rr = srgbEncode(r)
            val gg = srgbEncode(g)
            val bb = srgbEncode(b)
            pixels[idx] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    /** Inverse sRGB opto-electronic transfer function: encoded 0..1 -> linear 0..1. */
    private fun srgbDecode(encoded: Float): Float {
        val x = encoded.coerceIn(0f, 1f)
        return if (x <= 0.04045f) x / 12.92f else ((x + 0.055f) / 1.055f).pow(2.4f)
    }

    /** sRGB opto-electronic transfer function: linear 0..1 -> encoded 0..255 byte. */
    private fun srgbEncode(linear: Float): Int {
        val x = linear.coerceIn(0f, 1f)
        val encoded = if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f
        return (encoded.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    /**
     * Pads a list to a desired length by repeating the last element (if list is shorter).
     */
    private fun <T> List<T>.padEnd(size: Int): List<T> {
        if (this.size >= size) return this
        val padded = this.toMutableList()
        val lastElement = this.lastOrNull() ?: return padded
        while (padded.size < size) padded.add(lastElement)
        return padded
    }
}
