package com.example.hdrfusion

import android.graphics.Bitmap
import kotlin.math.sqrt

/**
 * Estimates noise characteristics and signal-to-noise ratio (SNR) from frame data.
 * Used to determine when sufficient frames have been captured via stacking to reach
 * a target SNR threshold, enabling adaptive burst length based on scene noise.
 *
 * SNR improves as sqrt(N) with frame stacking: SNR_stacked = SNR_single * sqrt(N).
 * This allows early termination when target SNR is reached, reducing capture time
 * in low-noise scenes while capturing more frames in high-noise (low-light) scenes.
 */
object NoiseAnalysis {

    data class NoiseMetrics(
        /** Estimated noise standard deviation (arbitrary units, normalized to 0..255 scale). */
        val noiseStdDev: Float,
        /** Estimated signal level (mean brightness in valid pixels). */
        val signalLevel: Float,
        /** Signal-to-noise ratio: signalLevel / noiseStdDev. */
        val snr: Float
    )

    /**
     * Estimates noise from a single frame by analyzing local variance.
     * In a low-noise area (like a uniform surface), high-frequency components are noise.
     * In textured areas, high-frequency components could be signal; we sample uniform-ish
     * regions by looking for areas with low local gradient.
     */
    fun estimateFrameNoise(bitmap: Bitmap): NoiseMetrics {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Convert to luma for noise analysis (standard weights)
        val luma = FloatArray(w * h) { i ->
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            0.299f * r + 0.587f * g + 0.114f * b
        }

        // Estimate noise from high-frequency components (local differences)
        // Sample interior pixels where we can compute gradients
        var sumDiff = 0.0
        var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                // Compute Laplacian (2nd derivative) as noise proxy
                val center = luma[idx]
                val up = luma[(y - 1) * w + x]
                val down = luma[(y + 1) * w + x]
                val left = luma[y * w + (x - 1)]
                val right = luma[y * w + (x + 1)]
                val laplacian = kotlin.math.abs(
                    4 * center - (up + down + left + right)
                )
                sumDiff += laplacian
                count++
            }
        }

        // Noise std dev from Laplacian: σ ≈ √(sumLaplacian / (6*N))
        // Factor 6 is from the Laplacian kernel properties
        val noiseStdDev = sqrt(sumDiff / (6.0 * count)).toFloat()

        // Signal level: mean brightness, but clip at median to reduce outlier influence
        val sortedLuma = luma.sorted()
        val medianSignal = sortedLuma[sortedLuma.size / 2]
        val meanSignal = luma.average().toFloat()
        val signalLevel = (medianSignal + meanSignal) / 2f

        val snr = if (noiseStdDev > 0) signalLevel / noiseStdDev else Float.MAX_VALUE

        return NoiseMetrics(noiseStdDev, signalLevel, snr)
    }

    /**
     * Computes SNR improvement from frame stacking: SNR_stacked = SNR_single * sqrt(frameCount).
     * Used to predict when target SNR will be reached with additional frames.
     */
    fun predictSnrAfterStacking(singleFrameSnr: Float, frameCount: Int): Float {
        return singleFrameSnr * sqrt(frameCount.toFloat())
    }

    /**
     * Given current SNR and target SNR, estimates how many frames are needed to reach it.
     * Returns: (framesNeeded, snrAfterFrames)
     */
    fun estimateFramesForTargetSnr(
        currentFrameSnr: Float,
        targetSnr: Float
    ): Pair<Int, Float> {
        if (currentFrameSnr >= targetSnr) return 1 to currentFrameSnr

        // SNR_target = SNR_single * sqrt(N)
        // N = (SNR_target / SNR_single) ^ 2
        val snrRatio = targetSnr / currentFrameSnr
        val framesNeeded = (snrRatio * snrRatio).toInt().coerceAtLeast(1)
        val achievedSnr = predictSnrAfterStacking(currentFrameSnr, framesNeeded)

        return framesNeeded to achievedSnr
    }
}
