package com.example.parkinson.gait

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.example.parkinson.camera.FrameAnalyzer
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * One pose result as upright landmarks ([points] is null when no person was found or on error), with the
 * upright image size the normalized coordinates refer to.
 */
data class PoseSample(
    val timestampMs: Long,
    val points: List<PosePoint?>?,
    val imageWidth: Int,
    val imageHeight: Int,
    val status: PoseFrameStatus,
)

/**
 * MediaPipe Pose Landmarker (LIVE_STREAM, one person) for the gait assessment. Every result is turned
 * into a [PoseFrame] by [CameraPoseFeatureExtractor] and passed to [resultListener] on the MediaPipe
 * thread, so no frame is dropped.
 *
 * Timestamps: frames are submitted with the monotonic uptime clock (the same clock as the hand test);
 * MediaPipe's callback time is not the camera exposure time, and [PoseFrame.timestampMs] is the
 * submission time of the frame.
 *
 * The model asset is bundled as `pose_landmarker_lite.task` (MediaPipe Pose Landmarker Lite,
 * Apache-2.0, about 5.8 MB).
 */
class PoseLandmarkerManager(
    context: Context,
    private val modelAssetPath: String = MODEL_ASSET,
    private val extractor: CameraPoseFeatureExtractor = CameraPoseFeatureExtractor(),
) : FrameAnalyzer {

    /** Every frame's pose, called on the MediaPipe result thread. */
    @Volatile
    var resultListener: ((PoseFrame) -> Unit)? = null

    /**
     * Every result as upright landmarks, for tests that need more joints than [PoseFrame] keeps. Called on
     * the MediaPipe thread. Errors arrive with null points and status ERROR.
     */
    @Volatile
    var sampleListener: ((PoseSample) -> Unit)? = null

    @Volatile private var closed = false
    private var poseLandmarker: PoseLandmarker? = null

    /** Timestamp of the last frame submitted to MediaPipe (monotonic, written on the analysis thread). */
    @Volatile private var lastTimestampMs = 0L

    // Upright image size of each submitted frame, keyed by its timestamp until the result arrives.
    private val sizeLock = Any()
    private val pendingSizes = LinkedHashMap<Long, PendingGeometry>()

    init {
        try {
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(modelAssetPath).build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { result, _ -> onResult(result) }
                .setErrorListener { e -> Log.e(TAG, "Pose error", e) }
                .build()
            poseLandmarker = PoseLandmarker.createFromOptions(context.applicationContext, options)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create PoseLandmarker", e)
        } catch (e: LinkageError) {
            Log.e(TAG, "PoseLandmarker native library unavailable", e)
        }
    }

    /** True when the model loaded; false means no pose data will arrive. */
    val isReady: Boolean get() = poseLandmarker != null

    /** Submits one camera frame. The caller closes the ImageProxy. */
    @Synchronized
    override fun analyze(imageProxy: ImageProxy) {
        val landmarker = poseLandmarker ?: return
        if (closed) return
        try {
            val rotation = imageProxy.imageInfo.rotationDegrees
            val bitmap = imageProxy.toBitmap()
            val mpImage = BitmapImageBuilder(bitmap).build()
            val processing = ImageProcessingOptions.builder().setRotationDegrees(rotation).build()
            val swapped = rotation == 90 || rotation == 270
            val ts = nextTimestamp()
            synchronized(sizeLock) {
                pendingSizes[ts] = PendingGeometry(
                    width = if (swapped) bitmap.height else bitmap.width,
                    height = if (swapped) bitmap.width else bitmap.height,
                    rotation = rotation,
                )
                while (pendingSizes.size > MAX_PENDING) pendingSizes.remove(pendingSizes.keys.first())
            }
            landmarker.detectAsync(mpImage, processing, ts)
        } catch (e: Exception) {
            Log.e(TAG, "pose detect failed", e)
            // Never earlier than the frames already submitted: the timeline must stay non-decreasing.
            resultListener?.invoke(PoseFrame(lastTimestampMs, PoseFrameStatus.ERROR, null))
            sampleListener?.invoke(PoseSample(lastTimestampMs, null, 0, 0, PoseFrameStatus.ERROR))
        }
    }

    private fun nextTimestamp(): Long {
        // MediaPipe requires strictly increasing timestamps.
        var ts = SystemClock.uptimeMillis()
        if (ts <= lastTimestampMs) ts = lastTimestampMs + 1
        lastTimestampMs = ts
        return ts
    }

    private fun onResult(result: PoseLandmarkerResult) {
        if (closed) return
        val ts = result.timestampMs()
        val geometry = synchronized(sizeLock) { pendingSizes.remove(ts) }
        val w = geometry?.width ?: 0
        val h = geometry?.height ?: 0
        val upright: List<PosePoint?>? = if (result.landmarks().isEmpty()) {
            null
        } else {
            // MediaPipe reports landmarks in the frame it received; rotate them to the upright image.
            val raw = result.landmarks()[0].map { lm ->
                PosePoint(lm.x().toDouble(), lm.y().toDouble(), lm.visibility().orElse(0f).toDouble())
            }
            PoseOrientation.toUpright(raw, geometry?.rotation ?: 0)
        }
        val frame = if (upright == null) {
            PoseFrame(ts, PoseFrameStatus.NO_POSE, null)
        } else {
            extractor.extract(ts, upright, w, h)
        }
        resultListener?.invoke(frame)
        sampleListener?.invoke(PoseSample(ts, upright, w, h, frame.status))
    }

    fun close() {
        closed = true
        poseLandmarker?.close()
        poseLandmarker = null
    }

    private class PendingGeometry(val width: Int, val height: Int, val rotation: Int)

    companion object {
        private const val TAG = "PoseLandmarkerManager"
        const val MODEL_ASSET = "pose_landmarker_lite.task"
        private const val MAX_PENDING = 16
    }
}
