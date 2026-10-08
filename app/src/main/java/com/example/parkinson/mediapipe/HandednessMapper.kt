package com.example.parkinson.mediapipe

import com.example.parkinson.model.SelectedHand

/** How the frame handed to MediaPipe relates to the real scene in front of the camera. */
enum class FrameMirroring {
    /** The frame shows the scene as the camera sees it (CameraX ImageAnalysis, both lenses). */
    NONE,

    /** The frame was flipped left-right before detection (e.g. a selfie-style bitmap). */
    HORIZONTAL
}

/**
 * Maps the MediaPipe handedness label to the PHYSICAL hand of the person.
 *
 * Pipeline facts this mapping relies on:
 *  1. Handedness is a property of the hand's shape in the frame MediaPipe receives. Rotation
 *     (ImageProcessingOptions) does not change it; a left-right mirror inverts it.
 *  2. CameraX ImageAnalysis frames are never mirrored, for the front or the back lens.
 *     ImageProxy.toBitmap() converts pixels only. Hence [CAMERAX_ANALYSIS] = NONE.
 *  3. The mirrored front-camera PREVIEW is a display-only transform. It affects where the
 *     overlay draws landmarks (HandLandmarkMapper.mapToView), never the classification.
 *  4. MediaPipe Tasks (tasks-vision 0.10.x) labels an UNMIRRORED frame with the physical hand.
 *     The older "assume a selfie-mirrored input" rule from the legacy Hands solution does NOT
 *     apply to the frames this app sends. Verified on a physical device (2026-10): applying that
 *     legacy flip to unmirrored CameraX frames reported the real left hand as "right" and the real
 *     right hand as "left".
 *
 * So: label is taken as-is for an unmirrored frame and inverted only when the frame itself was
 * mirrored. The camera lens does not matter.
 */
object HandednessMapper {

    /** Mirroring of the frames produced by CameraX ImageAnalysis and sent to MediaPipe. */
    val CAMERAX_ANALYSIS: FrameMirroring = FrameMirroring.NONE

    fun toPhysicalHand(label: String?, frameMirroring: FrameMirroring): HandSide? {
        val inFrame = when (label?.trim()?.lowercase()) {
            "left" -> HandSide.LEFT
            "right" -> HandSide.RIGHT
            else -> return null
        }
        return when (frameMirroring) {
            FrameMirroring.NONE -> inFrame
            FrameMirroring.HORIZONTAL -> inFrame.opposite()
        }
    }

    /** Outcome of checking one detected hand against the confidence limit and the selected hand. */
    sealed interface Decision {
        data class Accepted(val side: HandSide) : Decision
        data object LowConfidence : Decision
        data class WrongHand(val expected: HandSide, val detected: HandSide) : Decision
    }

    fun decide(
        label: String?,
        score: Float,
        frameMirroring: FrameMirroring,
        expected: HandSide?,
        minScore: Float
    ): Decision {
        val side = toPhysicalHand(label, frameMirroring)
        if (side == null || score < minScore) return Decision.LowConfidence
        if (expected != null && side != expected) return Decision.WrongHand(expected, side)
        return Decision.Accepted(side)
    }
}

/** The hand the patient selected is the physical hand that MediaPipe must report. */
fun SelectedHand.toHandSide(): HandSide = when (this) {
    SelectedHand.LEFT -> HandSide.LEFT
    SelectedHand.RIGHT -> HandSide.RIGHT
}
