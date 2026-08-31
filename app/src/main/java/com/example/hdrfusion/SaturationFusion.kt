package com.example.hdrfusion

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.max
import kotlin.math.min

/**
 * Fuses a bracket of same-framing exposures into one image by choosing, independently for
 * every pixel (i,j), the source frame whose pixel at (i,j) has the highest saturation —
 * i.e. argmax over frames f of saturation(frame_f[i,j]) — then copying that frame's RGB
 * value into the output at (i,j).
 *
 * saturation(pixel) = HSV saturation = (max channel - min channel) / max channel.
 * This is normalized by brightness (unlike raw chroma = max-min), so a dim-but-saturated
 * pixel in an underexposed frame is compared fairly against a bright-but-saturated pixel
 * in an overexposed frame — which matters a lot across a wide-EV bracket where absolute
 * brightness varies by design. It collapses toward zero for pixels that are washed out
 * (overexposed, near-white) and is undefined/noisy right at black, which the low-clip
 * penalty below guards against (S = (max-min)/max blows up numerically as max -> 0).
 *
 * A clipping penalty is applied so that a technically "colorful" but blown-out or
 * crushed-black pixel doesn't win over a correctly exposed one from another frame.
 */
object SaturationFusion {

    data class Params(
        /** Channel values at/above this are treated as clipped highlights. */
        val highClip: Int = 250,
        /** Channel values at/below this are treated as clipped shadows. */
        val lowClip: Int = 5,
        /** Score subtracted per clipped channel (large enough to always lose to an unclipped pixel). */
        val clipPenalty: Double = 1000.0
    )

    /**
     * All bitmaps must share identical width/height (guaranteed by locking focal length
     * and framing during capture — see CameraBracketController's fx lock).
     */
    suspend fun fuse(frames: List<Bitmap>, params: Params = Params()): Bitmap = coroutineScope {
        require(frames.isNotEmpty()) { "Need at least one frame" }
        val w = frames[0].width
        val h = frames[0].height
        frames.forEach {
            require(it.width == w && it.height == h) { "All bracket frames must be the same size/framing" }
        }

        val pixelArrays = frames.map { bmp ->
            IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        }

        val out = IntArray(w * h)

        // Process row-bands in parallel; each band is independent so this scales cleanly
        // with core count without any shared mutable state.
        val bands = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val rowsPerBand = (h + bands - 1) / bands

        val jobs = (0 until bands).map { b ->
            async(Dispatchers.Default) {
                val rowStart = b * rowsPerBand
                val rowEnd = min(h, rowStart + rowsPerBand)
                for (row in rowStart until rowEnd) {
                    val rowBase = row * w
                    for (col in 0 until w) {
                        val idx = rowBase + col
                        var bestScore = Double.NEGATIVE_INFINITY
                        var bestPixel = pixelArrays[0][idx]
                        for (f in pixelArrays.indices) {
                            val p = pixelArrays[f][idx]
                            val r = (p shr 16) and 0xFF
                            val g = (p shr 8) and 0xFF
                            val bch = p and 0xFF

                            val cMax = max(r, max(g, bch))
                            val cMin = min(r, min(g, bch))
                            // HSV saturation, guarding the max==0 (pure black) singularity.
                            var score = if (cMax == 0) 0.0 else (cMax - cMin).toDouble() / cMax.toDouble()

                            if (cMax >= params.highClip) score -= params.clipPenalty
                            if (cMax <= params.lowClip) score -= params.clipPenalty

                            if (score > bestScore) {
                                bestScore = score
                                bestPixel = p
                            }
                        }
                        out[idx] = bestPixel
                    }
                }
            }
        }
        jobs.awaitAll()

        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(out, 0, w, 0, 0, w, h)
        }
    }
}
