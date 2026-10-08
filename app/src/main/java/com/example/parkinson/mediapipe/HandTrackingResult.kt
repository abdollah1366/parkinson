package com.example.parkinson.mediapipe

/** Whether the detected hand is the one the user selected, according to MediaPipe's handedness. */
enum class HandSideStatus {
    /** Handedness score >= limit and the side is the selected one. */
    MATCHES,

    /** Handedness score below the limit (e.g. hand seen edge-on): the side is not reliable. */
    UNCERTAIN,

    /** Handedness score >= limit and the side is NOT the selected one. */
    MISMATCH
}

/** Everything the UI / test engine needs to know about the current tracking state. */
sealed interface HandTrackingResult {
    val timestampMs: Long

    /** Facts about the camera frame behind this result; null in tests / before the first frame. */
    val frameInfo: FrameInfo?

    /** No hand in the frame (yet). */
    data class NoHandDetected(
        override val timestampMs: Long,
        override val frameInfo: FrameInfo? = null
    ) : HandTrackingResult

    /**
     * One hand with 21 landmarks. Landmarks are usable whenever MediaPipe returns them (its own
     * presence / tracking confidences already gate them). Handedness is separate information:
     * [handSide] is MediaPipe's left/right label, [confidence] its score, [sideStatus] the
     * comparison with the selected hand. A single uncertain or flipped label never discards the
     * landmarks; the recording-level hand check uses all frames together.
     */
    data class HandDetected(
        override val timestampMs: Long,
        val landmarks: List<HandLandmark>,   // 21 points, normalized 0..1 in the upright image
        val handSide: HandSide?,
        /** Handedness (left/right) score, NOT an image or landmark quality. */
        val confidence: Float,
        val imageWidth: Int,                 // upright image size, used by the overlay
        val imageHeight: Int,
        val sideStatus: HandSideStatus = HandSideStatus.MATCHES,
        override val frameInfo: FrameInfo? = null
    ) : HandTrackingResult

    /** A hand was tracked before, and has been missing for several frames. */
    data class TrackingLost(
        override val timestampMs: Long,
        override val frameInfo: FrameInfo? = null
    ) : HandTrackingResult

    /** More than one hand in the frame (only possible when the manager tracks more than one hand). */
    data class MultipleHandsDetected(
        override val timestampMs: Long,
        val count: Int,
        override val frameInfo: FrameInfo? = null
    ) : HandTrackingResult

    data class Error(
        override val timestampMs: Long,
        val message: String,
        override val frameInfo: FrameInfo? = null
    ) : HandTrackingResult
}
