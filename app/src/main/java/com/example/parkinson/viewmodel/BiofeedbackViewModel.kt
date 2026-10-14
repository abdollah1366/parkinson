package com.example.parkinson.viewmodel

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.parkinson.ParkinsonApplication
import com.example.parkinson.biofeedback.BalanceEngine
import com.example.parkinson.biofeedback.BioExercise
import com.example.parkinson.biofeedback.BioSession
import com.example.parkinson.biofeedback.LiveMic
import com.example.parkinson.biofeedback.LoudVoiceEngine
import com.example.parkinson.biofeedback.MemorySequenceEngine
import com.example.parkinson.biofeedback.MetronomeWalkEngine
import com.example.parkinson.biofeedback.TapRhythmEngine
import com.example.parkinson.biofeedback.Tones
import com.example.parkinson.plan.PlanSync
import com.example.parkinson.plan.Prescription
import com.example.parkinson.profile.UserProfile
import com.example.parkinson.sensors.MotionSensorSource
import com.example.parkinson.sensors.MotionSensorType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the exercise screen shows while it runs; one kind per exercise. */
sealed interface BioLive {
    data class Loud(val snapshot: LoudVoiceEngine.Snapshot, val targetDb: Double, val holdSec: Double, val reps: Int) : BioLive
    data class Balance(val snapshot: BalanceEngine.Snapshot, val radiusDeg: Double, val durationSec: Double) : BioLive
    data class Walk(val snapshot: MetronomeWalkEngine.Snapshot, val bpm: Double, val durationSec: Double, val beats: Int) : BioLive
    data class Memory(val snapshot: MemorySequenceEngine.Snapshot, /** The cell lit now while showing. */ val lit: Int?) : BioLive
    data class Tap(val snapshot: TapRhythmEngine.Snapshot, val durationSec: Double, val targetRate: Double, val handDone: Boolean) : BioLive
}

enum class BioStage { INTRO, RUNNING, RESULT }

data class BioResult(val score: Int, val completed: Boolean, val metrics: Map<String, Double>, /** Null while saving. */ val sent: Boolean?)

data class BioState(
    val prescription: Prescription? = null,
    val exercise: BioExercise? = null,
    val level: Int = 1,
    val stage: BioStage = BioStage.INTRO,
    val live: BioLive? = null,
    val result: BioResult? = null,
    /** "mic" or "sensor" when the exercise could not start. */
    val error: String? = null,
)

/**
 * Runs one biofeedback session of the active person: reads the microphone or the motion sensors into the exercise's
 * engine, plays the metronome and the memory notes, and keeps the result (sent to the doctor when possible).
 */
