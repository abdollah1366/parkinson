package com.example.parkinson.mediapipe

/** Everything the UI / test engine needs to know about the current tracking state. */
sealed interface HandTrackingResult {
    val timestampMs: Long

    /** No hand in the frame (yet). */
    data class NoHandDetected(override val timestampMs: Long) : HandTrackingResult

    /** Exactly one hand, confident, and (if an expected hand is set) the correct one. */
    data class HandDetected(
        override val timestampMs: Long,
        val landmarks: List<HandLandmark>,   // 21 points, normalized 0..1 in the upright image
        val handSide: HandSide,
        val confidence: Float,
        val imageWidth: Int,                 // upright image size, used by the overlay
        val imageHeight: Int
    ) : HandTrackingResult

    /** A single hand is visible but it is not the hand the user selected. */
    data class WrongHandDetected(
        override val timestampMs: Long,
        val expected: HandSide,
        val detected: HandSide
    ) : HandTrackingResult

    /** A hand was tracked before, and has been missing for several frames. */
    data class TrackingLost(override val timestampMs: Long) : HandTrackingResult

    /** A hand is visible but the model is not sure (bad light, blur, partial hand). */
    data class LowConfidence(
        override val timestampMs: Long,
        val confidence: Float
    ) : HandTrackingResult

    /** More than one hand in the frame. */
    data class MultipleHandsDetected(
        override val timestampMs: Long,
        val count: Int
    ) : HandTrackingResult

    data class Error(
        override val timestampMs: Long,
        val message: String
    ) : HandTrackingResult
}
