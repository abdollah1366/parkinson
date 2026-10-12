package com.example.parkinson.sync

import org.json.JSONObject

/** Records what would be sent; [failWith] makes every call fail with that code. */
class FakeServerApi(private val doctorName: String = "دکتر آزمایشی") : ServerApi {
    val linked = mutableListOf<Pair<String, JSONObject>>()
    val uploads = mutableListOf<List<JSONObject>>()
    val profiles = mutableListOf<JSONObject>()
    val revoked = mutableListOf<String>()
    var failWith: String? = null

    private fun maybeFail() {
        failWith?.let { throw ServerException(it, if (it == ServerException.NETWORK) 0 else 400) }
    }

    override suspend fun link(serverUrl: String, inviteCode: String, patient: JSONObject): LinkResult {
        maybeFail()
        linked += inviteCode to patient
        return LinkResult("token-${linked.size}", doctorName)
    }

    override suspend fun upload(serverUrl: String, token: String, results: List<JSONObject>): List<String> {
        maybeFail()
        uploads += results
        return results.map { it.getString("assessmentId") }
    }

    override suspend fun updateProfile(serverUrl: String, token: String, patient: JSONObject) {
        maybeFail()
        profiles += patient
    }

    override suspend fun revoke(serverUrl: String, token: String) {
        maybeFail()
        revoked += token
    }

    val uploadedIds: List<String> get() = uploads.flatten().map { it.getString("assessmentId") }
}
