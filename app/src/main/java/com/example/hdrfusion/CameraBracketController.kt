package com.example.hdrfusion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlin.math.pow
import kotlin.math.roundToInt
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
 */
data class BracketConfig(
    val steps: Int,
    val stopsPerStep: Float,
    val baseIso: Int,
    val isoWeight: Float,
    val focalLengthMm: Float? = null
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

    private val characteristics: CameraCharacteristics =
        (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager)
            .getCameraCharacteristics(cameraId)

    fun start() {
        bgThread = HandlerThread("hdrfusion-cam").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    fun stop() {
        session?.close()
        device?.close()
        imageReader?.close()
        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
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

    /**
     * Runs the full bracket: opens the camera, streams preview to [previewSurface],
     * fires [BracketConfig.steps] manual captures with ISO/shutter offset per stop,
     * decodes each JPEG to a Bitmap, and returns them in capture order.
     */
    suspend fun captureBracket(
        previewSurface: Surface,
        maxSize: android.util.Size,
        config: BracketConfig,
        onProgress: (Int, Int) -> Unit
    ): List<Bitmap> {
        val cam = device ?: openCamera()

        val reader = ImageReader.newInstance(maxSize.width, maxSize.height, android.graphics.ImageFormat.JPEG, config.steps)
        imageReader = reader

        val sess = session ?: createSession(previewSurface, reader.surface)

        // Lock focal length (fx) if requested and the device exposes it.
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val chosenFocal = config.focalLengthMm
            ?: focalLengths?.firstOrNull()

        // Establish a reasonable base exposure time by reading the sensor's exposure range;
        // in a production app you'd run a brief auto-exposure metering pass first and use
        // its converged values as the centre of the bracket. Here we use a safe default
        // and let per-step EV offsets fan out around it.
        val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val baseExposureNs = (exposureRange?.lower ?: 1_000_000L).coerceAtLeast(1_000_000L) * 8 // ~8ms default

        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

        val results = mutableListOf<Bitmap>()

        for (k in 0 until config.steps) {
            val centered = k - (config.steps - 1) / 2f
            val evOffset = centered * config.stopsPerStep

            val isoEv = evOffset * config.isoWeight
            val shutterEv = evOffset * (1f - config.isoWeight)

            val iso = (config.baseIso * 2.0.pow(isoEv.toDouble())).roundToInt()
                .coerceIn(isoRange?.lower ?: 50, isoRange?.upper ?: 3200)

            val exposureNs = (baseExposureNs * 2.0.pow(shutterEv.toDouble())).toLong()
                .coerceIn(exposureRange?.lower ?: 1000L, exposureRange?.upper ?: 500_000_000L)

            val bitmap = captureOne(cam, sess, reader, iso, exposureNs, chosenFocal)
            results.add(bitmap)
            onProgress(k + 1, config.steps)
        }

        return results
    }

    private suspend fun captureOne(
        cam: CameraDevice,
        sess: CameraCaptureSession,
        reader: ImageReader,
        iso: Int,
        exposureNs: Long,
        focalMm: Float?
    ): Bitmap = suspendCancellableCoroutine { cont ->
        var imageBytes: ByteArray? = null

        reader.setOnImageAvailableListener({ r ->
            val img: Image? = r.acquireLatestImage()
            if (img != null) {
                val buffer = img.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                img.close()
                imageBytes = bytes
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (cont.isActive) cont.resume(bmp)
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
            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
            ) {
                if (cont.isActive) cont.resumeWithException(RuntimeException("Capture failed: reason=${failure.reason}"))
            }
        }, bgHandler)
    }

    fun backCameraId(): String? {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return mgr.cameraIdList.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    companion object {
        private const val TAG = "CameraBracket"
    }
}
