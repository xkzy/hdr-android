package com.example.hdrfusion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.delay
import kotlin.math.sqrt

/**
 * Tracks handheld camera shake via the gyroscope (preferred — angular velocity is what
 * actually smears a still-scene exposure) or, on devices without one, a coarser fallback
 * derived from the accelerometer's jerk (frame-to-frame change in linear acceleration).
 *
 * Used by [CameraBracketController] for two things:
 *  - gating the start of a bracket on the hand being steady, so the shoot doesn't begin
 *    mid-adjustment;
 *  - per-frame, checking whether the device rotated enough *during that frame's own
 *    exposure window* to blur it, so the frame can be retaken.
 *
 * The per-frame check additionally needs the camera's exposure timestamps and this
 * monitor's sensor timestamps to be in the same clock domain (both nanosecond, but not
 * guaranteed to share an epoch on every device) — see
 * [CameraBracketController.timestampsAreComparable].
 */
class MotionMonitor(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val activeSensor: Sensor? = gyroscope ?: accelerometer

    /** True if a real gyroscope is present; false means the accelerometer fallback is in use. */
    val hasGyroscope: Boolean = gyroscope != null

    /** True if any motion sensor is available at all — when false, no motion gating happens. */
    val isAvailable: Boolean = activeSensor != null

    /**
     * Magnitude unit depends on [hasGyroscope]: rad/s for the gyroscope, or m/s^3-ish
     * "jerk" for the accelerometer fallback — not directly comparable across devices,
     * so thresholds are picked per-mode (see [STILLNESS_THRESHOLD]).
     */
    private data class Sample(val timestampNs: Long, val magnitude: Double)

    private val samples = ArrayDeque<Sample>()
    private val bufferWindowNs = 2_000_000_000L // keep last ~2s of samples
    private var lastAccel: FloatArray? = null

    fun start() {
        activeSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        synchronized(samples) { samples.clear() }
        lastAccel = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val magnitude = if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            val (x, y, z) = event.values
            sqrt((x * x + y * y + z * z).toDouble())
        } else {
            val prev = lastAccel
            lastAccel = event.values.clone()
            if (prev == null) 0.0 else {
                val dx = (event.values[0] - prev[0]).toDouble()
                val dy = (event.values[1] - prev[1]).toDouble()
                val dz = (event.values[2] - prev[2]).toDouble()
                sqrt(dx * dx + dy * dy + dz * dz)
            }
        }
        synchronized(samples) {
            samples.addLast(Sample(event.timestamp, magnitude))
            val cutoff = event.timestamp - bufferWindowNs
            while (samples.isNotEmpty() && samples.first().timestampNs < cutoff) samples.removeFirst()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    /** Most recent motion magnitude — used to decide whether the hand is currently steady. */
    fun currentMagnitude(): Double = synchronized(samples) { samples.lastOrNull()?.magnitude ?: 0.0 }

    /**
     * Peak motion magnitude observed with a sample timestamp inside [startNs, endNs]
     * (both in [SensorEvent.timestamp]'s clock domain). Used to judge whether a frame's
     * exposure window overlapped shake severe enough to have blurred it.
     */
    fun peakMagnitudeInWindow(startNs: Long, endNs: Long): Double = synchronized(samples) {
        samples.filter { it.timestampNs in startNs..endNs }.maxOfOrNull { it.magnitude } ?: 0.0
    }

    /**
     * Polls until [currentMagnitude] stays under the stillness threshold, or [timeoutMs]
     * elapses — whichever comes first. Best-effort: a shoot never blocks indefinitely on a
     * hand that won't hold still.
     */
    suspend fun waitForStillness(timeoutMs: Long = 2500L, pollIntervalMs: Long = 40L) {
        if (!isAvailable) return
        val threshold = if (hasGyroscope) STILLNESS_THRESHOLD_GYRO_RAD_PER_S else STILLNESS_THRESHOLD_ACCEL_JERK
        val deadline = System.currentTimeMillis() + timeoutMs
        while (currentMagnitude() > threshold && System.currentTimeMillis() < deadline) {
            delay(pollIntervalMs)
        }
    }

    companion object {
        /** Gyroscope reading below this (rad/s) counts as "hand is steady" before starting a shoot. */
        const val STILLNESS_THRESHOLD_GYRO_RAD_PER_S = 0.05

        /** Accelerometer-jerk fallback threshold when no gyroscope is present. Coarser signal, looser bound. */
        const val STILLNESS_THRESHOLD_ACCEL_JERK = 0.3

        /**
         * Angle (radians) a frame is estimated to have rotated through during its own exposure,
         * above which it's considered blurred and worth retaking. Estimated as
         * peak angular velocity * exposure duration — a coarse but cheap upper bound, since the
         * true blur also depends on focal length and subject distance that this app doesn't track.
         */
        const val BLUR_ANGLE_THRESHOLD_RAD = 0.0015
    }
}
