package com.example.hdrfusion

import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.media.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.min
import kotlin.math.pow

/**
 * Minimal RAW_SENSOR -> sRGB pipeline: black-level subtraction, per-channel white
 * balance (from the metered "as-shot" gains/transform, so every bracket frame gets the
 * *same* color rendering), bilinear Bayer demosaic, sensor->sRGB color-correction matrix,
 * then the sRGB gamma. This gives the fusion stage genuine sensor bit depth (typically
 * 10-14 bits, versus the camera's baked-in 8-bit JPEG tone curve) to make its
 * per-pixel saturation comparison against — real headroom, at the cost of the ISP's own
 * noise reduction, sharpening, and lens-shading correction that a JPEG would already have
 * applied (none of that is reimplemented here).
 *
 * This does not produce or save a .dng file — DNG is a container format for RAW bytes
 * plus this same metadata, meant for external RAW processors; since the app needs
 * demosaiced RGB in-process for fusion anyway, decoding straight to a [Bitmap] skips
 * that intermediate.
 */
object RawDemosaic {

    /** Per-camera constants plus this shoot's metered white balance. */
    data class ColorPipeline(
        val whiteLevel: Int,
        val blackLevel: Int,
        val cfa: Int, // one of CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_*
        val gains: RggbChannelVector?,
        val transform: ColorSpaceTransform?
    )

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

        val range = (pipeline.whiteLevel - pipeline.blackLevel).coerceAtLeast(1)
        val gains = pipeline.gains
        val rGain = gains?.red ?: 1f
        val gEvenGain = gains?.greenEven ?: 1f
        val gOddGain = gains?.greenOdd ?: 1f
        val bGain = gains?.blue ?: 1f
        val matrix = pipeline.transform?.toFloatMatrix()

        fun sampleNorm(r: Int, c: Int): Float {
            val v = raw[(top + r) * fullWidth + (left + c)].toInt() and 0xFFFF
            val normalized = ((v - pipeline.blackLevel).toFloat() / range).coerceIn(0f, 1f)
            val gain = when (cfaChannelAt(pipeline.cfa, r, c)) {
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
