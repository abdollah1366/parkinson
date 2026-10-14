package com.example.parkinson.data

import com.example.parkinson.assessment.AssessmentResult
import com.example.parkinson.dualtask.DualTaskResult
import com.example.parkinson.gait.GaitResult
import com.example.parkinson.openclose.HandOpenCloseResult
import com.example.parkinson.pronation.PronationSupinationResult
import com.example.parkinson.imu.ImuGaitResult
import com.example.parkinson.medication.MedicationState
import com.example.parkinson.imu.ImuSitToStandResult
import com.example.parkinson.speech.SpeechResult
import com.example.parkinson.sts.SitToStandResult
import com.example.parkinson.stability.HandStabilityResult
import com.example.parkinson.tapping.result.FingerTappingAssessment
import com.example.parkinson.tremor.RestingTremorImuResult
import com.example.parkinson.tremor.RestingTremorResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

interface AssessmentRepository {
    suspend fun save(assessment: FingerTappingAssessment)
    fun observeLatest(): Flow<FingerTappingAssessment?>
    fun observeAll(): Flow<List<FingerTappingAssessment>>
    fun observe(assessmentId: String): Flow<FingerTappingAssessment?>

    suspend fun saveHandStability(result: HandStabilityResult)
    fun observeHandStability(assessmentId: String): Flow<HandStabilityResult?>

    suspend fun savePronationSupination(result: PronationSupinationResult)
    fun observePronationSupination(assessmentId: String): Flow<PronationSupinationResult?>

    suspend fun saveHandOpenClose(result: HandOpenCloseResult)
    fun observeHandOpenClose(assessmentId: String): Flow<HandOpenCloseResult?>

    suspend fun saveRestingTremor(result: RestingTremorResult)
    fun observeRestingTremor(assessmentId: String): Flow<RestingTremorResult?>

    suspend fun saveRestingTremorImu(result: RestingTremorImuResult)
    fun observeRestingTremorImu(assessmentId: String): Flow<RestingTremorImuResult?>

    suspend fun saveGait(result: GaitResult)
    fun observeGait(assessmentId: String): Flow<GaitResult?>

    suspend fun saveSitToStand(result: SitToStandResult)
    fun observeSitToStand(assessmentId: String): Flow<SitToStandResult?>

    /** Every stored Sit-to-Stand result, newest first (for comparison with earlier sessions). */
    fun observeAllSitToStand(): Flow<List<SitToStandResult>>

    suspend fun saveSpeech(result: SpeechResult)
    fun observeSpeech(assessmentId: String): Flow<SpeechResult?>

    /** Every stored speech result, newest first (for comparison with earlier sessions of the same task). */
    fun observeAllSpeech(): Flow<List<SpeechResult>>

    fun observeAllImuSitToStand(): Flow<List<ImuSitToStandResult>>

    suspend fun saveImuSitToStand(result: ImuSitToStandResult)
    fun observeImuSitToStand(assessmentId: String): Flow<ImuSitToStandResult?>

    fun observeAllImuGait(): Flow<List<ImuGaitResult>>

    suspend fun saveImuGait(result: ImuGaitResult)
    fun observeImuGait(assessmentId: String): Flow<ImuGaitResult?>

    suspend fun saveDualTask(result: DualTaskResult)
    fun observeDualTask(assessmentId: String): Flow<DualTaskResult?>

    /** Results of every assessment type, newest first. */
    fun observeHistory(): Flow<List<AssessmentResult>>

    /** [observeHistory] with the medication state reported before each test. */
    fun observeStoredHistory(): Flow<List<StoredResult>>
}

/** A stored result with the medication state reported before the test (null when it was not asked). */
data class StoredResult(val result: AssessmentResult, val medicationState: MedicationState?)

