package com.example.parkinson.gait

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Rolling share of recent pose frames with a complete body (VALID), for the live guide before the walk.
 * Works in every state, including IDLE, when the session does not record frames. Warnings only, never a
 * measurement. Thread-safe: frames arrive on the MediaPipe thread.
 */
class LivePoseStatus(private val window: Int = 20) {

    private val lock = Any()
    private val recent = ArrayDeque<Boolean>()
    private val _validPercent = MutableStateFlow<Double?>(null)

    /** Share of the last [window] frames that were VALID (percent); null before the first frame. */
    val validPercent: StateFlow<Double?> = _validPercent.asStateFlow()

    fun add(frame: PoseFrame) {
        synchronized(lock) {
            recent.addLast(frame.status == PoseFrameStatus.VALID)
            while (recent.size > window) recent.removeFirst()
            _validPercent.value = 100.0 * recent.count { it } / recent.size
        }
    }
}
