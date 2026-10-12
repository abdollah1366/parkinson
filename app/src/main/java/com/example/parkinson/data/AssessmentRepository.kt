package com.example.parkinson.data

import com.example.parkinson.assessment.AssessmentResult
import com.example.parkinson.dualtask.DualTaskResult
import com.example.parkinson.gait.GaitResult
import com.example.parkinson.openclose.HandOpenCloseResult
import com.example.parkinson.pronation.PronationSupinationResult
import com.example.parkinson.imu.ImuGaitResult
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
}

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
) : AssessmentRepository {

    override suspend fun save(assessment: FingerTappingAssessment) =
        dao.insert(assessment.toEntity().copy(profileId = currentProfileId()))

    override fun observeLatest(): Flow<FingerTappingAssessment?> =
        dao.observeAll().mine { it.profileId }.map { it.firstOrNull()?.toDomain() }

    override fun observeAll(): Flow<List<FingerTappingAssessment>> =
        dao.observeAll().mine { it.profileId }.map { list -> list.map { it.toDomain() } }

    override fun observe(assessmentId: String): Flow<FingerTappingAssessment?> =
        dao.observeById(assessmentId).map { it?.toDomain() }

    override suspend fun saveHandStability(result: HandStabilityResult) =
        stabilityDao.insert(result.toEntity().copy(profileId = currentProfileId()))

    override fun observeHandStability(assessmentId: String): Flow<HandStabilityResult?> =
        stabilityDao.observeById(assessmentId).map { it?.toDomain() }

    override suspend fun savePronationSupination(result: PronationSupinationResult) =
        pronationDao.insert(result.toEntity().copy(profileId = currentProfileId()))

    override fun observePronationSupination(assessmentId: String): Flow<PronationSupinationResult?> =
        pronationDao.observeById(assessmentId).map { it?.toDomain() }

    override suspend fun saveHandOpenClose(result: HandOpenCloseResult) =
        openCloseDao.insert(result.toEntity().copy(profileId = currentProfileId()))

    override fun observeHandOpenClose(assessmentId: String): Flow<HandOpenCloseResult?> =
        openCloseDao.observeById(assessmentId).map { it?.toDomain() }

    override suspend fun saveRestingTremor(result: RestingTremorResult) =
        restingTremorDao.insert(result.toEntity().copy(profileId = currentProfileId()))

    override fun observeRestingTremor(assessmentId: String): Flow<RestingTremorResult?> =
        restingTremorDao.observeById(assessmentId).map { it?.toDomain() }

    override suspend fun saveRestingTremorImu(result: RestingTremorImuResult) =
        restingTremorImuDao.insert(result.toEntity().copy(profileId = currentProfileId()))

    override fun observeRestingTremorImu(assessmentId: String): Flow<RestingTremorImuResult?> =
        restingTremorImuDao.observeById(assessmentId).map { it?.toDomain() }

    override suspend fun saveGait(result: GaitResult) =
        gaitDao.insert(result.toEntity().copy(profileId = currentProfileId()))

    override fun observeGait(assessmentId: String): Flow<GaitResult?> =
        gaitDao.observeById(assessmentId).map { it?.toDomain() }

    override suspend fun saveSitToStand(result: SitToStandResult) =
        sitToStandDao.insert(result.toEntity().copy(profileId = currentProfileId()))

    override fun observeSitToStand(assessmentId: String): Flow<SitToStandResult?> =
        sitToStandDao.observeById(assessmentId).map { it?.toDomain() }

    override fun observeAllSitToStand(): Flow<List<SitToStandResult>> =
        sitToStandDao.observeAll().mine { it.profileId }.map { list -> list.map { it.toDomain() } }

    override suspend fun saveSpeech(result: SpeechResult) =
        speechDao.insert(result.toEntity().copy(profileId = currentProfileId()))

    override fun observeSpeech(assessmentId: String): Flow<SpeechResult?> =
        speechDao.observeById(assessmentId).map { it?.toDomain() }

    override fun observeAllSpeech(): Flow<List<SpeechResult>> =
        speechDao.observeAll().mine { it.profileId }.map { list -> list.map { it.toDomain() } }

    override fun observeAllImuSitToStand(): Flow<List<ImuSitToStandResult>> =
        imuSitToStandDao.observeAll().mine { it.profileId }.map { list -> list.map { it.toDomain() } }

    override suspend fun saveImuSitToStand(result: ImuSitToStandResult) =
        imuSitToStandDao.insert(result.toEntity().copy(profileId = currentProfileId()))

    override fun observeImuSitToStand(assessmentId: String): Flow<ImuSitToStandResult?> =
        imuSitToStandDao.observeById(assessmentId).map { it?.toDomain() }

    override fun observeAllImuGait(): Flow<List<ImuGaitResult>> =
        imuGaitDao.observeAll().mine { it.profileId }.map { list -> list.map { it.toDomain() } }

    override suspend fun saveImuGait(result: ImuGaitResult) =
        imuGaitDao.insert(result.toEntity().copy(profileId = currentProfileId()))

    override fun observeImuGait(assessmentId: String): Flow<ImuGaitResult?> =
        imuGaitDao.observeById(assessmentId).map { it?.toDomain() }

    override suspend fun saveDualTask(result: DualTaskResult) =
        dualTaskDao.insert(result.toEntity().copy(profileId = currentProfileId()))

    override fun observeDualTask(assessmentId: String): Flow<DualTaskResult?> =
        dualTaskDao.observeById(assessmentId).map { it?.toDomain() }

    override fun observeHistory(): Flow<List<AssessmentResult>> =
        combine(
            combine(
                dao.observeAll().mine { it.profileId }, stabilityDao.observeAll().mine { it.profileId },
                pronationDao.observeAll().mine { it.profileId }, openCloseDao.observeAll().mine { it.profileId },
                restingTremorDao.observeAll().mine { it.profileId },
            ) { tapping, stability, pronation, openClose, tremor ->
                val all: List<AssessmentResult> = tapping.map { it.toDomain() } + stability.map { it.toDomain() } +
                    pronation.map { it.toDomain() } + openClose.map { it.toDomain() } + tremor.map { it.toDomain() }
                all
            },
            gaitDao.observeAll().mine { it.profileId }
        ) { others: List<AssessmentResult>, gait: List<GaitEntity> ->
            others + gait.map { it.toDomain() }
        }.let { withGait ->
            combine(
                withGait, sitToStandDao.observeAll().mine { it.profileId }, speechDao.observeAll().mine { it.profileId },
            ) { others: List<AssessmentResult>, sts: List<SitToStandEntity>, speech: List<SpeechEntity>,
                ->
                others + sts.map { it.toDomain() } + speech.map { it.toDomain() }
            }
        }.let { withSpeech ->
            combine(
                withSpeech, imuSitToStandDao.observeAll().mine { it.profileId },
                imuGaitDao.observeAll().mine { it.profileId }, restingTremorImuDao.observeAll().mine { it.profileId },
                dualTaskDao.observeAll().mine { it.profileId },
            ) { others: List<AssessmentResult>, imuSts: List<ImuSitToStandEntity>, imuGait: List<ImuGaitEntity>,
                imuTremor: List<RestingTremorImuEntity>, dualTask: List<DualTaskEntity> ->
                (others + imuSts.map { it.toDomain() } + imuGait.map { it.toDomain() } + imuTremor.map { it.toDomain() } +
                    dualTask.map { it.toDomain() })
                    .sortedByDescending { it.timestampEpochMs }
            }
        }

    /** The person new results are saved for (null while nobody is registered). */
    private suspend fun currentProfileId(): Int? = activeProfileId.first()

    /** Only the rows of the active person, so one person's results are never shown or compared as another's. */
    private fun <E> Flow<List<E>>.mine(profileOf: (E) -> Int?): Flow<List<E>> =
        combine(this, activeProfileId) { rows, active -> rows.filter { profileOf(it) == active } }

    companion object {
        fun from(db: AssessmentDatabase, activeProfileId: Flow<Int?> = flowOf(null)) = RoomAssessmentRepository(
            db.assessmentDao(), db.handStabilityDao(), db.pronationSupinationDao(), db.handOpenCloseDao(),
            db.restingTremorDao(), db.gaitDao(), db.sitToStandDao(), db.speechDao(), db.imuSitToStandDao(), db.imuGaitDao(),
            db.restingTremorImuDao(), db.dualTaskDao(), activeProfileId,
        )
    }
}
