package com.example.hdrfusion

import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.BlackLevelPattern
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.LensShadingMap
import android.hardware.camera2.params.RggbChannelVector
import android.media.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.min
import kotlin.math.pow

/**
 * Minimal RAW_SENSOR -> sRGB pipeline: black-level subtraction (per-channel, using each
 * frame's own dynamic black level when the camera reports one), lens-shading (vignetting)
 * correction, per-channel white balance (from the metered "as-shot" gains/transform, so
 * every bracket frame gets the *same* color rendering), bilinear Bayer demosaic,
 * sensor->sRGB color-correction matrix, then the sRGB gamma. This gives the fusion stage
 * genuine sensor bit depth (typically 10-14 bits, versus the camera's baked-in 8-bit JPEG
 * tone curve) to make its per-pixel saturation comparison against — real headroom, at the
 * cost of the ISP's own noise reduction and sharpening that a JPEG would already have
 * applied (neither is reimplemented here; see LIMITATIONS.md).
 *
 * This does not produce or save a .dng file — DNG is a container format for RAW bytes
 * plus this same metadata, meant for external RAW processors; since the app needs
 * demosaiced RGB in-process for fusion anyway, decoding straight to a [Bitmap] skips
 * that intermediate.
 */
object RawDemosaic {

    /**
     * Per-camera constants plus this shoot's metered white balance.
     *
     * [blackLevels] is per-CFA-channel (indexed the same way as [cfaChannelAt]: 0=R,
     * 1=green-on-a-red-row, 2=green-on-a-blue-row, 3=B) rather than one shared value,
     * since a sensor's four color channels can each drift by a different offset —
     * [withDynamicBlackLevel] rebuilds this per frame from that capture's own
     * `SENSOR_DYNAMIC_BLACK_LEVEL`, falling back to the static per-camera
     * `SENSOR_BLACK_LEVEL_PATTERN` ([blackLevelsFromStaticPattern]) when a frame doesn't
     * report one.
     */
    data class ColorPipeline(
        val whiteLevel: Int,
        val blackLevels: FloatArray,
        val cfa: Int, // one of CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_*
        val gains: RggbChannelVector?,
        val transform: ColorSpaceTransform?,
        val shadingMap: LensShadingMap? = null
    )

    /** Static per-camera fallback, from `CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN`. */
    fun blackLevelsFromStaticPattern(cfa: Int, pattern: BlackLevelPattern?): FloatArray {
        if (pattern == null) return floatArrayOf(64f, 64f, 64f, 64f)
        return blackLevelsFromQuad(
            cfa,
            pattern.getOffsetForIndex(0, 0).toFloat(),
            pattern.getOffsetForIndex(1, 0).toFloat(),
            pattern.getOffsetForIndex(0, 1).toFloat(),
            pattern.getOffsetForIndex(1, 1).toFloat()
        )
    }

    /**
     * Returns [pipeline] with its black levels replaced by this frame's own
     * `CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL` (tracks sensor drift, e.g. with
     * temperature, frame-to-frame) when the capture reported one, else [pipeline]
     * unchanged (already carrying the static per-camera fallback).
     */
    fun withDynamicBlackLevel(pipeline: ColorPipeline, dynamic: FloatArray?): ColorPipeline {
        if (dynamic == null || dynamic.size != 4) return pipeline
        return pipeline.copy(
            blackLevels = blackLevelsFromQuad(pipeline.cfa, dynamic[0], dynamic[1], dynamic[2], dynamic[3])
        )
    }

    /**
     * `SENSOR_BLACK_LEVEL_PATTERN`/`SENSOR_DYNAMIC_BLACK_LEVEL` both report their four
     * values in raster order over the top-left 2x2 of the CFA — (0,0), (1,0), (0,1),
     * (1,1) as (col,row) — which this maps onto this file's own R/Geven/Godd/B channel
     * numbering via [cfaChannelAt] so the two stay consistent regardless of CFA layout.
     */
    private fun blackLevelsFromQuad(cfa: Int, q00: Float, q10: Float, q01: Float, q11: Float): FloatArray {
        val levels = FloatArray(4)
        levels[cfaChannelAt(cfa, 0, 0)] = q00
        levels[cfaChannelAt(cfa, 0, 1)] = q10
        levels[cfaChannelAt(cfa, 1, 0)] = q01
        levels[cfaChannelAt(cfa, 1, 1)] = q11
        return levels
    }

