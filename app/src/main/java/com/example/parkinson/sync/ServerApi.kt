package com.example.parkinson.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A refusal or failure, with the server's error code ("invite_used", "unauthorized", ...) or "network". */
class ServerException(val code: String, val httpStatus: Int = 0) : Exception(code) {
    companion object {
        const val NETWORK = "network"
        const val UNAUTHORIZED = "unauthorized"
    }
}

data class LinkResult(val token: String, val doctorName: String)

/** The doctor's server (server/ in this repository). */
interface ServerApi {
    suspend fun link(serverUrl: String, inviteCode: String, patient: JSONObject): LinkResult

    /** Sends results and returns the ids the server now has. */
    suspend fun upload(serverUrl: String, token: String, results: List<JSONObject>): List<String>

    suspend fun updateProfile(serverUrl: String, token: String, patient: JSONObject)

    suspend fun revoke(serverUrl: String, token: String)

    /** Lets the patient log in to their own web panel with their national ID and [password]. */
    suspend fun setWebPassword(serverUrl: String, token: String, password: String)
}

class HttpServerApi : ServerApi {

    override suspend fun link(serverUrl: String, inviteCode: String, patient: JSONObject): LinkResult {
        val body = JSONObject().put("inviteCode", inviteCode).put("patient", patient)
        val json = call("POST", "$serverUrl/api/device/link", null, body) ?: throw ServerException("bad_response")
        return try {
            LinkResult(json.getString("patientToken"), json.getString("doctorName"))
        } catch (e: JSONException) {
            throw ServerException("bad_response")
        }
    }

    override suspend fun upload(serverUrl: String, token: String, results: List<JSONObject>): List<String> {
        val json = call("POST", "$serverUrl/api/device/results", token, JSONObject().put("results", JSONArray(results)))
            ?: throw ServerException("bad_response")
        val stored = json.optJSONArray("stored") ?: throw ServerException("bad_response")
        return List(stored.length()) { stored.getString(it) }
    }

    override suspend fun updateProfile(serverUrl: String, token: String, patient: JSONObject) {
        call("PUT", "$serverUrl/api/device/profile", token, patient)
    }

    override suspend fun revoke(serverUrl: String, token: String) {
        call("DELETE", "$serverUrl/api/device/token", token, null)
    }

    override suspend fun setWebPassword(serverUrl: String, token: String, password: String) {
        call("PUT", "$serverUrl/api/device/web-password", token, JSONObject().put("password", password))
    }

    private suspend fun call(method: String, url: String, token: String?, body: JSONObject?): JSONObject? =
        withContext(Dispatchers.IO) {
            val connection = try {
                URL(url).openConnection() as HttpURLConnection
            } catch (e: IOException) {
                throw ServerException(ServerException.NETWORK)
            }
            try {
                connection.requestMethod = method
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.setRequestProperty("Accept", "application/json")
                token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = text.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (status !in 200..299) {
                    val code = json?.optString("error")?.takeIf { it.isNotEmpty() }
                        ?: if (status == 401) ServerException.UNAUTHORIZED else "http_$status"
                    throw ServerException(code, status)
                }
                json
            } catch (e: IOException) {
                throw ServerException(ServerException.NETWORK)
            } finally {
                connection.disconnect()
            }
        }
}

/**
 * What a person may type as the server address, made into https://host[:port][/path] without a trailing slash.
 * With [allowLocalHttp] (debug builds), http:// is also accepted for a private network address such as a test server
 * on the home network (192.168.x.x, 10.x.x.x, 172.16-31.x.x); health data never goes over plain HTTP otherwise.
 */
object ServerAddress {
    fun normalize(input: String, allowLocalHttp: Boolean = false): String? {
        val text = input.trim().trimEnd('/')
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val http = text.startsWith("http://", ignoreCase = true)
        val rest = when {
            http -> text.substring(7)
            text.startsWith("https://", ignoreCase = true) -> text.substring(8)
            else -> text
        }
        val host = rest.substringBefore('/').substringBefore(':')
        if (host.isEmpty() || !host.contains('.') || host.startsWith('.') || host.endsWith('.')) return null
        if (http) return if (allowLocalHttp && isPrivateAddress(host)) "http://$rest" else null
        return "https://$rest"
    }

    private fun isPrivateAddress(host: String): Boolean {
        val parts = host.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1] in 16..31)
    }
}
