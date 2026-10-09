package com.example.parkinson.ui.screens.invalid

import androidx.annotation.StringRes
import com.example.parkinson.R
import com.example.parkinson.mediapipe.CameraQuality
import com.example.parkinson.openclose.OpenCloseError
import com.example.parkinson.openclose.OpenCloseInvalidReason
import com.example.parkinson.openclose.OpenCloseQualityIssue
import com.example.parkinson.openclose.OpenCloseQualityReport
import com.example.parkinson.openclose.OpenCloseState
import com.example.parkinson.tapping.SessionError
import com.example.parkinson.tapping.SessionInvalidReason
import com.example.parkinson.tapping.SessionState
import com.example.parkinson.tapping.quality.QualityIssue
import com.example.parkinson.gait.GaitError
import com.example.parkinson.gait.GaitInvalidReason
import com.example.parkinson.gait.GaitQualityIssue
import com.example.parkinson.gait.GaitQualityReport
import com.example.parkinson.gait.GaitState
import com.example.parkinson.sts.SitToStandError
import com.example.parkinson.sts.SitToStandFailure
import com.example.parkinson.sts.SitToStandInvalidReason
import com.example.parkinson.sts.SitToStandState
import com.example.parkinson.tapping.quality.QualityReport
import com.example.parkinson.tremor.RestingTremorError
import com.example.parkinson.tremor.RestingTremorInvalidReason
import com.example.parkinson.tremor.RestingTremorQualityIssue
import com.example.parkinson.tremor.RestingTremorQualityReport
import com.example.parkinson.tremor.RestingTremorState

/**
 * Patient-facing reason why a test produced no result. Passed as a navigation argument
 * (by name), so it only carries a category, never measurement data.
 *
 * [headline]: «داده کافی برای محاسبه نتیجه وجود ندارد.» ONLY when the quality engine found the
 * motor data insufficient; «نتیجه قابل اعتماد نیست.» for technically invalid recordings; none for
 * interruptions and technical errors. The lighting tip appears only when hand tracking was poor
 * AND the image was too dark/bright, never because of brightness alone.
 */
