package com.example.parkinson.ui.screens.invalid

import androidx.annotation.StringRes
import com.example.parkinson.R
import com.example.parkinson.tapping.SessionError
import com.example.parkinson.tapping.SessionInvalidReason
import com.example.parkinson.tapping.SessionState
import com.example.parkinson.tapping.quality.QualityIssue

/**
 * Patient-facing reason why a test produced no result. Passed as a navigation argument
 * (by name), so it only carries a category, never measurement data.
 */
enum class InvalidResultKind(@param:StringRes val message: Int, @param:StringRes val tip: Int) {
    INTERRUPTED(R.string.invalid_interrupted, R.string.invalid_tip_interrupted),
    INSUFFICIENT_FRAMES(R.string.invalid_insufficient_frames, R.string.invalid_tip_hand_in_frame),
    EXCESSIVE_DROPOUT(R.string.invalid_excessive_dropout, R.string.invalid_tip_hand_in_frame),
    NO_HAND(R.string.invalid_no_hand, R.string.invalid_tip_hand_in_frame),
    WRONG_HAND(R.string.invalid_wrong_hand, R.string.invalid_tip_wrong_hand),
    MULTIPLE_HANDS(R.string.invalid_multiple_hands, R.string.invalid_tip_multiple_hands),
    RECORDING_TOO_SHORT(R.string.invalid_too_short, R.string.invalid_tip_interrupted),
    UNSTABLE_TRACKING(R.string.invalid_unstable_tracking, R.string.invalid_tip_stable),
    INSUFFICIENT_FPS(R.string.invalid_low_fps, R.string.invalid_tip_light),
    NO_TAPPING(R.string.invalid_no_tapping, R.string.invalid_tip_tapping),
    TOO_FEW_TAPS(R.string.invalid_too_few_taps, R.string.invalid_tip_tapping),
    CAMERA_ERROR(R.string.invalid_camera_error, R.string.invalid_tip_camera),
    TRACKING_ERROR(R.string.invalid_tracking_error, R.string.invalid_tip_camera),
    STORAGE_ERROR(R.string.invalid_storage_error, R.string.invalid_tip_storage),
    UNEXPECTED_ERROR(R.string.invalid_unexpected_error, R.string.invalid_tip_camera);

    companion object {
        fun from(state: SessionState): InvalidResultKind? = when (state) {
            is SessionState.Invalid -> when (val reason = state.reason) {
                SessionInvalidReason.Interrupted -> INTERRUPTED
                is SessionInvalidReason.QualityRejected -> fromQuality(reason.report.primaryIssue)
            }

            is SessionState.Error -> when (state.error) {
                SessionError.CAMERA_FAILURE, SessionError.FRAME_STARVATION -> CAMERA_ERROR
                SessionError.TRACKING_FAILURE -> TRACKING_ERROR
                SessionError.STORAGE_FAILURE -> STORAGE_ERROR
                SessionError.UNEXPECTED -> UNEXPECTED_ERROR
            }

            else -> null
        }

        fun fromQuality(issue: QualityIssue?): InvalidResultKind = when (issue) {
            QualityIssue.NO_FRAMES, QualityIssue.TOO_FEW_VALID_FRAMES -> INSUFFICIENT_FRAMES
            QualityIssue.EXCESSIVE_DROPOUT -> EXCESSIVE_DROPOUT
            QualityIssue.NO_HAND_DETECTED -> NO_HAND
            QualityIssue.WRONG_HAND -> WRONG_HAND
            QualityIssue.MULTIPLE_HANDS -> MULTIPLE_HANDS
            QualityIssue.RECORDING_INCOMPLETE -> RECORDING_TOO_SHORT
            QualityIssue.INSUFFICIENT_FPS -> INSUFFICIENT_FPS
            QualityIssue.NO_TAPPING_DETECTED -> NO_TAPPING
            QualityIssue.TOO_FEW_TAPS -> TOO_FEW_TAPS
            else -> UNSTABLE_TRACKING
        }

        fun fromName(name: String?): InvalidResultKind = entries.firstOrNull { it.name == name } ?: UNEXPECTED_ERROR
    }
}
