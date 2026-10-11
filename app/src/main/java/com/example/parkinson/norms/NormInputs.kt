package com.example.parkinson.norms

import com.example.parkinson.imu.ImuGaitResult
import com.example.parkinson.imu.ImuSitToStandResult
import com.example.parkinson.profile.Gender
import com.example.parkinson.profile.UserProfile
import com.example.parkinson.pronation.PronationSupinationResult
import com.example.parkinson.tapping.result.FingerTappingAssessment

/** The registered age, sex and diagnosis; [NormPerson.UNKNOWN] without a registration. */
fun UserProfile?.toNormPerson(): NormPerson = if (this == null) NormPerson.UNKNOWN else NormPerson(
    age = age,
    sex = when (gender) {
        Gender.MALE -> NormSex.MALE
        Gender.FEMALE -> NormSex.FEMALE
    },
    parkinsonDiagnosed = parkinsonDiagnosed,
)

/** The measures of each result that have a published reference, compared for [person]. Unmeasured values are skipped. */
fun NormComparator.forGait(r: ImuGaitResult, person: NormPerson): List<NormComparison> = listOfNotNull(
    r.cadenceStepsPerMinute?.let { compare(NormMetric.GAIT_CADENCE, it, person) },
    r.strideCvPercent?.let { compare(NormMetric.GAIT_STRIDE_CV, it, person) },
    r.meanTurnVelocityDegPerSec?.let { compare(NormMetric.GAIT_TURN_VELOCITY, it, person) },
)

/** Only a complete five-repetition trial matches the published five-times sit-to-stand values. */
fun NormComparator.forSitToStand(r: ImuSitToStandResult, person: NormPerson): List<NormComparison> =
    if (r.targetRepetitions != 5 || r.repetitions.size < 5 || r.totalTimeMs <= 0) emptyList()
    else listOf(compare(NormMetric.STS_FIVE_TIMES, r.totalTimeMs / 1000.0, person))

fun NormComparator.forFingerTapping(a: FingerTappingAssessment, person: NormPerson): List<NormComparison> = listOfNotNull(
    a.tapRateHz.takeIf { it > 0.0 }?.let { compare(NormMetric.FT_TAP_RATE, it, person) },
    a.intervalCvPercent?.let { compare(NormMetric.FT_INTERVAL_CV, it, person) },
)

fun NormComparator.forPronation(r: PronationSupinationResult, person: NormPerson): List<NormComparison> =
    listOfNotNull(r.cyclesPerSecond.takeIf { it > 0.0 }?.let { compare(NormMetric.PS_CYCLE_RATE, it, person) })

fun NormComparator.forTremorFrequency(hz: Double?, person: NormPerson): List<NormComparison> =
    listOfNotNull(hz?.takeIf { it > 0.0 }?.let { compare(NormMetric.TREMOR_FREQUENCY, it, person) })