    /**
     * Decodes one RAW_SENSOR [Image] into a display-referred ARGB_8888 [Bitmap], cropped to
     * [activeArray] (the sensor's optically-black calibration border is excluded).
     * Does not close [image] — the caller owns that.
     */
    suspend fun demosaic(image: Image, activeArray: Rect, pipeline: ColorPipeline): Bitmap = coroutineScope {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val fullWidth = image.width
        val fullHeight = image.height

        val raw = ShortArray(fullWidth * fullHeight)
        val rowBytes = ByteArray(rowStride)
        for (row in 0 until fullHeight) {
            buffer.position(row * rowStride)
            buffer.get(rowBytes, 0, rowStride)
            var srcIdx = 0
            var dstIdx = row * fullWidth
            for (col in 0 until fullWidth) {
                val lo = rowBytes[srcIdx].toInt() and 0xFF
                val hi = rowBytes[srcIdx + 1].toInt() and 0xFF
                raw[dstIdx] = ((hi shl 8) or lo).toShort()
                srcIdx += pixelStride
                dstIdx++
            }
        }

        // Bayer phase is preserved by the active-array crop on every device that reports
        // one (an even-aligned crop is required for the CFA pattern to still line up) —
        // otherwise this whole per-pixel parity scheme would need a phase correction.
        val left = activeArray.left.coerceIn(0, fullWidth - 1)
        val top = activeArray.top.coerceIn(0, fullHeight - 1)
        val w = activeArray.width().coerceAtMost(fullWidth - left)
        val h = activeArray.height().coerceAtMost(fullHeight - top)

        val range = FloatArray(4) { ch -> (pipeline.whiteLevel - pipeline.blackLevels[ch]).coerceAtLeast(1f) }
        val gains = pipeline.gains
        val rGain = gains?.red ?: 1f
        val gEvenGain = gains?.greenEven ?: 1f
        val gOddGain = gains?.greenOdd ?: 1f
        val bGain = gains?.blue ?: 1f
        val matrix = pipeline.transform?.toFloatMatrix()

        // Bilinear demosaic samples each raw pixel from several call sites (its own output
        // site plus neighbor/diagonal averaging for the other two channels at nearby
        // sites) — up to ~9x per output pixel. The shading-map lookup itself is a 4-point
        // bilinear interpolation, so evaluating it inline in `sampleNorm` would redo that
        // work up to 9x per raw pixel; precomputing it once per raw pixel here keeps the
        // per-sample path to a single array read, same as the black-level/white-balance data.
        val shadingGain: FloatArray? = pipeline.shadingMap?.let { map ->
            FloatArray(w * h) { i ->
                val r = i / w
                val c = i % w
                shadingGainAt(map, cfaChannelAt(pipeline.cfa, r, c), r.toFloat() / h, c.toFloat() / w)
            }
        }

        fun sampleNorm(r: Int, c: Int): Float {
            val channel = cfaChannelAt(pipeline.cfa, r, c)
            val v = raw[(top + r) * fullWidth + (left + c)].toInt() and 0xFFFF
            var normalized = ((v - pipeline.blackLevels[channel]) / range[channel]).coerceIn(0f, 1f)
            if (shadingGain != null) {
                normalized *= shadingGain[r * w + c]
            }
            val gain = when (channel) {
                0 -> rGain
                1 -> gEvenGain
                2 -> gOddGain
                else -> bGain
            }
            return (normalized * gain).coerceIn(0f, 1f)
        }

        val outArgb = IntArray(w * h)
        val bands = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val rowsPerBand = (h + bands - 1) / bands
        val jobs = (0 until bands).map { b ->
            async(Dispatchers.Default) {
                val rowStart = b * rowsPerBand
                val rowEnd = min(h, rowStart + rowsPerBand)
                for (r in rowStart until rowEnd) {
                    for (c in 0 until w) {
                        val rr: Float
                        val gg: Float
                        val bb: Float
                        when (val channel = cfaChannelAt(pipeline.cfa, r, c)) {
                            0 -> { // red site
                                rr = sampleNorm(r, c)
                                gg = neighborAvg4(::sampleNorm, r, c, w, h)
                                bb = diagonalAvg4(::sampleNorm, r, c, w, h)
                            }
                            3 -> { // blue site
                                bb = sampleNorm(r, c)
                                gg = neighborAvg4(::sampleNorm, r, c, w, h)
                                rr = diagonalAvg4(::sampleNorm, r, c, w, h)
                            }
                            else -> { // green site: channel 1 = same row as red, channel 2 = same row as blue
                                gg = sampleNorm(r, c)
                                if (channel == 1) {
                                    rr = horizontalAvg2(::sampleNorm, r, c, w)
                                    bb = verticalAvg2(::sampleNorm, r, c, h)
                                } else {
                                    bb = horizontalAvg2(::sampleNorm, r, c, w)
                                    rr = verticalAvg2(::sampleNorm, r, c, h)
                                }
                            }
                        }

                        var sr = rr; var sg = gg; var sb = bb
                        if (matrix != null) {
                            sr = matrix[0][0] * rr + matrix[0][1] * gg + matrix[0][2] * bb
                            sg = matrix[1][0] * rr + matrix[1][1] * gg + matrix[1][2] * bb
                            sb = matrix[2][0] * rr + matrix[2][1] * gg + matrix[2][2] * bb
                        }

                        val R = srgbEncode(sr)
                        val G = srgbEncode(sg)
                        val B = srgbEncode(sb)
                        outArgb[r * w + c] = (0xFF shl 24) or (R shl 16) or (G shl 8) or B
                    }
                }
            }
        }
        jobs.awaitAll()

        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(outArgb, 0, w, 0, 0, w, h)
        }
    }

    /** Row-major 3x3 float matrix: out[row] = sum_col matrix[row][col] * in[col]. */
    private fun ColorSpaceTransform.toFloatMatrix(): Array<FloatArray> = Array(3) { row ->
        FloatArray(3) { col ->
            val rational = getElement(col, row)
            rational.numerator.toFloat() / rational.denominator.toFloat()
        }
    }

    /** 0=R, 1=G-on-a-red-row, 2=G-on-a-blue-row, 3=B, from CFA pattern + parity of (row,col). */
    private fun cfaChannelAt(cfa: Int, r: Int, c: Int): Int {
        val evenRow = r % 2 == 0
        val evenCol = c % 2 == 0
        return when (cfa) {
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB ->
                if (evenRow) { if (evenCol) 0 else 1 } else { if (evenCol) 2 else 3 }
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG ->
                if (evenRow) { if (evenCol) 1 else 0 } else { if (evenCol) 3 else 2 }
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG ->
                if (evenRow) { if (evenCol) 2 else 3 } else { if (evenCol) 0 else 1 }
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR ->
                if (evenRow) { if (evenCol) 3 else 2 } else { if (evenCol) 1 else 0 }
            else -> if (evenRow) { if (evenCol) 0 else 1 } else { if (evenCol) 2 else 3 } // assume RGGB
        }
    }

    /**
     * Vignetting (lens-shading) correction gain for one raw-Bayer sample, via bilinear
     * interpolation over the camera-reported shading grid. [rowFrac]/[colFrac] are this
     * sample's position within the active array as a 0..1 fraction — the grid is much
     * coarser than the sensor (typically single digits to a few dozen cells per axis) and
     * is defined to cover the *pre-correction* active array, which is typically a few
     * pixels larger than the active array this file crops to; treating the two as the same
     * region here is a small approximation, not a phase error (unlike the CFA parity,
     * which does need an exact crop).
     */
    private fun shadingGainAt(map: LensShadingMap, channel: Int, rowFrac: Float, colFrac: Float): Float {
        val cols = map.columnCount
        val rows = map.rowCount
        if (cols < 1 || rows < 1) return 1f

        val fx = (colFrac.coerceIn(0f, 1f) * (cols - 1))
        val fy = (rowFrac.coerceIn(0f, 1f) * (rows - 1))
        val x0 = fx.toInt().coerceIn(0, cols - 1)
        val y0 = fy.toInt().coerceIn(0, rows - 1)
        val x1 = (x0 + 1).coerceAtMost(cols - 1)
        val y1 = (y0 + 1).coerceAtMost(rows - 1)
        val tx = fx - x0
        val ty = fy - y0

        val g00 = map.getGainFactor(channel, x0, y0)
        val g10 = map.getGainFactor(channel, x1, y0)
        val g01 = map.getGainFactor(channel, x0, y1)
        val g11 = map.getGainFactor(channel, x1, y1)
        val top = g00 + (g10 - g00) * tx
        val bottom = g01 + (g11 - g01) * tx
        return top + (bottom - top) * ty
    }

    private inline fun neighborAvg4(sample: (Int, Int) -> Float, r: Int, c: Int, w: Int, h: Int): Float {
        var sum = 0f; var n = 0
        if (r > 0) { sum += sample(r - 1, c); n++ }
        if (r < h - 1) { sum += sample(r + 1, c); n++ }
        if (c > 0) { sum += sample(r, c - 1); n++ }
        if (c < w - 1) { sum += sample(r, c + 1); n++ }
        return if (n > 0) sum / n else 0f
    }

    private inline fun diagonalAvg4(sample: (Int, Int) -> Float, r: Int, c: Int, w: Int, h: Int): Float {
        var sum = 0f; var n = 0
        if (r > 0 && c > 0) { sum += sample(r - 1, c - 1); n++ }
        if (r > 0 && c < w - 1) { sum += sample(r - 1, c + 1); n++ }
        if (r < h - 1 && c > 0) { sum += sample(r + 1, c - 1); n++ }
        if (r < h - 1 && c < w - 1) { sum += sample(r + 1, c + 1); n++ }
        return if (n > 0) sum / n else 0f
    }

    private inline fun horizontalAvg2(sample: (Int, Int) -> Float, r: Int, c: Int, w: Int): Float {
        var sum = 0f; var n = 0
        if (c > 0) { sum += sample(r, c - 1); n++ }
        if (c < w - 1) { sum += sample(r, c + 1); n++ }
        return if (n > 0) sum / n else sample(r, c)
    }

    private inline fun verticalAvg2(sample: (Int, Int) -> Float, r: Int, c: Int, h: Int): Float {
        var sum = 0f; var n = 0
        if (r > 0) { sum += sample(r - 1, c); n++ }
        if (r < h - 1) { sum += sample(r + 1, c); n++ }
        return if (n > 0) sum / n else sample(r, c)
    }

    /** sRGB opto-electronic transfer function, encoding a linear 0..1 value to an 8-bit channel. */
    private fun srgbEncode(linear: Float): Int {
        val x = linear.coerceIn(0f, 1f)
        val encoded = if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f
        return (encoded.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
    }
}
