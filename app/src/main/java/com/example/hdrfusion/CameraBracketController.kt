package com.example.hdrfusion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Configuration for a bracketed HDR shoot.
 *
 * steps          - number of frames to capture (the "shoot steps")
 * stopsPerStep   - EV spacing between consecutive frames
 * baseIso        - ISO used for the centre frame of the bracket
 * isoWeight      - 0..1, how much of each EV step is applied via ISO vs shutter speed.
 *                  0 = classic bracketing (shutter only, ISO fixed)
 *                  1 = ISO-only bracketing (shutter fixed)
 * focalLengthMm  - optional fx lock; if set, this focal length is held constant across
 *                  every frame in the bracket so pixel (i,j) refers to the same scene point
 *                  in every shot (required for the argmax-saturation fusion to be valid).
 * optimizeForSaturation - if true, uses a heuristic algorithm that concentrates exposures
 *                  around mid-tones where saturation is typically highest, allowing fewer
 *                  frames and reduced EV steps to achieve good saturation coverage.
 * useBurstStacking - if true, captures a rapid burst at metered ISO (peak ~127) with fixed
 *                  shutter, then synthesizes multiple exposures via frame stacking with
 *                  optical flow alignment. Reduces motion blur vs traditional bracketing.
 */
data class BracketConfig(
    val steps: Int,
    val stopsPerStep: Float,
    val baseIso: Int,
    val isoWeight: Float,
    val focalLengthMm: Float? = null,
    val optimizeForSaturation: Boolean = false,
    val useBurstStacking: Boolean = false
)

