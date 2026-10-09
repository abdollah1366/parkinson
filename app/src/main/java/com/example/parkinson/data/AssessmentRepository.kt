package com.example.parkinson.data

import com.example.parkinson.assessment.AssessmentResult
import com.example.parkinson.gait.GaitResult
import com.example.parkinson.openclose.HandOpenCloseResult
import com.example.parkinson.pronation.PronationSupinationResult
import com.example.parkinson.sts.SitToStandResult
import com.example.parkinson.stability.HandStabilityResult
import com.example.parkinson.tapping.result.FingerTappingAssessment
import com.example.parkinson.tremor.RestingTremorResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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

    suspend fun saveGait(result: GaitResult)
    fun observeGait(assessmentId: String): Flow<GaitResult?>

    suspend fun saveSitToStand(result: SitToStandResult)
    fun observeSitToStand(assessmentId: String): Flow<SitToStandResult?>

    /** Every stored Sit-to-Stand result, newest first (for comparison with earlier sessions). */
    fun observeAllSitToStand(): Flow<List<SitToStandResult>>

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
) : AssessmentRepository {

    override suspend fun save(assessment: FingerTappingAssessment) = dao.insert(assessment.toEntity())

    override fun observeLatest(): Flow<FingerTappingAssessment?> = dao.observeLatest().map { it?.toDomain() }

    override fun observeAll(): Flow<List<FingerTappingAssessment>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observe(assessmentId: String): Flow<FingerTappingAssessment?> =
        dao.observeById(assessmentId).map { it?.toDomain() }

    override suspend fun saveHandStability(result: HandStabilityResult) = stabilityDao.insert(result.toEntity())

    override fun observeHandStability(assessmentId: String): Flow<HandStabilityResult?> =
        stabilityDao.observeById(assessmentId).map { it?.toDomain() }

    override suspend fun savePronationSupination(result: PronationSupinationResult) = pronationDao.insert(result.toEntity())

    override fun observePronationSupination(assessmentId: String): Flow<PronationSupinationResult?> =
        pronationDao.observeById(assessmentId).map { it?.toDomain() }

    override suspend fun saveHandOpenClose(result: HandOpenCloseResult) = openCloseDao.insert(result.toEntity())

    override fun observeHandOpenClose(assessmentId: String): Flow<HandOpenCloseResult?> =
        openCloseDao.observeById(assessmentId).map { it?.toDomain() }

    override suspend fun saveRestingTremor(result: RestingTremorResult) = restingTremorDao.insert(result.toEntity())

    override fun observeRestingTremor(assessmentId: String): Flow<RestingTremorResult?> =
        restingTremorDao.observeById(assessmentId).map { it?.toDomain() }

    override suspend fun saveGait(result: GaitResult) = gaitDao.insert(result.toEntity())

    override fun observeGait(assessmentId: String): Flow<GaitResult?> =
        gaitDao.observeById(assessmentId).map { it?.toDomain() }

    override suspend fun saveSitToStand(result: SitToStandResult) = sitToStandDao.insert(result.toEntity())

    override fun observeSitToStand(assessmentId: String): Flow<SitToStandResult?> =
        sitToStandDao.observeById(assessmentId).map { it?.toDomain() }

    override fun observeAllSitToStand(): Flow<List<SitToStandResult>> =
        sitToStandDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeHistory(): Flow<List<AssessmentResult>> =
        combine(
            combine(
                dao.observeAll(), stabilityDao.observeAll(), pronationDao.observeAll(),
                openCloseDao.observeAll(), restingTremorDao.observeAll()
            ) { tapping, stability, pronation, openClose, tremor ->
                val all: List<AssessmentResult> = tapping.map { it.toDomain() } + stability.map { it.toDomain() } +
                    pronation.map { it.toDomain() } + openClose.map { it.toDomain() } + tremor.map { it.toDomain() }
                all
            },
            gaitDao.observeAll()
        ) { others: List<AssessmentResult>, gait: List<GaitEntity> ->
            others + gait.map { it.toDomain() }
        }.let { withGait ->
            combine(withGait, sitToStandDao.observeAll()) { others: List<AssessmentResult>, sts: List<SitToStandEntity> ->
                (others + sts.map { it.toDomain() }).sortedByDescending { it.timestampEpochMs }
            }
        }

    companion object {
        fun from(db: AssessmentDatabase) = RoomAssessmentRepository(
            db.assessmentDao(), db.handStabilityDao(), db.pronationSupinationDao(), db.handOpenCloseDao(),
            db.restingTremorDao(), db.gaitDao(), db.sitToStandDao()
        )
    }
}
