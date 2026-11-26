package com.vampuck.pipa_trainer.sync

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import com.vampuck.pipa_trainer.practice.PracticeDay

data class AuthResult(val session: AccountSession, val recoveryCode: String? = null)

/** Blocking transport. Every caller runs under SyncEngine's IO dispatcher. */
class AuthApi {
    fun register(username: String, password: String, deviceName: String, deviceId: String): AuthResult =
        auth(request("POST", "/auth/register", body = credentials(username, password, deviceName, deviceId), expected = 201), true)

    fun login(username: String, password: String, deviceName: String, deviceId: String): AuthResult =
        auth(request("POST", "/auth/login", body = credentials(username, password, deviceName, deviceId)))

    fun me(session: AccountSession): AccountSession = parse {
        val json = request("GET", "/auth/me", session.token)
        val user = json.getJSONObject("user")
        require(user.getString("id") == session.userId)
        AccountSession(session.token, user.getString("id"), user.getString("username"), json.getLong("expiresAt"))
            .also(::validateSession)
    }

    fun logout(token: String) { request("POST", "/auth/logout", token, expected = 204) }

    fun changePassword(session: AccountSession, currentPassword: String, newPassword: String): AccountSession = parse {
        val json = request("POST", "/auth/password", session.token,
            JSONObject().put("currentPassword", currentPassword).put("newPassword", newPassword))
        session.copy(token = json.getString("token"), expiresAt = json.getLong("expiresAt")).also(::validateSession)
    }

    fun recover(username: String, recoveryCode: String, newPassword: String, deviceName: String, deviceId: String): AuthResult =
        auth(request("POST", "/auth/recover", body = JSONObject().put("username", username)
            .put("recoveryCode", recoveryCode).put("newPassword", newPassword)
            .put("deviceName", deviceName).put("deviceId", deviceId)), true)

    fun regenerateRecoveryCode(token: String, password: String): String = parse {
        request("POST", "/auth/recovery-code", token, JSONObject().put("password", password))
            .getString("recoveryCode").also { require(it.isNotBlank()) }
    }

    fun uploadSegments(token: String, deviceId: String, segments: List<UploadSegment>) {
        require(segments.size <= SegmentQueue.BATCH_SIZE)
        val items = JSONArray()
        for (segment in segments) items.put(JSONObject().put("id", segment.id).put("day", segment.day)
            .put("source", segment.source.key).put("elapsedMs", segment.elapsedMs))
        parse {
            val result = request("POST", "/practice/segments", token,
                JSONObject().put("deviceId", deviceId).put("segments", items))
            require(result.getInt("accepted") == segments.size)
        }
    }

    fun fetchSummary(token: String, from: String, to: String): Map<String, CloudDay> = parse {
        require(PracticeDay.isValid(from) && PracticeDay.isValid(to) && from <= to)
        val json = request("GET", "/practice/summary?from=$from&to=$to", token)
        val items = json.getJSONArray("days")
        val days = linkedMapOf<String, CloudDay>()
        for (index in 0 until items.length()) {
            val row = items.getJSONObject(index)
            val day = row.getString("day")
            require(PracticeDay.isValid(day) && day >= from && day <= to && day !in days)
            val totals = CloudDay(row.getLong("basicsMs"), row.getLong("metronomeMs"), row.getLong("freeMs"))
            require(totals.totalMs == row.getLong("totalMs"))
            days[day] = totals
        }
        days
    }

    private fun auth(json: JSONObject, recoveryRequired: Boolean = false): AuthResult = parse {
        val user = json.getJSONObject("user")
        val session = AccountSession(json.getString("token"), user.getString("id"),
            user.getString("username"), json.getLong("expiresAt"))
        validateSession(session)
        val recoveryCode = if (recoveryRequired) json.getString("recoveryCode").also { require(it.isNotBlank()) } else null
        AuthResult(session, recoveryCode)
    }

    private fun validateSession(session: AccountSession) {
        require(session.token.matches(Regex("[A-Za-z0-9_-]{43}")))
        require(session.userId.isNotBlank() && session.username.isNotBlank() && session.expiresAt > 0L)
    }

    private fun credentials(username: String, password: String, deviceName: String, deviceId: String): JSONObject =
        JSONObject().put("username", username).put("password", password)
            .put("deviceName", deviceName).put("deviceId", deviceId)

    private fun request(
        method: String,
        path: String,
        token: String? = null,
        body: JSONObject? = null,
        expected: Int = 200
    ): JSONObject {
        val connection = URL(BASE_URL + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (method == "POST") {
                val bytes = (body ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status == 204 && status == expected) return JSONObject()
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 4 * 1024 * 1024) throw IOException("Response limit")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }.orEmpty()
            if (status != expected) {
                val error = try { JSONObject(text) } catch (_: JSONException) { null }
                val code = error?.optJSONObject("error")?.optString("code")?.takeIf { it.isNotBlank() }
                    ?: if (status == 401) "session_invalid" else if (status >= 500) "server_unavailable" else "invalid_response"
                val headerRetry = connection.getHeaderField("Retry-After")?.toLongOrNull()
                val bodyRetry = error?.optLong("retry_after", -1L)?.takeIf { it >= 0L }
                throw ApiException(code, status, (headerRetry ?: bodyRetry)?.takeIf { it >= 0 })
            }
            return parse { JSONObject(text) }
        } finally {
            connection.disconnect()
        }
    }

    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (_: JSONException) {
        throw ApiException("invalid_response", 200)
    } catch (_: IllegalArgumentException) {
        throw ApiException("invalid_response", 200)
    }

    companion object {
        const val BASE_URL = "https://pipa-api.alanvampuke.top/api/v1"
    }
}