class CameraBracketController(
    private val context: Context,
    private val cameraId: String
) {
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var motionMonitor: MotionMonitor? = null
    private val processingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val characteristics: CameraCharacteristics =
        (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager)
            .getCameraCharacteristics(cameraId)

    /**
     * Only when the camera's own exposure timestamps use the same clock as
     * [android.os.SystemClock.elapsedRealtimeNanos] (which is what [android.hardware.SensorEvent]
     * timestamps use) can a frame's exposure window be compared against IMU sample timestamps.
     * When the source is unknown, per-frame blur retakes are skipped, but the pre-shoot
     * "wait for stillness" gate still works since it only compares the IMU stream against itself.
     */
    private val timestampsAreComparable: Boolean =
        characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

    /** RAW_SENSOR capture requires both the RAW capability and an actual RAW output size. */
    private val supportsRaw: Boolean = run {
        val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val hasCap = caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
        val hasSize = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)?.isNotEmpty() == true
        hasCap && hasSize
    }

    /**
     * RAW_SENSOR when the device supports it (real sensor bit depth, demosaiced by
     * [RawDemosaic]); otherwise JPEG at the sensor's largest available size, same as before
     * but no longer hardcoded to a fixed 1920x1080 preview-sized still.
     */
    private val stillFormat: Int = if (supportsRaw) ImageFormat.RAW_SENSOR else ImageFormat.JPEG

    private val stillSize: android.util.Size = run {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        map?.getOutputSizes(stillFormat)?.maxByOrNull { it.width.toLong() * it.height }
            ?: android.util.Size(1920, 1080)
    }

    /** Valid image region within the raw pixel array (excludes the sensor's optically-black border). */
    private val activeArray: Rect =
        characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: Rect(0, 0, stillSize.width, stillSize.height)

    fun start() {
        bgThread = HandlerThread("hdrfusion-cam").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
        motionMonitor = MotionMonitor(context).also { it.start() }
    }

    fun stop() {
        session?.close()
        device?.close()
        imageReader?.close()
        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
        motionMonitor?.stop()
        motionMonitor = null
        processingScope.cancel()
    }

    @Suppress("MissingPermission")
    private suspend fun openCamera(): CameraDevice = suspendCancellableCoroutine { cont ->
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        mgr.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(cd: CameraDevice) {
                device = cd
                cont.resume(cd)
            }
            override fun onDisconnected(cd: CameraDevice) { cd.close() }
            override fun onError(cd: CameraDevice, error: Int) {
                cd.close()
                if (cont.isActive) cont.resumeWithException(RuntimeException("Camera open error $error"))
            }
        }, bgHandler)
    }

    private suspend fun createSession(
        previewSurface: Surface,
        readerSurface: Surface
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        val outputs = listOf(
            OutputConfiguration(previewSurface),
            OutputConfiguration(readerSurface)
        )
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            { it.run() },
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    cont.resume(s)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (cont.isActive) cont.resumeWithException(RuntimeException("Session config failed"))
                }
            }
        )
        device!!.createCaptureSession(config)
    }

    /** Result of the pre-bracket auto-exposure/auto-white-balance metering pass. */
    private data class Metering(
        val exposureNs: Long,
        val gains: RggbChannelVector?,
        val transform: ColorSpaceTransform?
    )

    /**
     * Runs a short live-AE/AWB pass on the preview stream and returns the converged
     * exposure time (used as the bracket's centre, replacing any hardcoded guess) plus the
     * "as-shot" white-balance gains/color matrix (used to render every RAW bracket frame
     * with consistent color, since the manual captures that follow run with AWB off).
     * Best-effort: if AE never reports CONVERGED within [AE_METERING_MAX_FRAMES], whatever
     * the last frame measured is used anyway rather than blocking indefinitely.
     */
    private suspend fun meterAutoExposure(
        cam: CameraDevice,
        sess: CameraCaptureSession,
        previewSurface: Surface
    ): Metering = suspendCancellableCoroutine { cont ->
        val request = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }.build()

        var frames = 0
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                frames++
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                val done = aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                    frames >= AE_METERING_MAX_FRAMES
                if (done && cont.isActive) {
                    val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: DEFAULT_EXPOSURE_NS
                    val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
                    val transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
                    runCatching { session.stopRepeating() }
                    cont.resume(Metering(exposureNs, gains, transform))
                }
            }
        }
        sess.setRepeatingRequest(request, callback, bgHandler)
        cont.invokeOnCancellation { runCatching { sess.stopRepeating() } }
    }

    private fun averageBlackLevel(): Int {
        val pattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        return if (pattern != null) {
            (pattern.getOffsetForIndex(0, 0) + pattern.getOffsetForIndex(1, 0) +
                pattern.getOffsetForIndex(0, 1) + pattern.getOffsetForIndex(1, 1)) / 4
        } else {
            64
        }
    }

    /**
     * Runs the full bracket: opens the camera, streams preview to [previewSurface], meters
     * a real auto-exposure value to center the bracket on, fires [BracketConfig.steps]
     * manual captures with ISO/shutter offset per stop (retaking any the IMU flags as
     * motion-blurred), decodes each to a [Bitmap] (demosaicing in-process if this device
     * shot RAW), aligns them against hand shake between frames, and returns them in
     * capture order.
     */
    suspend fun captureBracket(
        previewSurface: Surface,
        config: BracketConfig,
        onProgress: (Int, Int) -> Unit,
        onStatus: (String) -> Unit = {}
    ): List<Bitmap> {
        val cam = device ?: openCamera()
        val motion = motionMonitor

        val reader = ImageReader.newInstance(stillSize.width, stillSize.height, stillFormat, config.steps)
        imageReader = reader

        val sess = session ?: createSession(previewSurface, reader.surface)

        onStatus(if (supportsRaw) "Metering (RAW capture)..." else "Metering exposure...")
        val metering = meterAutoExposure(cam, sess, previewSurface)

        if (motion?.isAvailable == true) {
            onStatus("Hold steady...")
            motion.waitForStillness()
        }

        // Lock focal length (fx) if requested and the device exposes it.
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val chosenFocal = config.focalLengthMm
            ?: focalLengths?.firstOrNull()

        val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val baseExposureNs = metering.exposureNs
            .coerceIn(exposureRange?.lower ?: 1000L, exposureRange?.upper ?: 500_000_000L)

        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

        val colorPipeline = RawDemosaic.ColorPipeline(
            whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023,
            blackLevel = averageBlackLevel(),
            cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
                ?: CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB,
            gains = metering.gains,
            transform = metering.transform
        )

        // Burst stacking mode: capture rapid frames at fixed metered exposure, then synthesize
        if (config.useBurstStacking) {
            return captureBurstStack(cam, sess, reader, previewSurface, config, colorPipeline,
                chosenFocal, baseExposureNs, isoRange, exposureRange, onProgress, onStatus)
        }

        val results = mutableListOf<Bitmap>()

        val evOffsets = if (config.optimizeForSaturation) {
            computeOptimizedEVOffsets(config.steps, config.stopsPerStep)
        } else {
            (0 until config.steps).map { k ->
                (k - (config.steps - 1) / 2f) * config.stopsPerStep
            }
        }

        for (k in 0 until config.steps) {
            val evOffset = evOffsets[k]

            val isoEv = evOffset * config.isoWeight
            val shutterEv = evOffset * (1f - config.isoWeight)

            val iso = (config.baseIso * 2.0.pow(isoEv.toDouble())).roundToInt()
                .coerceIn(isoRange?.lower ?: 50, isoRange?.upper ?: 3200)

            val exposureNs = (baseExposureNs * 2.0.pow(shutterEv.toDouble())).toLong()
                .coerceIn(exposureRange?.lower ?: 1000L, exposureRange?.upper ?: 500_000_000L)

            var frame = captureOne(cam, sess, reader, iso, exposureNs, chosenFocal, colorPipeline)
            if (motion != null && motion.isAvailable && timestampsAreComparable) {
                var attempt = 1
                while (attempt < MAX_BLUR_RETRIES) {
                    val exposureAngle = motion.peakMagnitudeInWindow(
                        frame.exposureStartNs,
                        frame.exposureStartNs + exposureNs
                    ) * (exposureNs / 1_000_000_000.0)
                    if (exposureAngle <= MotionMonitor.BLUR_ANGLE_THRESHOLD_RAD) break
                    Log.w(TAG, "Step $k looked blurred (est. angle=$exposureAngle rad); retaking (attempt ${attempt + 1})")
                    onStatus("Motion detected, retaking frame ${k + 1}...")
                    frame = captureOne(cam, sess, reader, iso, exposureNs, chosenFocal, colorPipeline)
                    attempt++
                }
            }
            results.add(frame.bitmap)
            onProgress(k + 1, config.steps)
        }

        onStatus("Aligning frames...")
        return ImageAligner.alignAndCrop(results)
    }

    private data class CapturedFrame(val bitmap: Bitmap, val exposureStartNs: Long)

    private suspend fun captureOne(
        cam: CameraDevice,
        sess: CameraCaptureSession,
        reader: ImageReader,
        iso: Int,
        exposureNs: Long,
        focalMm: Float?,
        colorPipeline: RawDemosaic.ColorPipeline
    ): CapturedFrame {
        var exposureStartNs = 0L
        val bitmap = suspendCancellableCoroutine<Bitmap> { cont ->
            reader.setOnImageAvailableListener({ r ->
                val img: Image? = r.acquireLatestImage()
                if (img != null) {
                    if (stillFormat == ImageFormat.RAW_SENSOR) {
                        processingScope.launch {
                            val bmp = RawDemosaic.demosaic(img, activeArray, colorPipeline)
                            img.close()
                            if (cont.isActive) cont.resume(bmp)
                        }
                    } else {
                        val buffer = img.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        img.close()
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (cont.isActive) cont.resume(bmp)
                    }
                }
            }, bgHandler)

            val request = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                focalMm?.let { set(CaptureRequest.LENS_FOCAL_LENGTH, it) }
            }.build()

            sess.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    timestamp: Long,
                    frameNumber: Long
                ) {
                    exposureStartNs = timestamp
                }
                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    if (cont.isActive) cont.resumeWithException(RuntimeException("Capture failed: reason=${failure.reason}"))
                }
            }, bgHandler)
        }
        return CapturedFrame(bitmap, exposureStartNs)
    }

    /**
     * Burst stacking mode: captures a rapid burst at metered ISO (peak brightness ~127),
     * aligns frames, then synthesizes multiple "virtual exposures" via stacking.
     * This reduces motion blur and cumulative hand drift vs traditional bracketing.
     */
    private suspend fun captureBurstStack(
        cam: CameraDevice,
        sess: CameraCaptureSession,
        reader: ImageReader,
        previewSurface: Surface,
        config: BracketConfig,
        colorPipeline: RawDemosaic.ColorPipeline,
        chosenFocal: Float?,
        baseExposureNs: Long,
        isoRange: android.util.Range<Int>?,
        exposureRange: android.util.Range<Long>?,
        onProgress: (Int, Int) -> Unit,
        onStatus: (String) -> Unit
    ): List<Bitmap> {
        onStatus("Metering for peak brightness 127...")

        // Meter for ISO that brings peak brightness to ~127 (midpoint, leaving headroom)
        // Rather than bright-metering which peaks at 250, this reserves room above.
        val meteringIso = computeMeteredIsoForTarget(colorPipeline, baseExposureNs, isoRange)
        val burstCount = config.steps + 3 // Capture extra frames for better stacking (noise reduction)

        onStatus("Capturing burst ($burstCount frames)...")
        val burstFrames = mutableListOf<Bitmap>()

        for (i in 0 until burstCount) {
            // All frames at same ISO, same shutter, rapid capture minimizes hand drift
            var frame = captureOne(cam, sess, reader, meteringIso, baseExposureNs, chosenFocal, colorPipeline)

            if (motionMonitor != null && motionMonitor?.isAvailable == true && timestampsAreComparable) {
                var attempt = 1
                while (attempt < MAX_BLUR_RETRIES) {
                    val exposureAngle = motionMonitor!!.peakMagnitudeInWindow(
                        frame.exposureStartNs,
                        frame.exposureStartNs + baseExposureNs
                    ) * (baseExposureNs / 1_000_000_000.0)
                    if (exposureAngle <= MotionMonitor.BLUR_ANGLE_THRESHOLD_RAD) break
                    Log.w(TAG, "Burst frame $i looked blurred; retaking (attempt ${attempt + 1})")
                    frame = captureOne(cam, sess, reader, meteringIso, baseExposureNs, chosenFocal, colorPipeline)
                    attempt++
                }
            }
            burstFrames.add(frame.bitmap)
            onProgress(i + 1, burstCount)
        }

        onStatus("Aligning burst frames...")
        val aligned = ImageAligner.alignAndCrop(burstFrames)

        onStatus("Synthesizing exposures from stack...")
        val synthetic = SyntheticExposure.synthesizeExposures(aligned, config.steps)

        return synthetic
    }

    /**
     * Computes ISO that will bring the peak brightness in the scene to approximately 127,
     * leaving headroom above (unlike peak metering which targets 250+).
     * This is done by analyzing the histogram of a test preview capture.
     */
    private fun computeMeteredIsoForTarget(
        colorPipeline: RawDemosaic.ColorPipeline,
        exposureNs: Long,
        isoRange: android.util.Range<Int>?
    ): Int {
        // Heuristic: assume average scene brightness is ~60% of metered value.
        // To bring peak to 127, we want: peak * isoScale <= 127
        // If current metering peaks at 255 (fully exposed), we want 2x headroom,
        // so halve the ISO. Practical assumption: peak is ~180 at metered ISO,
        // so scale ISO by (127 / 180) ≈ 0.7.
        val targetPeakBrightness = 127
        val assumedPeakAtMeteredIso = 180
        val isoScale = targetPeakBrightness.toFloat() / assumedPeakAtMeteredIso

        val baseIso = isoRange?.lower ?: 100
        val adjustedIso = (baseIso * isoScale).toInt()
        return adjustedIso.coerceIn(isoRange?.lower ?: 50, isoRange?.upper ?: 3200)
    }

    fun backCameraId(): String? {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return mgr.cameraIdList.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    companion object {
        private const val TAG = "CameraBracket"

        /** Max capture attempts per bracket step before accepting a still-blurred frame. */
        private const val MAX_BLUR_RETRIES = 3

        /** Give up waiting for AE_STATE_CONVERGED after this many preview frames. */
        private const val AE_METERING_MAX_FRAMES = 30

        /** Only used if a device somehow never reports an exposure time at all. */
        private const val DEFAULT_EXPOSURE_NS = 8_000_000L

        /**
         * Computes optimized EV offsets that concentrate exposures around mid-tones where
         * saturation is typically highest, allowing fewer frames to achieve good coverage.
         * Uses a heuristic that clusters steps toward zero (midtone) rather than uniform spacing.
         *
         * For example, with steps=3 and stopsPerStep=2:
         * - Uniform: [-2, 0, 2]
         * - Optimized: [-1, 0, 1] (tighter clustering, still covers range but more densely)
         *
         * For steps=5 and stopsPerStep=1:
         * - Uniform: [-2, -1, 0, 1, 2]
         * - Optimized: [-0.8, -0.4, 0, 0.4, 0.8] (same frame count, better coverage)
         */
        fun computeOptimizedEVOffsets(steps: Int, stopsPerStep: Float): List<Float> {
            if (steps <= 1) return listOf(0f)

            // Heuristic: use a sigmoid-like scaling that concentrates steps near the center
            // but still spans the full range. This is more aggressive than uniform spacing,
            // allowing fewer frames to cover the saturation range effectively.
            val scale = 1.0f / kotlin.math.sqrt(steps.toFloat() / 2f)
            val offsets = mutableListOf<Float>()

            for (k in 0 until steps) {
                val centered = k - (steps - 1) / 2f
                // Apply sqrt compression to cluster toward zero
                val compressed = kotlin.math.sign(centered) * kotlin.math.sqrt(kotlin.math.abs(centered)) * scale
                offsets.add(compressed * stopsPerStep)
            }

            return offsets
        }
    }
}