enum class InvalidResultKind(
    @param:StringRes val message: Int,
    @param:StringRes val tip: Int,
    @param:StringRes val headline: Int?
) {
    INTERRUPTED(R.string.invalid_interrupted, R.string.invalid_tip_interrupted, null),
    INSUFFICIENT_FRAMES(R.string.invalid_insufficient_frames, R.string.invalid_tip_hand_in_frame, R.string.invalid_headline_unreliable),
    EXCESSIVE_DROPOUT(R.string.invalid_excessive_dropout, R.string.invalid_tip_hand_in_frame, R.string.invalid_headline_insufficient),
    NO_HAND(R.string.invalid_no_hand, R.string.invalid_tip_hand_in_frame, R.string.invalid_headline_insufficient),
    POOR_TRACKING(R.string.invalid_poor_tracking, R.string.invalid_tip_hand_in_frame, R.string.invalid_headline_insufficient),
    POOR_TRACKING_LIGHTING(R.string.invalid_poor_tracking, R.string.invalid_tip_light_tracking, R.string.invalid_headline_insufficient),
    WRONG_HAND(R.string.invalid_wrong_hand, R.string.invalid_tip_wrong_hand, R.string.invalid_headline_unreliable),
    MULTIPLE_HANDS(R.string.invalid_multiple_hands, R.string.invalid_tip_multiple_hands, R.string.invalid_headline_unreliable),
    RECORDING_TOO_SHORT(R.string.invalid_too_short, R.string.invalid_tip_interrupted, R.string.invalid_headline_unreliable),
    UNSTABLE_TRACKING(R.string.invalid_unstable_tracking, R.string.invalid_tip_stable, R.string.invalid_headline_unreliable),
    INSUFFICIENT_FPS(R.string.invalid_low_fps, R.string.invalid_tip_fps, R.string.invalid_headline_unreliable),
    NO_TAPPING(R.string.invalid_no_tapping, R.string.invalid_tip_tapping, R.string.invalid_headline_insufficient),
    TOO_FEW_TAPS(R.string.invalid_too_few_taps, R.string.invalid_tip_tapping, R.string.invalid_headline_insufficient),
    NO_CYCLES(R.string.invalid_no_cycles, R.string.invalid_tip_cycles, R.string.invalid_headline_insufficient),
    TOO_FEW_CYCLES(R.string.invalid_too_few_cycles, R.string.invalid_tip_cycles, R.string.invalid_headline_insufficient),
    CAMERA_ERROR(R.string.invalid_camera_error, R.string.invalid_tip_camera, null),
    TRACKING_ERROR(R.string.invalid_tracking_error, R.string.invalid_tip_camera, null),
    STORAGE_ERROR(R.string.invalid_storage_error, R.string.invalid_tip_storage, null),
    UNEXPECTED_ERROR(R.string.invalid_unexpected_error, R.string.invalid_tip_camera, null),
    GROSS_MOVEMENT(R.string.invalid_gross_movement, R.string.invalid_tip_rest, R.string.invalid_headline_unreliable),
    GAIT_BODY_NOT_VISIBLE(R.string.invalid_gait_body, R.string.invalid_tip_gait, R.string.invalid_headline_insufficient),
    GAIT_TOO_FEW_STEPS(R.string.invalid_gait_steps, R.string.invalid_tip_gait, R.string.invalid_headline_insufficient),
    STS_INCOMPLETE(R.string.invalid_sts_incomplete, R.string.invalid_tip_sts, R.string.invalid_headline_insufficient),
    STS_TRACKING_LOST(R.string.invalid_sts_tracking, R.string.invalid_tip_sts, R.string.invalid_headline_insufficient),
    STS_LOW_VALID(R.string.invalid_sts_low_valid, R.string.invalid_tip_sts, R.string.invalid_headline_insufficient);

    companion object {
        fun from(state: SessionState): InvalidResultKind? = when (state) {
            is SessionState.Invalid -> when (val reason = state.reason) {
                SessionInvalidReason.Interrupted -> INTERRUPTED
                is SessionInvalidReason.QualityRejected -> fromQuality(reason.report)
            }

            is SessionState.Error -> when (state.error) {
                SessionError.CAMERA_FAILURE, SessionError.FRAME_STARVATION -> CAMERA_ERROR
                SessionError.TRACKING_FAILURE -> TRACKING_ERROR
                SessionError.STORAGE_FAILURE -> STORAGE_ERROR
                SessionError.UNEXPECTED -> UNEXPECTED_ERROR
            }

            else -> null
        }

        /** Hand Opening/Closing: same categories, with the cycle-level kinds for "no complete movement". */
        fun fromOpenClose(state: OpenCloseState): InvalidResultKind? = when (state) {
            is OpenCloseState.Invalid -> when (val reason = state.reason) {
                OpenCloseInvalidReason.Interrupted -> INTERRUPTED
                is OpenCloseInvalidReason.QualityRejected -> fromOpenCloseQuality(reason.report)
            }

            is OpenCloseState.Error -> when (state.error) {
                OpenCloseError.CAMERA_FAILURE, OpenCloseError.FRAME_STARVATION -> CAMERA_ERROR
                OpenCloseError.TRACKING_FAILURE -> TRACKING_ERROR
                OpenCloseError.STORAGE_FAILURE -> STORAGE_ERROR
                OpenCloseError.UNEXPECTED -> UNEXPECTED_ERROR
            }

            else -> null
        }

        /** Resting Hand Tremor: data-quality findings mapped to the same categories, plus gross movement. */
        fun fromRestingTremor(state: RestingTremorState): InvalidResultKind? = when (state) {
            is RestingTremorState.Invalid -> when (val reason = state.reason) {
                RestingTremorInvalidReason.Interrupted -> INTERRUPTED
                is RestingTremorInvalidReason.QualityRejected -> fromRestingTremorQuality(reason.report)
            }

            is RestingTremorState.Error -> when (state.error) {
                RestingTremorError.CAMERA_FAILURE, RestingTremorError.FRAME_STARVATION -> CAMERA_ERROR
                RestingTremorError.TRACKING_FAILURE -> TRACKING_ERROR
                RestingTremorError.STORAGE_FAILURE -> STORAGE_ERROR
                RestingTremorError.UNEXPECTED -> UNEXPECTED_ERROR
            }

            else -> null
        }

        /** Walking (camera pose): the same categories, plus the body-visibility and step-count kinds. */
        fun fromGait(state: GaitState): InvalidResultKind? = when (state) {
            is GaitState.Invalid -> when (val reason = state.reason) {
                GaitInvalidReason.Interrupted -> INTERRUPTED
                is GaitInvalidReason.QualityRejected -> fromGaitQuality(reason.report)
            }

            is GaitState.Error -> when (state.error) {
                GaitError.CAMERA_FAILURE, GaitError.FRAME_STARVATION -> CAMERA_ERROR
                GaitError.MODEL_FAILURE -> TRACKING_ERROR
                GaitError.STORAGE_FAILURE -> STORAGE_ERROR
                GaitError.UNEXPECTED -> UNEXPECTED_ERROR
            }

            else -> null
        }

        /** Sit-to-Stand: attempt-level failures (interruption, tracking, time limit, valid-frame share). */
        fun fromSitToStand(state: SitToStandState): InvalidResultKind? = when (state) {
            is SitToStandState.Invalid -> when (val reason = state.reason) {
                is SitToStandFailure.Engine -> when (reason.reason) {
                    SitToStandInvalidReason.INTERRUPTED -> INTERRUPTED
                    SitToStandInvalidReason.TRACKING_LOST -> STS_TRACKING_LOST
                    SitToStandInvalidReason.TIMEOUT_INCOMPLETE -> STS_INCOMPLETE
                    SitToStandInvalidReason.NON_MONOTONIC_TIMESTAMPS -> UNSTABLE_TRACKING
                }

                is SitToStandFailure.TooFewValidFrames -> STS_LOW_VALID
            }

            is SitToStandState.Error -> when (state.error) {
                SitToStandError.CAMERA_FAILURE, SitToStandError.FRAME_STARVATION -> CAMERA_ERROR
                SitToStandError.MODEL_FAILURE -> TRACKING_ERROR
                SitToStandError.STORAGE_FAILURE -> STORAGE_ERROR
                SitToStandError.UNEXPECTED -> UNEXPECTED_ERROR
            }

            else -> null
        }

        fun fromGaitQuality(report: GaitQualityReport): InvalidResultKind = when (report.primaryIssue) {
            GaitQualityIssue.TOO_FEW_FRAMES, GaitQualityIssue.LOW_VALID_FRAMES -> GAIT_BODY_NOT_VISIBLE
            GaitQualityIssue.TOO_FEW_STEPS -> GAIT_TOO_FEW_STEPS
            GaitQualityIssue.RECORDING_TOO_SHORT -> RECORDING_TOO_SHORT
            GaitQualityIssue.LONG_GAP, GaitQualityIssue.FREQUENT_GAPS -> EXCESSIVE_DROPOUT
            GaitQualityIssue.LOW_FRAME_RATE -> INSUFFICIENT_FPS
            GaitQualityIssue.NON_MONOTONIC_TIMESTAMPS,
            GaitQualityIssue.REDUCED_VALID_FRAMES, null -> UNSTABLE_TRACKING
        }

        fun fromRestingTremorQuality(report: RestingTremorQualityReport): InvalidResultKind = when (report.primaryIssue) {
            RestingTremorQualityIssue.GROSS_MOVEMENT -> GROSS_MOVEMENT
            RestingTremorQualityIssue.MULTIPLE_HANDS -> MULTIPLE_HANDS
            RestingTremorQualityIssue.WRONG_HAND -> WRONG_HAND
            RestingTremorQualityIssue.TOO_FEW_FRAMES, RestingTremorQualityIssue.LOW_VALID_FRAMES -> INSUFFICIENT_FRAMES
            RestingTremorQualityIssue.RECORDING_TOO_SHORT -> RECORDING_TOO_SHORT
            RestingTremorQualityIssue.LONG_INTERRUPTION, RestingTremorQualityIssue.FREQUENT_INTERRUPTIONS -> EXCESSIVE_DROPOUT
            RestingTremorQualityIssue.LOW_FRAME_RATE -> INSUFFICIENT_FPS
            RestingTremorQualityIssue.NON_MONOTONIC_TIMESTAMPS,
            RestingTremorQualityIssue.IRREGULAR_SAMPLING,
            RestingTremorQualityIssue.REDUCED_VALID_FRAMES, null -> UNSTABLE_TRACKING
        }

        fun fromOpenCloseQuality(report: OpenCloseQualityReport): InvalidResultKind = when (report.primaryIssue) {
            OpenCloseQualityIssue.NO_FRAMES -> INSUFFICIENT_FRAMES
            OpenCloseQualityIssue.EXCESSIVE_DROPOUT -> EXCESSIVE_DROPOUT
            OpenCloseQualityIssue.NO_HAND_DETECTED -> NO_HAND
            OpenCloseQualityIssue.INSUFFICIENT_TRACKING ->
                if (report.cameraQuality != CameraQuality.GOOD) POOR_TRACKING_LIGHTING else POOR_TRACKING
            OpenCloseQualityIssue.WRONG_HAND -> WRONG_HAND
            OpenCloseQualityIssue.MULTIPLE_HANDS -> MULTIPLE_HANDS
            OpenCloseQualityIssue.RECORDING_INCOMPLETE -> RECORDING_TOO_SHORT
            OpenCloseQualityIssue.INSUFFICIENT_FPS -> INSUFFICIENT_FPS
            OpenCloseQualityIssue.NO_CYCLES_DETECTED -> NO_CYCLES
            OpenCloseQualityIssue.TOO_FEW_CYCLES -> TOO_FEW_CYCLES
            else -> UNSTABLE_TRACKING
        }

        fun fromQuality(report: QualityReport): InvalidResultKind = when (report.primaryIssue) {
            QualityIssue.NO_FRAMES -> INSUFFICIENT_FRAMES
            QualityIssue.EXCESSIVE_DROPOUT -> EXCESSIVE_DROPOUT
            QualityIssue.NO_HAND_DETECTED -> NO_HAND
            QualityIssue.INSUFFICIENT_TRACKING ->
                if (report.cameraQuality != CameraQuality.GOOD) POOR_TRACKING_LIGHTING else POOR_TRACKING
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
