package com.example.parkinson.sync

import com.example.parkinson.data.ResultRow
import com.example.parkinson.data.StoredResult
import com.example.parkinson.profile.UserProfile
import org.json.JSONObject

/** What is sent, in the server's field names (server/.../Models.kt: PatientIn, ResultIn). */
object SyncJson {

    fun patient(p: UserProfile): JSONObject = JSONObject()
        .put("firstName", p.firstName)
        .put("lastName", p.lastName)
        .put("age", p.age)
        .put("gender", p.gender.id)
        .put("mobile", p.mobile)
        .put("nationalId", p.nationalId)
        .put("parkinsonDiagnosed", p.parkinsonDiagnosed)
        .put("takesParkinsonMedication", p.takesParkinsonMedication ?: JSONObject.NULL)
        .put("dominantHand", p.dominantHand.id)
        .put("consentEpochMs", p.consentEpochMs)

    /** The summary fields from [stored]; the test's own measurements (every stored column) from [row]. */
    fun result(stored: StoredResult, row: ResultRow): JSONObject {
        val r = stored.result
        return JSONObject()
            .put("assessmentId", r.assessmentId)
            .put("type", r.type.id)
            .put("timestampEpochMs", r.timestampEpochMs)
            .put("hand", r.hand?.name?.lowercase() ?: JSONObject.NULL)
            .put("qualityStatus", r.quality.status.name)
            .put("qualityScore", r.quality.score ?: JSONObject.NULL)
            .put("performanceIndex", r.performanceIndex ?: JSONObject.NULL)
            .put("medicationState", stored.medicationState?.id ?: JSONObject.NULL)
            .put("algorithmVersion", r.algorithmVersion)
            .put("scoringVersion", r.scoringVersion)
            .put("metrics", JSONObject(row.columns.toString()).put("_table", row.table))
    }
}
