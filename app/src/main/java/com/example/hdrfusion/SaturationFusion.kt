package com.example.hdrfusion

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Fuses a bracket of same-framing exposures into one image using a softmax-weighted blend
 * of saturation scores (a smooth approximation of the original per-pixel argmax), with a
 * per-pixel ghost guard that falls back to the temporal median when frames disagree far
 * more than sensor noise can explain — the signature of a moving subject rather than an
 * exposure difference.
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
 *
 * ## Why softmax instead of hard argmax
 * A hard argmax picks a single frame's pixel value at every location independently. Where
 * the winning frame flips between neighboring pixels (common at saturation boundaries —
 * e.g. the edge of a red flower against green leaves, where each frame's saturation peak is
 * slightly different) the output can show a visible seam: a one-pixel-wide discontinuity in
 * brightness or color where the source frame changed. Softmax blending computes a weight per
 * frame from the same scores (weight_f = exp(score_f / T) / sum_g exp(score_g / T)) and
 * blends pixel *values*, not just picks one. At low temperature this converges to the same
 * winner as hard argmax; near a tie it blends smoothly instead of flipping abruptly. It does
 * not fix everything a proper multi-resolution (Laplacian pyramid) blend would — it is still
 * a single-resolution, per-pixel operation — but it removes the single-pixel seam artifact
 * at negligible extra cost (this was already computing every frame's score per pixel).
 *
 * ## Why a ghost guard, and why it can only be a guard, not a fix
 * If a subject moves between frames, per-pixel selection (soft or hard) can source
 * different pixels from different frames for what should be one coherent object — outputting
 * a rough sketch of the object in each position it passed through ("ghosting"). Detecting
 * *that* a pixel is a ghost candidate is possible without full optical flow, but the naive
 * version — comparing raw luma across frames — does not work here: bracket frames have
 * *intentionally* different exposures, so a perfectly static pixel's raw brightness swings by
 * design (that is the entire point of bracketing), which would swamp any motion signal.
 * Instead, each frame's luma is first normalized by that frame's own mean scene brightness
 * (computed once per frame). For a static scene, a global exposure change scales every pixel
 * roughly proportionally, so this normalized value stays roughly constant across frames
 * regardless of which EV step captured it; a pixel whose *content* actually changed (a moving
 * subject swapping in a different-colored/brighter object) breaks that proportionality and
 * shows up as elevated variance in the normalized values. Where that variance is anomalously
 * high, this guard uses the temporal median instead of the softmax blend — median rejects a
 * minority of outlier (ghost) frames in favor of whatever value most frames agree on, the
 * standard cheap deghosting trick. This is still an approximation: sensor/tone-curve gamma
 * means brightness doesn't scale *perfectly* linearly with exposure, so the normalization is
 * imperfect, and it cannot identify the object, correct its color, or distinguish "subject
 * moved" from "gamma nonlinearity" for extreme EV spreads — real deghosting needs per-object
 * motion estimation (optical flow + segmentation), a fundamentally different, much larger
 * algorithm. This guard reduces, but does not eliminate, ghost visibility for small/fast
 * subjects against an otherwise static scene; a subject filling a large fraction of the
 * frame, or moving slowly enough to look "real" in several bracket steps, still shows through.
 */
object SaturationFusion {

    data class Params(
        /** Channel values at/above this are treated as clipped highlights. */
        val highClip: Int = 250,
        /** Channel values at/below this are treated as clipped shadows. */
        val lowClip: Int = 5,
        /** Score subtracted per clipped channel (large enough to always lose to an unclipped pixel). */
        val clipPenalty: Double = 1000.0,
        /**
         * Softmax temperature over saturation scores. Lower = closer to hard argmax (sharper
         * winner-take-all); higher = smoother blending across frames. ~0.05 keeps the same
         * frame choice as argmax almost everywhere while smoothing genuine near-ties.
         */
        val softmaxTemperature: Double = 0.05,
        /**
         * Coefficient of variation (stddev / mean) of each pixel's *exposure-normalized* luma
         * across frames, above which a pixel is treated as a ghost candidate and resolved by
         * temporal median instead of the softmax blend. Normalization (see class doc) removes
         * the deliberate brightness swing from bracketing; what's left is mostly sensor noise
         * (a few percent) plus gamma-curve nonlinearity (rarely more than ~10-15%) for a truly
         * static pixel. A genuine content change (a different object swapped in) typically
         * pushes this well past 25%.
         */
        val ghostNormalizedVariationThreshold: Double = 0.25
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
        val n = pixelArrays.size

        // Per-frame mean luma, used to normalize away the deliberate bracket exposure swing
        // before ghost detection (see class doc on why raw luma variance can't be used here).
        val frameMeanLuma = DoubleArray(n) { f ->
            var sum = 0.0
            val pixels = pixelArrays[f]
            for (p in pixels) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                sum += 0.299 * r + 0.587 * g + 0.114 * b
            }
            (sum / pixels.size).coerceAtLeast(1.0)
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
                val scores = DoubleArray(n)
                val lumas = DoubleArray(n)

                for (row in rowStart until rowEnd) {
                    val rowBase = row * w
                    for (col in 0 until w) {
                        val idx = rowBase + col

                        var bestScore = Double.NEGATIVE_INFINITY
                        for (f in 0 until n) {
                            val p = pixelArrays[f][idx]
                            val r = (p shr 16) and 0xFF
                            val g = (p shr 8) and 0xFF
                            val bch = p and 0xFF

                            val cMax = max(r, max(g, bch))
                            val cMin = min(r, min(g, bch))
                            var score = if (cMax == 0) 0.0 else (cMax - cMin).toDouble() / cMax.toDouble()
                            if (cMax >= params.highClip) score -= params.clipPenalty
                            if (cMax <= params.lowClip) score -= params.clipPenalty

                            scores[f] = score
                            // Normalize by this frame's own mean brightness so the deliberate
                            // bracket exposure swing doesn't get mistaken for pixel content
                            // changing — see class doc.
                            lumas[f] = (0.299 * r + 0.587 * g + 0.114 * bch) / frameMeanLuma[f]
                            if (score > bestScore) bestScore = score
                        }

                        // Ghost check: coefficient of variation of exposure-normalized luma.
                        val meanNormLuma = lumas.sum() / n
                        var varSum = 0.0
                        for (f in 0 until n) {
                            val d = lumas[f] - meanNormLuma
                            varSum += d * d
                        }
                        val normStdDev = sqrt(varSum / n)
                        val coeffOfVariation = if (meanNormLuma > 1e-6) normStdDev / meanNormLuma else 0.0

                        out[idx] = if (coeffOfVariation > params.ghostNormalizedVariationThreshold) {
                            medianPixel(pixelArrays, idx, n)
                        } else {
                            softmaxBlend(pixelArrays, idx, scores, bestScore, params.softmaxTemperature, n)
                        }
                    }
                }
            }
        }
        jobs.awaitAll()

        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(out, 0, w, 0, 0, w, h)
        }
    }

    /** Softmax-weighted RGB blend across frames at one pixel index, scores shifted by their max for numerical stability. */
    private fun softmaxBlend(
        pixelArrays: List<IntArray>,
        idx: Int,
        scores: DoubleArray,
        maxScore: Double,
        temperature: Double,
        n: Int
    ): Int {
        var weightSum = 0.0
        var r = 0.0; var g = 0.0; var b = 0.0
        for (f in 0 until n) {
            val w = exp((scores[f] - maxScore) / temperature)
            weightSum += w
            val p = pixelArrays[f][idx]
            r += w * ((p shr 16) and 0xFF)
            g += w * ((p shr 8) and 0xFF)
            b += w * (p and 0xFF)
        }
        val invSum = if (weightSum > 0) 1.0 / weightSum else 1.0 / n
        val rr = (r * invSum).toInt().coerceIn(0, 255)
        val gg = (g * invSum).toInt().coerceIn(0, 255)
        val bb = (b * invSum).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
    }

    /** Per-channel temporal median across frames at one pixel index — rejects minority ghost frames. */
    private fun medianPixel(pixelArrays: List<IntArray>, idx: Int, n: Int): Int {
        val rs = IntArray(n); val gs = IntArray(n); val bs = IntArray(n)
        for (f in 0 until n) {
            val p = pixelArrays[f][idx]
            rs[f] = (p shr 16) and 0xFF
            gs[f] = (p shr 8) and 0xFF
            bs[f] = p and 0xFF
        }
        rs.sort(); gs.sort(); bs.sort()
        val mid = n / 2
        val r = if (n % 2 == 1) rs[mid] else (rs[mid - 1] + rs[mid]) / 2
        val g = if (n % 2 == 1) gs[mid] else (gs[mid - 1] + gs[mid]) / 2
        val b = if (n % 2 == 1) bs[mid] else (bs[mid - 1] + bs[mid]) / 2
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
