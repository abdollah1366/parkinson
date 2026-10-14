package com.example.parkinson.plan

import com.example.parkinson.biofeedback.BioSession
import com.example.parkinson.sync.ServerException
import com.example.parkinson.sync.ServerLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanSyncTest {
    private class FakePlanApi : PlanApi {
        var plan = listOf(Prescription(5, "دکتر راوی", PlanKind.MEDICATION, "لوودوپا", null, listOf("08:00"), null, null, "2026-10-01", null, 0))
        var offline = false
        var revoked = false
        val recorded = mutableListOf<Intake>()
        val sessions = mutableListOf<BioSession>()

        private fun check() {
            if (revoked) throw ServerException(ServerException.UNAUTHORIZED, 401)
            if (offline) throw ServerException(ServerException.NETWORK)
        }

        override suspend fun prescriptions(serverUrl: String, token: String): List<Prescription> {
            check()
            return plan
        }

        override suspend fun record(serverUrl: String, token: String, intakes: List<Intake>) {
            check()
            recorded += intakes
        }

        override suspend fun sendSessions(serverUrl: String, token: String, sessions: List<BioSession>) {
            check()
            this.sessions += sessions
        }
    }

    private val api = FakePlanApi()
    private val store = InMemoryPlanStore(clock = { 10_000L })
    private val links = MutableStateFlow(mapOf(1 to ServerLink("https://clinic.example", "t", "دکتر راوی", 0, 0)))
    private var changes = 0
    private val sync = PlanSync(api, store, links) { changes++ }

    @Test
    fun thePlanIsKeptAndMarksMadeOfflineAreSentLater() = runTest {
        sync.refresh(1, "علی")
        assertEquals("لوودوپا", store.plans()[1]!!.prescriptions.single().name)
        assertEquals(1, changes)

        api.offline = true
        assertFalse(sync.mark(1, Intake(5, 1000, IntakeStatus.TAKEN)))
        assertEquals(1, store.pending(1).size)
        // Marked again (changed its mind): only the last mark is sent.
        assertFalse(sync.mark(1, Intake(5, 1000, IntakeStatus.MISSED)))
        assertEquals(IntakeStatus.MISSED, store.marks(1)["5@1000"]!!.status)

        api.offline = false
        sync.refresh(1, "علی")
        assertEquals(listOf(Intake(5, 1000, IntakeStatus.MISSED)), api.recorded)
        assertTrue(store.pending(1).isEmpty())
        assertTrue(sync.mark(1, Intake(5, 2000, IntakeStatus.TAKEN)))
    }

    @Test
    fun aBiofeedbackSessionIsKeptSentAndCountsAsDone() = runTest {
        api.offline = true
        val session = BioSession(7, 5000, 60_000, 72, completed = true, metrics = mapOf("insidePercent" to 72.0))
        assertFalse(sync.recordSession(1, session))
        assertEquals(listOf(session), store.sessions(1))
        assertEquals(IntakeStatus.DONE, store.marks(1)["7@5000"]!!.status)

        api.offline = false
        sync.flush(1)
        assertEquals(listOf(session), api.sessions)
        assertTrue(store.pendingSessions(1).isEmpty())
        assertEquals(listOf(Intake(7, 5000, IntakeStatus.DONE)), api.recorded)
        // Stopped early: kept, sent, but not counted as done.
        assertTrue(sync.recordSession(1, session.copy(startedAtEpochMs = 9000, completed = false)))
        assertEquals(null, store.marks(1)["7@9000"])
        assertEquals(2, store.sessions(1).size)
    }

    @Test
    fun aDisconnectedPersonsPlanIsDropped() = runTest {
        sync.refresh(1, "علی")
        api.revoked = true
        try {
            sync.refresh(1, "علی")
        } catch (e: ServerException) {
            // Expected: the screen says the server refused.
        }
        assertTrue(store.plans().isEmpty())

        api.revoked = false
        sync.refresh(1, "علی")
        links.value = emptyMap()
        sync.prune()
        assertTrue(store.plans().isEmpty())
    }
}
