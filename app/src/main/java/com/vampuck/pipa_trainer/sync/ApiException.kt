package com.vampuck.pipa_trainer.sync

/** Server messages are deliberately not presented or logged (the UI uses Chinese resources). */
class ApiException(
    val code: String,
    val httpStatus: Int,
    val retryAfter: Long? = null
) : Exception(code)

enum class AccountError {
    CREDENTIALS, SESSION, USERNAME_TAKEN, RECOVERY_CODE, RATE_LIMITED,
    VALIDATION, SERVER, RESPONSE, STORAGE, NETWORK, UNKNOWN;

    companion object {
        fun fromCode(code: String): AccountError = when (code) {
            "invalid_credentials" -> CREDENTIALS
            "session_invalid" -> SESSION
            "username_taken" -> USERNAME_TAKEN
            "invalid_recovery_code" -> RECOVERY_CODE
            "rate_limited" -> RATE_LIMITED
            "validation_failed", "invalid_json", "request_too_large", "unsupported_media_type" -> VALIDATION
            "internal_error", "server_unavailable" -> SERVER
            "invalid_response" -> RESPONSE
            "storage_failed" -> STORAGE
            "network_error" -> NETWORK
            else -> UNKNOWN
        }
    }
}
