package com.example.parkinson.norms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReferenceNormsTest {

    /** The bundled asset itself (unit tests run in the app module directory). */
    private val norms = ReferenceNorms.parse(File("src/main/assets/${ReferenceNorms.ASSET_PATH}").readText())
    private val comparator = NormComparator(norms)

    @Test
    fun everyRowNamesASourceAndADoi() {
        assertTrue(norms.entries.size >= 20)
        norms.entries.forEach {
            assertTrue(it.source, it.source.isNotBlank())
            assertTrue(it.doi, it.doi.startsWith("10."))
            assertTrue("${it.metric} ${it.ageMin}-${it.ageMax}", it.ageMin <= it.ageMax)
        }
    }

    @Test
    fun cadenceUsesTheRegisteredSexAndAge() {
        val man = NormPerson(age = 74, sex = NormSex.MALE, parkinsonDiagnosed = false)
        val woman = NormPerson(age = 74, sex = NormSex.FEMALE, parkinsonDiagnosed = false)
        assertEquals(103.0, comparator.compare(NormMetric.GAIT_CADENCE, 100.0, man).healthy!!.mean!!, 0.0)
        assertEquals(112.0, comparator.compare(NormMetric.GAIT_CADENCE, 100.0, woman).healthy!!.mean!!, 0.0)
        // The study only covered people aged 70 and over.
        val young = comparator.compare(NormMetric.GAIT_CADENCE, 100.0, NormPerson(45, NormSex.MALE, false))
        assertNull(young.healthy)
        assertEquals(NormVerdict.NO_REFERENCE, young.verdict)
    }

    @Test
    fun cadenceMoreThanOneAndAHalfSdBelowTheMeanIsOutsideTheHealthyRange() {
        val man = NormPerson(74, NormSex.MALE, false)
        assertEquals(NormVerdict.WITHIN_HEALTHY, comparator.compare(NormMetric.GAIT_CADENCE, 95.0, man).verdict)
        val slow = comparator.compare(NormMetric.GAIT_CADENCE, 80.0, man)
        assertEquals(NormVerdict.OUTSIDE_HEALTHY_RANGE, slow.verdict)
        assertEquals((103.0 - 80.0) / 13.0, slow.zHealthy!!, 1e-9)
    }

    @Test
    fun sitToStandUsesTheAgeBandAndThePdFallCutoff() {
        val sixties = NormPerson(65, NormSex.FEMALE, false)
        val eighties = NormPerson(84, NormSex.FEMALE, false)
        assertEquals(11.4, comparator.compare(NormMetric.STS_FIVE_TIMES, 12.0, sixties).healthy!!.mean!!, 0.0)
        assertEquals(NormVerdict.WORSE_THAN_AVERAGE, comparator.compare(NormMetric.STS_FIVE_TIMES, 12.0, sixties).verdict)
        assertEquals(NormVerdict.WITHIN_HEALTHY, comparator.compare(NormMetric.STS_FIVE_TIMES, 12.0, eighties).verdict)
        val slow = comparator.compare(NormMetric.STS_FIVE_TIMES, 17.0, eighties)
        assertEquals(NormVerdict.PAST_CUTOFF, slow.verdict)
        assertEquals(16.0, slow.cutoff!!.cutoff!!, 0.0)
        assertEquals(20.25, slow.parkinson!!.mean!!, 0.0)
    }

    @Test
    fun withoutARegistrationOnlyAgeIndependentValuesAreUsed() {
        val unknown = NormPerson.UNKNOWN
        assertNull(comparator.compare(NormMetric.GAIT_CADENCE, 100.0, unknown).healthy)
        assertNull(comparator.compare(NormMetric.STS_FIVE_TIMES, 12.0, unknown).healthy)
        val tapping = comparator.compare(NormMetric.FT_INTERVAL_CV, 9.5, unknown)
        assertEquals(NormVerdict.WITHIN_HEALTHY, tapping.verdict)
        assertNotNull(tapping.parkinson)
    }

    @Test
    fun tappingRhythmIsComparedWithBothGroups() {
        val person = NormPerson(66, NormSex.MALE, true)
        val irregular = comparator.compare(NormMetric.FT_INTERVAL_CV, 18.0, person)
        assertEquals(NormVerdict.OUTSIDE_HEALTHY_RANGE, irregular.verdict)
        assertEquals((18.0 - 14.0) / 7.0, irregular.zParkinson!!, 1e-9)
    }

    @Test
    fun turnVelocityWithoutSdIsJudgedByTheNearerGroupMean() {
        val p = NormPerson(70, NormSex.MALE, false)
        assertEquals(NormVerdict.WITHIN_HEALTHY, comparator.compare(NormMetric.GAIT_TURN_VELOCITY, 85.0, p).verdict)
        assertEquals(NormVerdict.WORSE_THAN_AVERAGE, comparator.compare(NormMetric.GAIT_TURN_VELOCITY, 76.0, p).verdict)
        assertEquals(NormVerdict.CLOSER_TO_PARKINSON, comparator.compare(NormMetric.GAIT_TURN_VELOCITY, 70.0, p).verdict)
    }

    @Test
    fun cutoffOnlyMeasures() {
        val p = NormPerson(60, NormSex.FEMALE, false)
        assertEquals(NormVerdict.WITHIN_CUTOFF, comparator.compare(NormMetric.PS_CYCLE_RATE, 2.8, p).verdict)
        assertEquals(NormVerdict.PAST_CUTOFF, comparator.compare(NormMetric.PS_CYCLE_RATE, 2.0, p).verdict)
        assertEquals(NormVerdict.WITHIN_CUTOFF, comparator.compare(NormMetric.GAIT_STRIDE_CV, 2.0, p).verdict)
        assertEquals(NormVerdict.PAST_CUTOFF, comparator.compare(NormMetric.GAIT_STRIDE_CV, 3.0, p).verdict)
    }

    @Test
    fun rowsKeptForReferenceAreNotApplied() {
        // Wu 2023's tap-rate cut-off is in the table but not used (tap count did not separate the groups in Paparella 2025).
        assertTrue(norms.all(NormMetric.FT_TAP_RATE).any { !it.applied && it.cutoff == 2.65 })
        assertEquals(NormVerdict.WITHIN_HEALTHY, comparator.compare(NormMetric.FT_TAP_RATE, 2.5, NormPerson.UNKNOWN).verdict)
        assertEquals(NormVerdict.NO_REFERENCE, comparator.compare("oc.cycle_rate", 1.0, NormPerson.UNKNOWN).verdict)
    }

    @Test
    fun tremorFrequencyBands() {
        val p = NormPerson.UNKNOWN
        assertEquals(NormVerdict.PATHOLOGICAL_BAND, comparator.compare(NormMetric.TREMOR_FREQUENCY, 5.0, p).verdict)
        assertEquals(NormVerdict.PHYSIOLOGICAL_BAND, comparator.compare(NormMetric.TREMOR_FREQUENCY, 9.5, p).verdict)
        assertEquals(NormVerdict.OTHER_BAND, comparator.compare(NormMetric.TREMOR_FREQUENCY, 3.0, p).verdict)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aMalformedRowIsRejected() {
        val header = File("src/main/assets/${ReferenceNorms.ASSET_PATH}").readLines().first { !it.startsWith("#") }
        ReferenceNorms.parse("$header\ngait,gait.cadence,steps/min,healthy,any,70")
    }
}