class RoomAssessmentRepository(
    private val dao: AssessmentDao,
    private val stabilityDao: HandStabilityDao,
    private val pronationDao: PronationSupinationDao,
    private val openCloseDao: HandOpenCloseDao,
    private val restingTremorDao: RestingTremorDao,
    private val gaitDao: GaitDao,
    private val sitToStandDao: SitToStandDao,
    private val speechDao: SpeechDao,
    private val imuSitToStandDao: ImuSitToStandDao,
    private val imuGaitDao: ImuGaitDao,
    private val restingTremorImuDao: RestingTremorImuDao,
    private val dualTaskDao: DualTaskDao,
    /** The person whose results are shown and to whom new results belong (the app's active patient). */
    private val activeProfileId: Flow<Int?> = flowOf(null),
    /** The medication state the person reported for the test being saved, or null when not known. */
    private val medicationStateNow: suspend (profileId: Int?) -> MedicationState? = { null },
) : AssessmentRepository {

    override suspend fun save(assessment: FingerTappingAssessment) =
        dao.insert(stamp { pid, med -> assessment.toEntity().copy(profileId = pid, medicationState = med) })

    override fun observeLatest(): Flow<FingerTappingAssessment?> =
        dao.observeAll().mine().map { it.firstOrNull()?.toDomain() }

    override fun observeAll(): Flow<List<FingerTappingAssessment>> =
        dao.observeAll().mine().map { list -> list.map { it.toDomain() } }

    override fun observe(assessmentId: String): Flow<FingerTappingAssessment?> =
        dao.observeById(assessmentId).map { it?.toDomain() }

    override suspend fun saveHandStability(result: HandStabilityResult) =
        stabilityDao.insert(stamp { pid, med -> result.toEntity().copy(profileId = pid, medicationState = med) })

    override fun observeHandStability(assessmentId: String): Flow<HandStabilityResult?> =
        stabilityDao.observeById(assessmentId).map { it?.toDomain() }

    override suspend fun savePronationSupination(result: PronationSupinationResult) =
        pronationDao.insert(stamp { pid, med -> result.toEntity().copy(profileId = pid, medicationState = med) })

    override fun observePronationSupination(assessmentId: String): Flow<PronationSupinationResult?> =
        pronationDao.observeById(assessmentId).map { it?.toDomain() }

    override suspend fun saveHandOpenClose(result: HandOpenCloseResult) =
        openCloseDao.insert(stamp { pid, med -> result.toEntity().copy(profileId = pid, medicationState = med) })

    override fun observeHandOpenClose(assessmentId: String): Flow<HandOpenCloseResult?> =
        openCloseDao.observeById(assessmentId).map { it?.toDomain() }

    override suspend fun saveRestingTremor(result: RestingTremorResult) =
        restingTremorDao.insert(stamp { pid, med -> result.toEntity().copy(profileId = pid, medicationState = med) })

    override fun observeRestingTremor(assessmentId: String): Flow<RestingTremorResult?> =
        restingTremorDao.observeById(assessmentId).map { it?.toDomain() }

    override suspend fun saveRestingTremorImu(result: RestingTremorImuResult) =
        restingTremorImuDao.insert(stamp { pid, med -> result.toEntity().copy(profileId = pid, medicationState = med) })

    override fun observeRestingTremorImu(assessmentId: String): Flow<RestingTremorImuResult?> =
        restingTremorImuDao.observeById(assessmentId).map { it?.toDomain() }

    override suspend fun saveGait(result: GaitResult) =
        gaitDao.insert(stamp { pid, med -> result.toEntity().copy(profileId = pid, medicationState = med) })

    override fun observeGait(assessmentId: String): Flow<GaitResult?> =
        gaitDao.observeById(assessmentId).map { it?.toDomain() }

    override suspend fun saveSitToStand(result: SitToStandResult) =
        sitToStandDao.insert(stamp { pid, med -> result.toEntity().copy(profileId = pid, medicationState = med) })

    override fun observeSitToStand(assessmentId: String): Flow<SitToStandResult?> =
        sitToStandDao.observeById(assessmentId).map { it?.toDomain() }

    override fun observeAllSitToStand(): Flow<List<SitToStandResult>> =
        sitToStandDao.observeAll().mine().map { list -> list.map { it.toDomain() } }

    override suspend fun saveSpeech(result: SpeechResult) =
        speechDao.insert(stamp { pid, med -> result.toEntity().copy(profileId = pid, medicationState = med) })

    override fun observeSpeech(assessmentId: String): Flow<SpeechResult?> =
        speechDao.observeById(assessmentId).map { it?.toDomain() }

    override fun observeAllSpeech(): Flow<List<SpeechResult>> =
        speechDao.observeAll().mine().map { list -> list.map { it.toDomain() } }

    override fun observeAllImuSitToStand(): Flow<List<ImuSitToStandResult>> =
        imuSitToStandDao.observeAll().mine().map { list -> list.map { it.toDomain() } }

    override suspend fun saveImuSitToStand(result: ImuSitToStandResult) =
        imuSitToStandDao.insert(stamp { pid, med -> result.toEntity().copy(profileId = pid, medicationState = med) })

    override fun observeImuSitToStand(assessmentId: String): Flow<ImuSitToStandResult?> =
        imuSitToStandDao.observeById(assessmentId).map { it?.toDomain() }

    override fun observeAllImuGait(): Flow<List<ImuGaitResult>> =
        imuGaitDao.observeAll().mine().map { list -> list.map { it.toDomain() } }

    override suspend fun saveImuGait(result: ImuGaitResult) =
        imuGaitDao.insert(stamp { pid, med -> result.toEntity().copy(profileId = pid, medicationState = med) })

    override fun observeImuGait(assessmentId: String): Flow<ImuGaitResult?> =
        imuGaitDao.observeById(assessmentId).map { it?.toDomain() }

    override suspend fun saveDualTask(result: DualTaskResult) =
        dualTaskDao.insert(stamp { pid, med -> result.toEntity().copy(profileId = pid, medicationState = med) })

    override fun observeDualTask(assessmentId: String): Flow<DualTaskResult?> =
        dualTaskDao.observeById(assessmentId).map { it?.toDomain() }

    override fun observeHistory(): Flow<List<AssessmentResult>> =
        observeStoredHistory().map { list -> list.map { it.result } }

    override fun observeStoredHistory(): Flow<List<StoredResult>> =
        combine(
            combine(
                dao.observeAll().mine(), stabilityDao.observeAll().mine(),
                pronationDao.observeAll().mine(), openCloseDao.observeAll().mine(),
                restingTremorDao.observeAll().mine(),
            ) { tapping, stability, pronation, openClose, tremor ->
                val all: List<StoredResult> = tapping.stored { it.toDomain() } + stability.stored { it.toDomain() } +
                    pronation.stored { it.toDomain() } + openClose.stored { it.toDomain() } + tremor.stored { it.toDomain() }
                all
            },
            gaitDao.observeAll().mine()
        ) { others: List<StoredResult>, gait: List<GaitEntity> ->
            others + gait.stored { it.toDomain() }
        }.let { withGait ->
            combine(
                withGait, sitToStandDao.observeAll().mine(), speechDao.observeAll().mine(),
            ) { others: List<StoredResult>, sts: List<SitToStandEntity>, speech: List<SpeechEntity>,
                ->
                others + sts.stored { it.toDomain() } + speech.stored { it.toDomain() }
            }
        }.let { withSpeech ->
            combine(
                withSpeech, imuSitToStandDao.observeAll().mine(),
                imuGaitDao.observeAll().mine(), restingTremorImuDao.observeAll().mine(),
                dualTaskDao.observeAll().mine(),
            ) { others: List<StoredResult>, imuSts: List<ImuSitToStandEntity>, imuGait: List<ImuGaitEntity>,
                imuTremor: List<RestingTremorImuEntity>, dualTask: List<DualTaskEntity> ->
                (others + imuSts.stored { it.toDomain() } + imuGait.stored { it.toDomain() } +
                    imuTremor.stored { it.toDomain() } + dualTask.stored { it.toDomain() })
                    .sortedByDescending { it.result.timestampEpochMs }
            }
        }

    /** Builds the entity to save for the active person, with the medication state they reported (if any). */
    private suspend fun <E> stamp(build: (profileId: Int?, medicationState: String?) -> E): E {
        val profileId = activeProfileId.first()
        return build(profileId, medicationStateNow(profileId)?.id)
    }

    private fun <E : OwnedResultRow> List<E>.stored(toDomain: (E) -> AssessmentResult): List<StoredResult> =
        map { StoredResult(toDomain(it), MedicationState.fromId(it.medicationState)) }

    /** Only the rows of the active person, so one person's results are never shown or compared as another's. */
    private fun <E : OwnedResultRow> Flow<List<E>>.mine(): Flow<List<E>> =
        combine(this, activeProfileId) { rows, active -> rows.filter { it.profileId == active } }

    companion object {
        fun from(
            db: AssessmentDatabase,
            activeProfileId: Flow<Int?> = flowOf(null),
            medicationStateNow: suspend (profileId: Int?) -> MedicationState? = { null },
        ) = RoomAssessmentRepository(
            db.assessmentDao(), db.handStabilityDao(), db.pronationSupinationDao(), db.handOpenCloseDao(),
            db.restingTremorDao(), db.gaitDao(), db.sitToStandDao(), db.speechDao(), db.imuSitToStandDao(), db.imuGaitDao(),
            db.restingTremorImuDao(), db.dualTaskDao(), activeProfileId, medicationStateNow,
        )
    }
}
