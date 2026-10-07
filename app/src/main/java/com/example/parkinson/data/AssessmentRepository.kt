package com.example.parkinson.data

import com.example.parkinson.tapping.result.FingerTappingAssessment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface AssessmentRepository {
    suspend fun save(assessment: FingerTappingAssessment)
    fun observeLatest(): Flow<FingerTappingAssessment?>
    fun observeAll(): Flow<List<FingerTappingAssessment>>
    fun observe(assessmentId: String): Flow<FingerTappingAssessment?>
}

class RoomAssessmentRepository(private val dao: AssessmentDao) : AssessmentRepository {

    override suspend fun save(assessment: FingerTappingAssessment) = dao.insert(assessment.toEntity())

    override fun observeLatest(): Flow<FingerTappingAssessment?> = dao.observeLatest().map { it?.toDomain() }

    override fun observeAll(): Flow<List<FingerTappingAssessment>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observe(assessmentId: String): Flow<FingerTappingAssessment?> =
        dao.observeById(assessmentId).map { it?.toDomain() }
}
