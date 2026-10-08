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
 *
 * Configuration (engineering defaults, see docs/finger-tapping-algorithm.md section 1c):
 *  - minDetection / minPresence / minTracking confidence 0.5: MediaPipe's own defaults. They
 *    gate whether landmarks are returned at all; lower values admit false hands, higher values
 *    drop real hands in dim or blurred frames.
 *  - [maxHands] 1: in LIVE_STREAM mode MediaPipe re-runs palm detection on EVERY frame while
 *    fewer hands than numHands are tracked. With numHands = 2 and one hand in view (the normal
 *    test situation) that roughly doubles the work per frame and lowers the result rate, which
 *    made recordings fail the frame-rate check. One hand is what the test needs.
 *  - [minHandednessScore] 0.6: only decides whether the LEFT/RIGHT label is trusted. It never
 *    discards landmarks (see [HandTrackingResult.HandDetected]).
 */
class HandLandmarkerManager(
    context: Context,
    private val modelAssetPath: String = "hand_landmarker.task",
    private val minDetectionConfidence: Float = 0.5f,
    private val minPresenceConfidence: Float = 0.5f,
    private val minTrackingConfidence: Float = 0.5f,
    private val maxHands: Int = 1,
    /** Below this handedness score the left/right label is reported as UNCERTAIN. */
    private val minHandednessScore: Float = 0.6f,
    /** Frames without a hand before NoHandDetected becomes TrackingLost. */
    private val trackingLostAfterFrames: Int = 5,
    private val visionConfig: VisionQualityConfig = VisionQualityConfig(),
    /** Rolling window of the live vision status. */
    private val liveWindowMs: Long = 2_000L
) : FrameAnalyzer {

    private val _result = MutableStateFlow<HandTrackingResult>(HandTrackingResult.NoHandDetected(0L))
    val result: StateFlow<HandTrackingResult> = _result.asStateFlow()

    private val _liveStatus = MutableStateFlow(LiveVisionStatus())

    /** Rolling camera / tracking status for the UI (warnings only, never a hard gate). */
    val liveStatus: StateFlow<LiveVisionStatus> = _liveStatus.asStateFlow()

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
    @Volatile private var consecutiveMisses = 0
    private var lastTimestampMs = 0L

    /** Geometry and frame facts of a submitted frame, until its result arrives. */
    private class Pending(val info: FrameInfo, val width: Int, val height: Int, val rotation: Int)

    private val pendingLock = Any()
    private val pending = LinkedHashMap<Long, Pending>()
    private var sequence = 0L
    private val dropEstimator = CameraDropEstimator()

    // Pipeline counters (analysis thread writes received/submitted, result thread the rest).
    private val countersLock = Any()
    private var framesReceived = 0L
    private var framesSubmitted = 0L
    private var resultsReceived = 0L
    private var cameraFramesSkipped = 0L
    private val recent = ArrayDeque<Triple<Long, Boolean, Float?>>()

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelAssetPath)
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(maxHands)
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
        synchronized(countersLock) { framesReceived++ }
        val landmarker = handLandmarker
        if (closed || landmarker == null) return

        try {
            val rotation = imageProxy.imageInfo.rotationDegrees
            val skipped = dropEstimator.onFrame(imageProxy.imageInfo.timestamp)
            // Measured on the frame itself (before conversion); a warning signal only.
            val luma = LumaEstimator.meanLuma(imageProxy)
            val bitmap = imageProxy.toBitmap()

            val swapped = rotation == 90 || rotation == 270
            val width = if (swapped) bitmap.height else bitmap.width
            val height = if (swapped) bitmap.width else bitmap.height

            val mpImage = BitmapImageBuilder(bitmap).build()
            val processing = ImageProcessingOptions.builder()
                .setRotationDegrees(rotation)
                .build()

            val frameTs = nextTimestamp()
            val info = FrameInfo(++sequence, luma, skipped)
            synchronized(pendingLock) {
                pending[frameTs] = Pending(info, width, height, rotation)
                // MediaPipe may drop frames while busy; never let the map grow.
                while (pending.size > MAX_PENDING) pending.remove(pending.keys.first())
            }
            synchronized(countersLock) {
                framesSubmitted++
                cameraFramesSkipped += skipped
            }
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
        val meta = synchronized(pendingLock) {
            // Results arrive in order: everything older was dropped by MediaPipe.
            val it = pending.entries.iterator()
            var found: Pending? = null
            while (it.hasNext()) {
                val e = it.next()
                if (e.key > ts) break
                if (e.key == ts) found = e.value
                it.remove()
            }
            found
        }
        val handsCount = handResult.landmarks().size
        TapDiagnostics.onResultReceived(SystemClock.uptimeMillis(), ts)

        val next = when {
            handsCount == 0 -> {
                consecutiveMisses++
                if (hadHand && consecutiveMisses >= trackingLostAfterFrames) {
                    HandTrackingResult.TrackingLost(ts, meta?.info)
                } else {
                    HandTrackingResult.NoHandDetected(ts, meta?.info)
                }
            }

            handsCount > 1 -> {
                consecutiveMisses = 0
                HandTrackingResult.MultipleHandsDetected(ts, handsCount, meta?.info)
            }

            else -> {
                consecutiveMisses = 0
                buildSingleHandResult(handResult, ts, meta)
            }
        }
        updateLiveStatus(ts, next is HandTrackingResult.HandDetected, meta?.info?.meanLuma)
        publish(next)
    }

    private fun publish(result: HandTrackingResult) {
        _result.value = result
        resultListener?.invoke(result)
    }

    private fun buildSingleHandResult(handResult: HandLandmarkerResult, ts: Long, meta: Pending?): HandTrackingResult {
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
                    "decision=${decision::class.simpleName} luma=${meta?.info?.meanLuma} seq=${meta?.info?.sequence}"
            )
        }

        // The landmarks are kept in every case; only the left/right statement differs.
        val (side, status) = when (decision) {
            is HandednessMapper.Decision.Accepted -> decision.side to HandSideStatus.MATCHES
            is HandednessMapper.Decision.WrongHand -> decision.detected to HandSideStatus.MISMATCH
            HandednessMapper.Decision.LowConfidence ->
                HandednessMapper.toPhysicalHand(label, frameMirroring) to HandSideStatus.UNCERTAIN
        }

        hadHand = true
        return HandTrackingResult.HandDetected(
            timestampMs = ts,
            landmarks = HandLandmarkMapper.toHandLandmarks(handResult.landmarks()[0], meta?.rotation ?: 0),
            handSide = side,
            confidence = confidence,
            imageWidth = meta?.width ?: 0,
            imageHeight = meta?.height ?: 0,
            sideStatus = status,
            frameInfo = meta?.info
        )
    }

    private fun updateLiveStatus(ts: Long, hasHand: Boolean, luma: Float?) {
        val status = synchronized(countersLock) {
            resultsReceived++
            recent.addLast(Triple(ts, hasHand, luma))
            while (recent.isNotEmpty() && recent.first().first < ts - liveWindowMs) recent.removeFirst()
            val rate = recent.count { it.second }.toDouble() / recent.size
            val lumas = recent.mapNotNull { it.third }
            val meanLuma = if (lumas.isEmpty()) null else lumas.average().toFloat()
            val span = recent.last().first - recent.first().first
            LiveVisionStatus(
                cameraQuality = visionConfig.cameraQuality(meanLuma),
                trackingQuality = visionConfig.trackingQuality(rate),
                trackingRate = rate,
                meanLuma = meanLuma,
                resultsPerSecond = if (span > 0) (recent.size - 1) * 1000.0 / span else 0.0,
                framesReceived = framesReceived,
                framesSubmitted = framesSubmitted,
                resultsReceived = resultsReceived,
                cameraFramesSkipped = cameraFramesSkipped
            )
        }
        _liveStatus.value = status
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
        synchronized(pendingLock) { pending.clear() }
    }

    private companion object {
        const val TAG = "HandLandmarkerManager"
        const val MAX_PENDING = 32
    }
}
