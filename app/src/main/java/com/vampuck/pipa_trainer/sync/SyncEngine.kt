package com.vampuck.pipa_trainer.sync

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AccountSession(val token: String, val userId: String, val username: String, val expiresAt: Long) {
    override fun toString(): String = "AccountSession(userId=$userId, username=$username, expiresAt=$expiresAt)"
}
data class MergeReport(val daysMerged: Int, val segmentsUploaded: Int, val localOnlySegments: Int = 0)
enum class SyncState { IDLE, SYNCING, OK, OFFLINE, AUTH_REQUIRED }
data class SyncStatus(val state: SyncState, val errorCode: String? = null, val retryAfter: Long? = null)

/** All sync and credential mutations share this lock, including logout and account switches. */
class SyncEngine(
    private val loadSession: () -> AccountSession?,
    private val clearSession: () -> Unit,
    private val synchronize: (AccountSession) -> MergeReport,
    initialState: SyncState = SyncState.IDLE,
    private val scheduleRetry: () -> Unit = {},
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow(SyncStatus(initialState))
    val status: StateFlow<SyncStatus> = mutableStatus.asStateFlow()
    private var failures = 0
    @Volatile private var retryAt = 0L

    fun retryDelayMs(): Long = (retryAt - monotonicMs()).coerceAtLeast(0L)

    suspend fun sync(): MergeReport? = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext null
        try {
            val session = loadSession()
            if (session == null) {
                val state = if (mutableStatus.value.state == SyncState.AUTH_REQUIRED) SyncState.AUTH_REQUIRED else SyncState.IDLE
                mutableStatus.value = SyncStatus(state)
                return@withContext null
            }
            if (retryDelayMs() > 0) return@withContext null
            mutableStatus.value = SyncStatus(SyncState.SYNCING)
            val report = synchronize(session)
            resetRetry()
            mutableStatus.value = SyncStatus(SyncState.OK)
            report
        } catch (error: ApiException) {
            if (error.httpStatus == 401) invalidateSession() else offline(error.code, error.retryAfter)
            null
        } catch (_: IOException) {
            offline("network_error")
            null
        } catch (error: CancellationException) {
            mutableStatus.value = SyncStatus(SyncState.IDLE)
            throw error
        } catch (_: Exception) {
            offline("invalid_response")
            null
        } finally {
            mutex.unlock()
            if (mutableStatus.value.state == SyncState.OFFLINE) scheduleRetry()
        }
    }

    /** Caller state is saved inside the IO block even if its Activity is recreated. */
    suspend fun <T> accountAction(action: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                action().also {
                    resetRetry()
                    mutableStatus.value = SyncStatus(SyncState.IDLE)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiException) {
                // A wrong current password is also HTTP 401; it does not revoke a valid session.
                if (error.code == "session_invalid") invalidateSession()
                throw error
            } catch (error: IOException) {
                offline("network_error")
                throw error
            }
        }
    }

    private fun invalidateSession() {
        clearSession()
        resetRetry()
        mutableStatus.value = SyncStatus(SyncState.AUTH_REQUIRED, "session_invalid")
    }

    private fun offline(code: String, retryAfter: Long? = null) {
        failures = (failures + 1).coerceAtMost(20)
        val delay = maxOf(backoffMs(failures), (retryAfter ?: 0L).coerceIn(0L, 86_400L) * 1_000L)
        retryAt = monotonicMs() + delay
        mutableStatus.value = SyncStatus(SyncState.OFFLINE, code, retryAfter)
    }

    private fun resetRetry() { failures = 0; retryAt = 0L }

    companion object {
        fun backoffMs(failures: Int): Long =
            (1_000L shl (failures - 1).coerceIn(0, 19)).coerceAtMost(300_000L)
    }
}
