package com.vampuck.pipa_trainer.sync

import android.content.Context
import com.vampuck.pipa_trainer.R
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AccountText {
    fun error(context: Context, error: Throwable): String {
        val apiError = error as? ApiException
        if (apiError?.code == "rate_limited" && apiError.retryAfter != null) {
            return context.getString(R.string.account_error_retry_after, apiError.retryAfter)
        }
        val kind = if (error is IOException) AccountError.NETWORK
            else AccountError.fromCode(apiError?.code.orEmpty())
        val resource = when (kind) {
            AccountError.CREDENTIALS -> R.string.account_error_credentials
            AccountError.SESSION -> R.string.account_error_session
            AccountError.USERNAME_TAKEN -> R.string.account_error_username_taken
            AccountError.RECOVERY_CODE -> R.string.account_error_recovery
            AccountError.RATE_LIMITED -> R.string.account_error_rate_limited
            AccountError.VALIDATION -> R.string.account_error_validation
            AccountError.SERVER -> R.string.account_error_server
            AccountError.RESPONSE -> R.string.account_error_response
            AccountError.STORAGE -> R.string.account_error_storage
            AccountError.NETWORK -> R.string.account_error_network
            AccountError.UNKNOWN -> R.string.account_error_unknown
        }
        return context.getString(resource)
    }

    fun date(context: Context, epochMs: Long): String =
        SimpleDateFormat(context.getString(R.string.account_date_format), Locale.SIMPLIFIED_CHINESE).format(Date(epochMs))

    fun relativeTime(context: Context, epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (epochMs <= 0L) return context.getString(R.string.account_never_synced)
        val elapsed = (nowMs - epochMs).coerceAtLeast(0L)
        return when {
            elapsed < 60_000L -> context.getString(R.string.account_just_now)
            elapsed < 3_600_000L -> context.getString(R.string.account_minutes_ago, elapsed / 60_000L)
            elapsed < 86_400_000L -> context.getString(R.string.account_hours_ago, elapsed / 3_600_000L)
            else -> date(context, epochMs)
        }
    }

    fun status(context: Context, account: AccountView): String {
        if (!account.loggedIn && account.status.state != SyncState.AUTH_REQUIRED) return context.getString(R.string.account_local)
        return when (account.status.state) {
            SyncState.SYNCING -> context.getString(R.string.account_syncing)
            SyncState.OK -> context.getString(R.string.account_status_ok, account.username.orEmpty(), relativeTime(context, account.lastSyncAt))
            SyncState.OFFLINE -> context.getString(R.string.account_status_offline, account.username.orEmpty())
            SyncState.IDLE -> context.getString(R.string.account_status_idle, account.username.orEmpty())
            SyncState.AUTH_REQUIRED -> context.getString(R.string.account_auth_required)
        }
    }
}
