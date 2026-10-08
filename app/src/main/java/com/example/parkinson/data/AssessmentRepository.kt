package com.example.parkinson.data

import com.example.parkinson.assessment.AssessmentResult
import com.example.parkinson.stability.HandStabilityResult
import com.example.parkinson.tapping.result.FingerTappingAssessment
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

    /** Results of every assessment type, newest first. */
    fun observeHistory(): Flow<List<AssessmentResult>>
}

class RoomAssessmentRepository(
    private val dao: AssessmentDao,
    private val stabilityDao: HandStabilityDao
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

    override fun observeHistory(): Flow<List<AssessmentResult>> =
        combine(dao.observeAll(), stabilityDao.observeAll()) { tapping, stability ->
            (tapping.map { it.toDomain() } + stability.map { it.toDomain() })
                .sortedByDescending { it.timestampEpochMs }
        }

    companion object {
        fun from(db: AssessmentDatabase) = RoomAssessmentRepository(db.assessmentDao(), db.handStabilityDao())
    }
}
