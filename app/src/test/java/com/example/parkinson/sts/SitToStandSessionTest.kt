package com.example.parkinson.sts

import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.gait.PoseFrameStatus
import com.example.parkinson.gait.PoseLandmarkIndex
import com.example.parkinson.gait.PoseSample
import com.example.parkinson.gait.PosePoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Session flow on SYNTHETIC pose samples, under virtual time. The pose geometry is built from a knee angle and
 * a shin length in a side view (480 x 640 px image), so the real extractor runs on it.
 */
class SitToStandSessionTest {

    private val w = 480
    private val h = 640

    /** Upright pose for posture [p] (0 seated .. 1 standing): knee angle 95 + 80 p, shin 192 px, thigh 200 px. */
    private fun pose(ts: Long, p: Double): PoseSample {
        val theta = Math.toRadians(95.0 + 80.0 * p)
        val knee = 240.0 to 400.0
        val hip = (knee.first + 200.0 * sin(theta)) to (knee.second + 200.0 * cos(theta))
        val shoulder = hip.first to (hip.second - 200.0)
        val ankle = 240.0 to 592.0
        val points = MutableList<PosePoint?>(PoseLandmarkIndex.COUNT) { null }
        fun put(i: Int, px: Pair<Double, Double>) { points[i] = PosePoint(px.first / w, px.second / h, 0.95) }
        put(PoseLandmarkIndex.RIGHT_SHOULDER, shoulder)
        put(PoseLandmarkIndex.RIGHT_HIP, hip)
        put(PoseLandmarkIndex.RIGHT_KNEE, knee)
        put(PoseLandmarkIndex.RIGHT_ANKLE, ankle)
        return PoseSample(ts, points, w, h, PoseFrameStatus.VALID)
    }

    private fun noPose(ts: Long) = PoseSample(ts, null, w, h, PoseFrameStatus.NO_POSE)

    private fun TestScope.session(saved: MutableList<SitToStandResult> = mutableListOf(), onSave: (suspend (SitToStandResult) -> Unit)? = null) =
        SitToStandSession(
            scope = this,
            clock = { testScheduler.currentTime },
            wallClock = { 1_700_000_000_000L },
            newAssessmentId = { "sts-test" },
            config = SitToStandSessionConfig(countdownMs = 1_000L, calibrationMs = 2_000L),
            processingDispatcher = StandardTestDispatcher(testScheduler),
            onCompleted = { result ->
                if (onSave != null) onSave(result) else saved += result
            },
        )

    /** Feeds seated poses for the calibration window. */
    private suspend fun TestScope.calibrate(session: SitToStandSession, p: Double = 0.0, windowMs: Long = 2_000L) {
        session.startCalibration()
        val end = testScheduler.currentTime + windowMs + 100
        while (testScheduler.currentTime < end) {
            session.onPoseSample(pose(testScheduler.currentTime, p))
            delay(33L)
        }
        advanceUntilIdle()
    }

    /**
     * Lets pending work run for a bounded time. The attempt loop never idles on its own while it runs, so
     * advanceUntilIdle() would not return here.
     */
    private fun TestScope.settle() {
        advanceTimeBy(1_500L)
        runCurrent()
    }

    /** Feeds an attempt along the synthetic movement. [pose] may return null to drop a frame. */
    private suspend fun TestScope.performAttempt(
        session: SitToStandSession,
        segments: List<SyntheticSitToStand.Segment> = SyntheticSitToStand.fiveCycles(),
        dropEvery: Int = 0,
    ) {
        val total = SyntheticSitToStand.totalMs(segments)
        val start = testScheduler.currentTime
        var t = 0L
        var i = 0
        while (t <= total) {
            val ts = start + t
            val drop = dropEvery > 0 && i % dropEvery == 0
            session.onPoseSample(if (drop) noPose(ts) else pose(ts, SyntheticSitToStand.postureAt(segments, t)))
            delay(33L)
            t += 33L
            i++
        }
    }