class BiofeedbackViewModel(
    private val sync: PlanSync,
    private val activeProfile: Flow<UserProfile?>,
    private val sensors: MotionSensorSource,
) : ViewModel() {
    private val _state = MutableStateFlow(BioState())
    val state: StateFlow<BioState> = _state.asStateFlow()

    private val lock = Any()
    private val mic = LiveMic()
    private var tones: Tones? = null
    private var jobs = mutableListOf<Job>()
    private var startedAt = 0L
    private var startedElapsed = 0L
    private var profileId: Int? = null

    private var loud: LoudVoiceEngine? = null
    private var balance: BalanceEngine? = null
    private var walk: MetronomeWalkEngine? = null
    private var memory: MemorySequenceEngine? = null
    private var tap: TapRhythmEngine? = null

    /** Loads the prescription [prescriptionId] of the active person; shows the intro. */
    fun open(prescriptionId: Long) {
        if (_state.value.prescription?.id == prescriptionId && _state.value.stage == BioStage.RUNNING) return
        viewModelScope.launch {
            val profile = activeProfile.first()
            profileId = profile?.id
            val p = profile?.let { sync.store.plans()[it.id] }?.prescriptions?.firstOrNull { it.id == prescriptionId }
            _state.value = BioState(prescription = p, exercise = BioExercise.of(p?.exercise), level = p?.level ?: 1)
        }
    }

    fun sensorsAvailable(): Boolean = sensors.isAvailable(MotionSensorType.ACCELEROMETER)

    /** Starts the session (the microphone permission must be granted for the loud voice). */
    fun start() {
        val s = _state.value
        val exercise = s.exercise ?: return
        if (s.stage == BioStage.RUNNING) return
        stopDevices()
        startedAt = System.currentTimeMillis()
        startedElapsed = SystemClock.elapsedRealtime()
        tones = Tones()
        _state.value = s.copy(stage = BioStage.RUNNING, result = null, error = null)
        val ok = when (exercise) {
            BioExercise.LOUD_VOICE -> startLoud(s.level)
            BioExercise.BALANCE -> startBalance(s.level)
            BioExercise.METRONOME_WALK -> startWalk(s.level)
            BioExercise.MEMORY_SEQUENCE -> startMemory(s.level)
            BioExercise.TAP_RHYTHM -> startTap(s.level)
        }
        if (!ok) {
            stopDevices()
            _state.value = _state.value.copy(stage = BioStage.INTRO, live = null,
                error = if (exercise == BioExercise.LOUD_VOICE) "mic" else "sensor")
        }
    }

    private fun startLoud(level: Int): Boolean {
        val engine = LoudVoiceEngine(level).also { loud = it }
        publish(BioLive.Loud(engine.snapshot, engine.targetDb, engine.holdSec, engine.reps))
        return mic.start { timeMs, db ->
            synchronized(lock) {
                if (loud !== engine) return@start
                publish(BioLive.Loud(engine.onLevel(timeMs, db), engine.targetDb, engine.holdSec, engine.reps))
                if (engine.done) finishLater(true)
            }
        }
    }

    private fun startBalance(level: Int): Boolean {
        val engine = BalanceEngine(level).also { balance = it }
        publish(BioLive.Balance(engine.snapshot, engine.radiusDeg, engine.durationSec))
        return accelerometer { ns, x, y, z ->
            synchronized(lock) {
                if (balance !== engine) return@accelerometer
                publish(BioLive.Balance(engine.onAccelerometer(ns, x, y, z), engine.radiusDeg, engine.durationSec))
                if (engine.done) finishLater(true)
            }
        }
    }

    private fun startWalk(level: Int): Boolean {
        val engine = MetronomeWalkEngine(level).also { walk = it }
        var beats = 0
        publish(BioLive.Walk(engine.snapshot, engine.bpm, engine.durationSec, 0))
        val started = accelerometer { ns, x, y, z ->
            synchronized(lock) {
                if (walk !== engine) return@accelerometer
                publish(BioLive.Walk(engine.onAccelerometer(ns, x, y, z), engine.bpm, engine.durationSec, beats))
                if (engine.done) finishLater(true)
            }
        }
        if (!started) return false
        jobs += viewModelScope.launch {
            // On the clock, not after each delay, so the beat does not drift.
            val begin = SystemClock.elapsedRealtime()
            while (isActive) {
                tones?.beat()
                synchronized(lock) {
                    beats++
                    if (walk === engine) publish(BioLive.Walk(engine.snapshot, engine.bpm, engine.durationSec, beats))
                }
                delay((begin + beats * engine.beatMs - SystemClock.elapsedRealtime()).coerceAtLeast(10))
            }
        }
        return true
    }

    /** Only the accelerometer: a phone without a gyroscope can do these exercises too. */
    private fun accelerometer(onSample: (timestampNs: Long, x: Float, y: Float, z: Float) -> Unit): Boolean =
        sensors.startRaw(SENSOR_PERIOD_US, setOf(MotionSensorType.ACCELEROMETER), emptySet()) { type, ns, x, y, z, _ ->
            if (type == MotionSensorType.ACCELEROMETER) onSample(ns, x, y, z)
        }

    private fun startMemory(level: Int): Boolean {
        val engine = MemorySequenceEngine(level).also { memory = it }
        showSequence(engine)
        return true
    }

    private fun showSequence(engine: MemorySequenceEngine) {
        jobs += viewModelScope.launch {
            publish(BioLive.Memory(engine.snapshot, null))
            delay(FIRST_SHOW_DELAY_MS)
            for (cell in engine.snapshot.sequence) {
                tones?.cell(cell)
                publish(BioLive.Memory(engine.snapshot, cell))
                delay(MemorySequenceEngine.SHOW_MS)
                publish(BioLive.Memory(engine.snapshot, null))
                delay(MemorySequenceEngine.GAP_MS)
            }
            engine.shown()
            publish(BioLive.Memory(engine.snapshot, null))
        }
    }

    /** Memory: the person tapped [cell]. */
    fun tapCell(cell: Int) {
        val engine = memory ?: return
        if (engine.snapshot.phase != MemorySequenceEngine.Phase.ANSWERING) return
        tones?.cell(cell)
        val after = engine.tap(cell)
        publish(BioLive.Memory(after, null))
        when {
            engine.done -> finish(true)
            after.phase == MemorySequenceEngine.Phase.SHOWING -> showSequence(engine)
        }
    }

    private fun startTap(level: Int): Boolean {
        val engine = TapRhythmEngine(level).also { tap = it }
        publish(BioLive.Tap(engine.snapshot, engine.durationSec, engine.targetRate, false))
        jobs += viewModelScope.launch {
            while (isActive) {
                delay(100)
                synchronized(lock) {
                    val snap = engine.tick(SystemClock.elapsedRealtime())
                    publish(BioLive.Tap(snap, engine.durationSec, engine.targetRate, engine.handDone))
                }
                if (engine.done) {
                    finish(true)
                    break
                }
            }
        }
        return true
    }

    /** Tapping: a tap on target 0 or 1. */
    fun tapTarget(target: Int) {
        val engine = tap ?: return
        synchronized(lock) {
            publish(BioLive.Tap(engine.tap(target, SystemClock.elapsedRealtime()), engine.durationSec, engine.targetRate, engine.handDone))
        }
    }

    /** Tapping: the right hand is done; the left hand starts with its first tap. */
    fun switchHand() {
        val engine = tap ?: return
        synchronized(lock) {
            engine.switchHand()
            publish(BioLive.Tap(engine.snapshot, engine.durationSec, engine.targetRate, engine.handDone))
        }
    }

    private fun publish(live: BioLive) {
        _state.value = _state.value.copy(live = live)
    }

    /** From a sensor thread: finishes on the main thread. */
    private fun finishLater(completed: Boolean) {
        viewModelScope.launch { finish(completed) }
    }

    /** Ends the session: [completed] false when the person pressed stop. Keeps and sends the result. */
    fun finish(completed: Boolean) {
        val s = _state.value
        if (s.stage != BioStage.RUNNING) return
        val p = s.prescription ?: return
        stopDevices()
        val (score, metrics, durationMs) = synchronized(lock) {
            val elapsed = SystemClock.elapsedRealtime() - startedElapsed
            when (s.exercise) {
                BioExercise.LOUD_VOICE -> loud?.let { Triple(it.score(), it.metrics(), elapsed) }
                BioExercise.BALANCE -> balance?.let { Triple(it.score(), it.metrics(), it.elapsedMs) }
                BioExercise.METRONOME_WALK -> walk?.let { Triple(it.score(), it.metrics(), it.elapsedMs) }
                BioExercise.MEMORY_SEQUENCE -> memory?.let { Triple(it.score(), it.metrics(), elapsed) }
                BioExercise.TAP_RHYTHM -> tap?.let { Triple(it.score(), it.metrics(), elapsed) }
                null -> null
            } ?: Triple(0, emptyMap<String, Double>(), elapsed)
        }
        clearEngines()
        _state.value = s.copy(stage = BioStage.RESULT, result = BioResult(score, completed, metrics, sent = null))
        val session = BioSession(p.id, startedAt, durationMs.toInt().coerceAtLeast(0), score, completed, metrics)
        val profile = profileId ?: return
        viewModelScope.launch {
            val sent = sync.recordSession(profile, session)
            _state.value = _state.value.let { it.copy(result = it.result?.copy(sent = sent)) }
        }
    }

    /** Back to the intro, to do it again. */
    fun again() {
        if (_state.value.stage == BioStage.RUNNING) return
        _state.value = _state.value.copy(stage = BioStage.INTRO, live = null, result = null, error = null)
    }

    private fun stopDevices() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        mic.stop()
        sensors.stop()
        tones?.release()
        tones = null
    }

    private fun clearEngines() = synchronized(lock) {
        loud = null; balance = null; walk = null; memory = null; tap = null
    }

    /** Leaving the screen in the middle stops the session (kept as not completed). */
    fun leave() {
        if (_state.value.stage == BioStage.RUNNING) finish(false)
        stopDevices()
    }

    override fun onCleared() {
        stopDevices()
    }

    companion object {
        const val SENSOR_PERIOD_US = 20_000
        const val FIRST_SHOW_DELAY_MS = 1200L

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ParkinsonApplication
                BiofeedbackViewModel(app.planSync, app.activePatient.profile, app.motionSensorSource)
            }
        }
    }
}
