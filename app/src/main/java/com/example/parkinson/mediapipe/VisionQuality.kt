package com.example.parkinson.mediapipe

import androidx.camera.core.ImageProxy
import kotlin.math.roundToInt

/** Per-frame facts measured before MediaPipe runs. Attached to every [HandTrackingResult]. */
data class FrameInfo(
    /** Analysis sequence number: every frame handed to MediaPipe gets the next number. */
    val sequence: Long,
    /** Mean luma 0..255 of the analysis frame (sampled grid); null when it could not be read. */
    val meanLuma: Float?,
    /** Camera frames skipped before this one (CameraX drops frames while the analyzer is busy). */
    val cameraFramesSkipped: Int
)

/** Image (exposure) quality. A QUALITY SIGNAL only: never, by itself, a reason to reject a test. */
enum class CameraQuality { GOOD, WARNING, POOR }

/** How continuously MediaPipe delivered hand landmarks. */
enum class HandTrackingQuality { GOOD, WARNING, POOR }

/**
 * ENGINEERING limits for the vision quality layers (not clinically validated).
 *
 * Auto-exposure normally keeps the mean luma of an indoor scene around 80-150. A mean far below
 * that means exposure has hit its limit (dark scene: noise, motion blur, lower camera frame rate);
 * far above means over-exposure. These only produce warnings: whether the image is usable is
 * decided by the tracking result itself (are landmarks delivered?), not by brightness.
 */
data class VisionQualityConfig(
    val darkWarningLuma: Float = 50f,
    val darkPoorLuma: Float = 20f,
    val brightWarningLuma: Float = 225f,
    /** Share of time with valid landmarks. */
    val goodTrackingRate: Double = 0.85,
    val minTrackingRate: Double = 0.60
) {
    fun cameraQuality(meanLuma: Float?): CameraQuality = when {
        meanLuma == null -> CameraQuality.GOOD
        meanLuma < darkPoorLuma -> CameraQuality.POOR
        meanLuma < darkWarningLuma || meanLuma > brightWarningLuma -> CameraQuality.WARNING
        else -> CameraQuality.GOOD
    }

    fun trackingQuality(trackingRate: Double): HandTrackingQuality = when {
        trackingRate >= goodTrackingRate -> HandTrackingQuality.GOOD
        trackingRate >= minTrackingRate -> HandTrackingQuality.WARNING
        else -> HandTrackingQuality.POOR
    }
}

/** Live (rolling ~2 s) vision status for the camera screen, before and during recording. */
data class LiveVisionStatus(
    val cameraQuality: CameraQuality = CameraQuality.GOOD,
    val trackingQuality: HandTrackingQuality = HandTrackingQuality.POOR,
    val trackingRate: Double = 0.0,
    val meanLuma: Float? = null,
    val resultsPerSecond: Double = 0.0,
    val framesReceived: Long = 0,
    val framesSubmitted: Long = 0,
    val resultsReceived: Long = 0,
    val cameraFramesSkipped: Long = 0
) {
    /** Results that never came back from MediaPipe (dropped while it was busy). */
    val pipelineFramesDropped: Long get() = (framesSubmitted - resultsReceived).coerceAtLeast(0)
}

/** Mean luma of a frame from a sparse grid of the first plane (Y for YUV, RGB for RGBA). Cheap. */
object LumaEstimator {

    private const val GRID_X = 32
    private const val GRID_Y = 24

    fun meanLuma(image: ImageProxy): Float? = try {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rgba = pixelStride >= 4
        var sum = 0.0
        var n = 0
        for (gy in 0 until GRID_Y) {
            val y = (gy + 0.5f) * image.height / GRID_Y
            for (gx in 0 until GRID_X) {
                val x = (gx + 0.5f) * image.width / GRID_X
                val i = y.toInt() * rowStride + x.toInt() * pixelStride
                if (i + (if (rgba) 2 else 0) >= buffer.limit()) continue
                sum += if (rgba) {
                    val r = buffer.get(i).toInt() and 0xFF
                    val g = buffer.get(i + 1).toInt() and 0xFF
                    val b = buffer.get(i + 2).toInt() and 0xFF
                    0.299 * r + 0.587 * g + 0.114 * b
                } else {
                    (buffer.get(i).toInt() and 0xFF).toDouble()
                }
                n++
            }
        }
        if (n == 0) null else (sum / n).toFloat()
    } catch (e: Exception) {
        null
    }
}

/**
 * Estimates camera frames skipped before each analyzed frame from the sensor timestamps: the
 * camera period is the shortest recent interval (frames arrive at the camera rate whenever none
 * is dropped). Not thread-safe; used on the analysis thread only.
 */
class CameraDropEstimator(private val window: Int = 30) {
    private var lastNs = -1L
    private val recent = ArrayDeque<Long>()

    fun onFrame(sensorTimestampNs: Long): Int {
        val prev = lastNs
        lastNs = sensorTimestampNs
        if (prev < 0 || sensorTimestampNs <= prev) return 0
        val interval = sensorTimestampNs - prev
        recent.addLast(interval)
        if (recent.size > window) recent.removeFirst()
        val period = recent.min()
        return ((interval.toDouble() / period).roundToInt() - 1).coerceAtLeast(0)
    }

    fun reset() {
        lastNs = -1L
        recent.clear()
    }
}
