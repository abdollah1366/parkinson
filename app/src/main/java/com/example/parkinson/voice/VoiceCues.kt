package com.example.parkinson.voice

import com.example.parkinson.imu.ImuGaitState
import com.example.parkinson.imu.ImuSitToStandState
import com.example.parkinson.openclose.OpenCloseState
import com.example.parkinson.pronation.PronationState
import com.example.parkinson.stability.StabilityState
import com.example.parkinson.tapping.SessionState
import com.example.parkinson.tremor.RestingTremorState

/** Short Persian voice clips (res/raw/voice_*.ogg, text in tools/voice/clips.tsv). */
enum class VoiceClip {
    COUNT_1, COUNT_2, COUNT_3, COUNT_4, COUNT_5,
    DONE, STAY_STILL, PAUSED,
    GAIT_STAND, GAIT_WALK,
    STS_SIT, STS_GO,
    HAND_HOLD, HAND_REST, TAP, OPEN_CLOSE, ROTATE,
    DUAL_START_99, DUAL_START_98, DUAL_START_96, DUAL_START_94, DUAL_START_92, DUAL_START_91, DUAL_STOP;

    companion object {
        /** The spoken number 1-5, or null outside that range. */
        fun count(n: Int): VoiceClip? = when (n) {
            1 -> COUNT_1
            2 -> COUNT_2
            3 -> COUNT_3
            4 -> COUNT_4
            5 -> COUNT_5
            else -> null
        }

        /** The dual-task instruction with its start number, or null for a number without a clip. */
        fun dualStart(startNumber: Int): VoiceClip? = when (startNumber) {
            99 -> DUAL_START_99
            98 -> DUAL_START_98
            96 -> DUAL_START_96
            94 -> DUAL_START_94
            92 -> DUAL_START_92
            91 -> DUAL_START_91
            else -> null
        }
    }
}

/**
 * One spoken cue. A cue is played once each time its [key] changes, so a state that ticks (a countdown with the same
 * number, a running recording) does not repeat it.
 */
data class VoiceCue(val key: String, val clip: VoiceClip)

/**
 * Which cue each test state speaks. Few cues on purpose: a count before the start, the instruction at the start (it is
 * the start signal), the repetitions of the sit-to-stand test, and the end. Pure functions, no Android calls.
 */
object VoiceCues {

    private fun countdown(n: Int): VoiceCue? = VoiceClip.count(n)?.let { VoiceCue("countdown-$n", it) }

    private val done = VoiceCue("done", VoiceClip.DONE)

    fun gait(state: ImuGaitState): VoiceCue? = when (state) {
        is ImuGaitState.Calibrating -> VoiceCue("calibrating", VoiceClip.GAIT_STAND)
        is ImuGaitState.Countdown -> countdown(state.secondsLeft)
        is ImuGaitState.Walking -> VoiceCue("walking", VoiceClip.GAIT_WALK)
        is ImuGaitState.Paused -> VoiceCue("paused", VoiceClip.PAUSED)
        ImuGaitState.Processing -> done
        else -> null
    }

    /** The walk while subtracting: the instruction with the start number when walking starts, and "stop" at the end. */
    fun dualWalk(startNumber: Int, state: ImuGaitState): VoiceCue? = when (state) {
        is ImuGaitState.Walking -> VoiceClip.dualStart(startNumber)?.let { VoiceCue("dual-walking-$startNumber", it) }
        ImuGaitState.Processing -> VoiceCue("dual-stop", VoiceClip.DUAL_STOP)
        else -> gait(state)
    }

    fun sitToStand(state: ImuSitToStandState): VoiceCue? = when (state) {
        is ImuSitToStandState.Calibrating -> VoiceCue("calibrating", VoiceClip.STS_SIT)
        is ImuSitToStandState.Countdown -> countdown(state.secondsLeft)
        // At the start the instruction; then each counted repetition is spoken.
        is ImuSitToStandState.Active ->
            if (state.repetitions == 0) VoiceCue("active-0", VoiceClip.STS_GO)
            else VoiceClip.count(state.repetitions)?.let { VoiceCue("active-${state.repetitions}", it) }
        is ImuSitToStandState.Paused -> VoiceCue("paused", VoiceClip.PAUSED)
        ImuSitToStandState.Processing -> done
        else -> null
    }

    fun fingerTapping(state: SessionState): VoiceCue? = when (state) {
        is SessionState.Countdown -> countdown(state.secondsLeft)
        is SessionState.Recording -> VoiceCue("recording", VoiceClip.TAP)
        SessionState.Processing -> done
        else -> null
    }

    fun openClose(state: OpenCloseState): VoiceCue? = when (state) {
        is OpenCloseState.Countdown -> countdown(state.secondsLeft)
        is OpenCloseState.Recording -> VoiceCue("recording", VoiceClip.OPEN_CLOSE)
        OpenCloseState.Processing -> done
        else -> null
    }

    fun restingTremor(state: RestingTremorState): VoiceCue? = when (state) {
        is RestingTremorState.Countdown -> countdown(state.secondsLeft)
        is RestingTremorState.Recording -> VoiceCue("recording", VoiceClip.HAND_REST)
        RestingTremorState.Processing -> done
        else -> null
    }

    fun pronation(state: PronationState): VoiceCue? = when (state) {
        is PronationState.Preparing -> VoiceCue("preparing", VoiceClip.STAY_STILL)
        is PronationState.Countdown -> countdown(state.count)
        is PronationState.Recording -> VoiceCue("recording", VoiceClip.ROTATE)
        PronationState.Processing -> done
        else -> null
    }

    fun handStability(state: StabilityState): VoiceCue? = when (state) {
        is StabilityState.Preparation -> VoiceCue("preparation", VoiceClip.HAND_HOLD)
        is StabilityState.Recording -> VoiceCue("recording", VoiceClip.STAY_STILL)
        StabilityState.Processing -> done
        else -> null
    }
}
