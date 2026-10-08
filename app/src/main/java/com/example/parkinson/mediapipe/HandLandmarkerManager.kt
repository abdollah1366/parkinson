package com.example.parkinson.mediapipe

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.example.parkinson.camera.FrameAnalyzer
import com.example.parkinson.diagnostics.TapDiagnostics
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wraps MediaPipe HandLandmarker (LIVE_STREAM mode).
 *
 * Usage:
 *   val manager = HandLandmarkerManager(context)
 *   manager.expectedHand = HandSide.RIGHT          // the hand chosen by the user
 *   // in your ImageAnalysis analyzer:
 *   manager.detect(imageProxy); imageProxy.close()
 *   // observe:
 *   manager.result.collect { ... }                 // UI only: StateFlow drops intermediate values
 *   manager.resultListener = { result -> ... }     // every result, called on the MediaPipe thread
 *   // when the screen is destroyed:
 *   manager.close()
 */
class HandLandmarkerManager(
    context: Context,
    private val modelAssetPath: String = "hand_landmarker.task",
    private val minDetectionConfidence: Float = 0.5f,
    private val minPresenceConfidence: Float = 0.5f,
    private val minTrackingConfidence: Float = 0.5f,
    /** Below this handedness score the result is reported as LowConfidence. */
    private val minHandednessScore: Float = 0.6f,
    /** Frames without a hand before NoHandDetected becomes TrackingLost. */
    private val trackingLostAfterFrames: Int = 5
) : FrameAnalyzer {

    private val _result = MutableStateFlow<HandTrackingResult>(HandTrackingResult.NoHandDetected(0L))
    val result: StateFlow<HandTrackingResult> = _result.asStateFlow()

    /**
     * Called synchronously for EVERY result, on the MediaPipe result thread (unlike [result],
     * which conflates). Use this to feed anything that must not miss frames, e.g. the tapping engine.
     */
    @Volatile
    var resultListener: ((HandTrackingResult) -> Unit)? = null

    /** The hand the user selected. null = accept any hand. */
    @Volatile
    var expectedHand: HandSide? = null

    /**
     * Mirroring of the frames sent to MediaPipe. CameraX analysis frames are unmirrored for both
     * lenses; see HandednessMapper for why this, not the camera lens, decides the mapping.
     */
    private val frameMirroring: FrameMirroring = HandednessMapper.CAMERAX_ANALYSIS

    private var handLandmarker: HandLandmarker? = null

    @Volatile private var closed = false
    @Volatile private var hadHand = false
    @Volatile private var uprightWidth = 0
    @Volatile private var uprightHeight = 0
    @Volatile private var consecutiveMisses = 0
    private var lastTimestampMs = 0L

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelAssetPath)
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(2) // 2 so that "multiple hands" can be detected
                .setMinHandDetectionConfidence(minDetectionConfidence)
                .setMinHandPresenceConfidence(minPresenceConfidence)
                .setMinTrackingConfidence(minTrackingConfidence)
                .setResultListener { handResult, _ -> onResult(handResult) }
                .setErrorListener { e -> onError(e) }
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context.applicationContext, options)
        } catch (e: Exception) {
            onInitFailed(e)
        } catch (e: LinkageError) {
            // Native library missing or incompatible with the device ABI: report, don't crash.
            onInitFailed(e)
        }
    }

    private fun onInitFailed(e: Throwable) {
        Log.e(TAG, "Failed to create HandLandmarker", e)
        publish(
            HandTrackingResult.Error(
                SystemClock.uptimeMillis(),
                "Could not load the hand model ($modelAssetPath): ${e.message}"
            )
        )
    }

    /**
     * Sends one camera frame to MediaPipe. Does NOT close the ImageProxy; the caller must.
     * Requires CameraX 1.3+ (ImageProxy.toBitmap()).
     */
    @Synchronized
    fun detect(imageProxy: ImageProxy) {
        val landmarker = handLandmarker
        if (closed || landmarker == null) return

        try {
            val rotation = imageProxy.imageInfo.rotationDegrees
            val bitmap = imageProxy.toBitmap()

            val swapped = rotation == 90 || rotation == 270
            uprightWidth = if (swapped) bitmap.height else bitmap.width
            uprightHeight = if (swapped) bitmap.width else bitmap.height

            val mpImage = BitmapImageBuilder(bitmap).build()
            val processing = ImageProcessingOptions.builder()
                .setRotationDegrees(rotation)
                .build()

            val frameTs = nextTimestamp()
            TapDiagnostics.onFrameAnalyzed(frameTs)
            landmarker.detectAsync(mpImage, processing, frameTs)
        } catch (e: Exception) {
            Log.e(TAG, "detect failed", e)
            publish(
                HandTrackingResult.Error(
                    SystemClock.uptimeMillis(),
                    e.message ?: "Unknown detection error"
                )
            )
        }
    }

    /** Called by the camera pipeline for every frame. The caller closes the ImageProxy. */
    override fun analyze(imageProxy: ImageProxy) = detect(imageProxy)

    private fun nextTimestamp(): Long {
        // MediaPipe requires strictly increasing timestamps.
        var ts = SystemClock.uptimeMillis()
        if (ts <= lastTimestampMs) ts = lastTimestampMs + 1
        lastTimestampMs = ts
        return ts
    }

    private fun onResult(handResult: HandLandmarkerResult) {
        if (closed) return

        val ts = handResult.timestampMs()
        val handsCount = handResult.landmarks().size
        TapDiagnostics.onResultReceived(SystemClock.uptimeMillis(), ts)

        val next = when {
            handsCount == 0 -> {
                consecutiveMisses++
                if (hadHand && consecutiveMisses >= trackingLostAfterFrames) {
                    HandTrackingResult.TrackingLost(ts)
                } else {
                    HandTrackingResult.NoHandDetected(ts)
                }
            }

            handsCount > 1 -> {
                consecutiveMisses = 0
                HandTrackingResult.MultipleHandsDetected(ts, handsCount)
            }

            else -> {
                consecutiveMisses = 0
                buildSingleHandResult(handResult, ts)
            }
        }
        publish(next)
    }

    private fun publish(result: HandTrackingResult) {
        _result.value = result
        resultListener?.invoke(result)
    }

    private fun buildSingleHandResult(handResult: HandLandmarkerResult, ts: Long): HandTrackingResult {
        val category = handResult.handedness().firstOrNull()?.firstOrNull()
        val confidence = category?.score() ?: 0f
        val label = category?.categoryName()
        val expected = expectedHand
        val decision = HandednessMapper.decide(label, confidence, frameMirroring, expected, minHandednessScore)
        if (TapDiagnostics.enabled) {
            // Checked first so release builds do not build this string for every frame.
            TapDiagnostics.log(
                "HAND ts=$ts label=$label score=${TapDiagnostics.f(confidence, 3)} mirroring=$frameMirroring " +
                    "physical=${HandednessMapper.toPhysicalHand(label, frameMirroring)} expected=$expected " +
                    "decision=${decision::class.simpleName}"
            )
        }

        val side = when (decision) {
            HandednessMapper.Decision.LowConfidence -> return HandTrackingResult.LowConfidence(ts, confidence)
            is HandednessMapper.Decision.WrongHand ->
                return HandTrackingResult.WrongHandDetected(ts, expected = decision.expected, detected = decision.detected)
            is HandednessMapper.Decision.Accepted -> decision.side
        }

        hadHand = true
        return HandTrackingResult.HandDetected(
            timestampMs = ts,
            landmarks = HandLandmarkMapper.toHandLandmarks(handResult.landmarks()[0]),
            handSide = side,
            confidence = confidence,
            imageWidth = uprightWidth,
            imageHeight = uprightHeight
        )
    }

    private fun onError(e: RuntimeException) {
        Log.e(TAG, "MediaPipe error", e)
        publish(
            HandTrackingResult.Error(
                SystemClock.uptimeMillis(),
                e.message ?: "MediaPipe error"
            )
        )
    }

    /** Releases the MediaPipe resources. Call from onCleared()/onDispose. */
    @Synchronized
    fun close() {
        closed = true
        resultListener = null
        try {
            handLandmarker?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close failed", e)
        }
        handLandmarker = null
    }

    private companion object {
        const val TAG = "HandLandmarkerManager"
    }
}