    @Test
    fun fullFlowCalibratesCountsFiveRepetitionsAndSavesOneResult() = runTest {
        val saved = mutableListOf<SitToStandResult>()
        val session = session(saved)
        calibrate(session)
        assertNotNull(session.baseline.value)
        assertEquals(SitToStandState.Idle, session.state.value)

        session.start()
        settle() // countdown finishes; the attempt starts
        performAttempt(session)
        settle()

        val done = session.state.value as SitToStandState.Done
        assertEquals(1, saved.size)
        assertEquals(done.result, saved.single())
        assertEquals(5, done.result.repetitions.size)
        assertEquals(QualityStatus.VALID, done.result.qualityStatus)
        // Total time is the timeline measured from the samples (about 18.7 s), not the wall clock.
        assertEquals(18_725.0, done.result.totalTimeMs.toDouble(), 150.0)
        assertEquals("sts-test", done.result.assessmentId)
    }

    @Test
    fun aSeatedPersonWhoIsNotBentEnoughFailsCalibrationAndCannotStart() = runTest {
        val session = session()
        calibrate(session, p = 1.0) // standing knee: not seated
        assertNull(session.baseline.value)
        assertEquals(CalibrationIssue.NOT_SEATED, session.calibrationIssue.value)
        session.start()
        settle()
        assertEquals(SitToStandState.Idle, session.state.value)
    }

    @Test
    fun abortDuringTheAttemptIsInvalidAndSavesNothing() = runTest {
        val saved = mutableListOf<SitToStandResult>()
        val session = session(saved)
        calibrate(session)
        session.start()
        settle()
        // Feed part of the attempt, then stop it.
        val start = testScheduler.currentTime
        repeat(60) { i ->
            session.onPoseSample(pose(start + i * 33L, 0.0))
            delay(33L)
        }
        session.abort()
        settle()
        assertEquals(
            SitToStandState.Invalid(SitToStandFailure.Engine(SitToStandInvalidReason.INTERRUPTED)),
            session.state.value
        )
        assertTrue(saved.isEmpty())
    }

    @Test
    fun cancellingBeforeTheAttemptReturnsToIdleWithoutAResult() = runTest {
        val saved = mutableListOf<SitToStandResult>()
        val session = session(saved)
        calibrate(session)
        session.start()
        session.reset()
        settle()
        assertEquals(SitToStandState.Idle, session.state.value)
        assertNull(session.baseline.value)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun aLowShareOfValidFramesIsNotSaved() = runTest {
        val saved = mutableListOf<SitToStandResult>()
        val session = session(saved)
        calibrate(session)
        session.start()
        settle()
        // Every third frame has no body: 67 % valid, below the 70 % minimum.
        performAttempt(session, dropEvery = 3)
        settle()
        val state = session.state.value
        assertTrue("state was $state", state is SitToStandState.Invalid)
        assertTrue((state as SitToStandState.Invalid).reason is SitToStandFailure.TooFewValidFrames)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun aFailedSaveIsReportedAsStorageErrorNotAsSaved() = runTest {
        val session = session(onSave = { throw IllegalStateException("disk full") })
        calibrate(session)
        session.start()
        settle()
        performAttempt(session)
        settle()
        assertEquals(SitToStandState.Error(SitToStandError.STORAGE_FAILURE), session.state.value)
    }

    @Test
    fun aTrackingLossDuringTheAttemptEndsItAsInvalid() = runTest {
        val session = session()
        calibrate(session)
        session.start()
        settle()
        val start = testScheduler.currentTime
        session.onPoseSample(pose(start, 0.0))
        // No body for 2 s: more than the 1 s tracking-loss limit.
        repeat(60) { i ->
            session.onPoseSample(noPose(start + 33L * (i + 1)))
            delay(33L)
        }
        settle()
        assertEquals(
            SitToStandState.Invalid(SitToStandFailure.Engine(SitToStandInvalidReason.TRACKING_LOST)),
            session.state.value
        )
    }

    @Test
    fun guideShowsTheShareOfRecentFramesWithABody() = runTest {
        val session = session()
        session.onPoseSample(pose(0L, 0.0))
        session.onPoseSample(noPose(33L))
        assertEquals(50.0, requireNotNull(session.guideValidPercent.value), 1e-9)
    }
}
