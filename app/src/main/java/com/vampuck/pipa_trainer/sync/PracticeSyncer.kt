package com.vampuck.pipa_trainer.sync

import android.content.Context
import com.vampuck.pipa_trainer.practice.ActivityViewData
import com.vampuck.pipa_trainer.practice.PracticeDay
import com.vampuck.pipa_trainer.practice.PracticeLog
import com.vampuck.pipa_trainer.practice.PracticeStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

data class AccountView(
    val username: String?, val userId: String?, val expiresAt: Long?,
    val lastSyncAt: Long, val status: SyncStatus, val localOnlySegments: Int
) {
    val loggedIn: Boolean get() = userId != null && expiresAt != null
}

data class HomeSyncData(val activity: ActivityViewData, val account: AccountView)

/** Process-scoped coordinator. Only the application Context is retained. */
class PracticeSyncer private constructor(context: Context) {
    private val app = context.applicationContext
    private val tokens = TokenStore(app)
    private val claims = ClaimStore(app)
    private val store = PracticeStore(app)
    private val api = AuthApi()
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "practice-sync").apply { isDaemon = true }
    }
    private var pending: ScheduledFuture<*>? = null
    private var pendingRetryOnly = false
    private var lastStartMs: Long? = null
    private val mutableRecovery = MutableStateFlow<String?>(null)
    val pendingRecovery: StateFlow<String?> = mutableRecovery.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val accountBusy: StateFlow<Boolean> = mutableBusy.asStateFlow()
    private val engine = SyncEngine(tokens::load, tokens::clear, ::synchronize,
        scheduleRetry = { enqueue(retryOnly = true) })
    val status: StateFlow<SyncStatus> = engine.status

    @Synchronized
    private fun enqueue(retryOnly: Boolean = false) {
        if (pending != null) {
            // An explicit trigger still needs to run if an earlier retry becomes unnecessary.
            pendingRetryOnly = pendingRetryOnly && retryOnly
            return
        }
        pendingRetryOnly = retryOnly
        schedule(queuedDelay())
    }

    private fun queuedDelay(): Long {
        val now = monotonicMs()
        val debounce = lastStartMs?.let { (it + 15_000L - now).coerceAtLeast(0L) } ?: 0L
        return maxOf(debounce, engine.retryDelayMs())
    }

    private fun schedule(delayMs: Long) {
        pending = executor.schedule(::runQueued, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun runQueued() {
        synchronized(this) {
            pending = null
            if (pendingRetryOnly && status.value.state != SyncState.OFFLINE) return
            val delay = queuedDelay()
            if (delay > 0L) {
                schedule(delay)
                return
            }
            lastStartMs = monotonicMs()
        }
        runBlocking { engine.sync() }
    }

    suspend fun syncNow(): MergeReport? = engine.sync()

    suspend fun login(username: String, password: String, deviceName: String) {
        authenticate { api.login(username, password, deviceName, DeviceId.get(app)) }
        enqueue()
    }

    suspend fun register(username: String, password: String, deviceName: String) {
        authenticate { api.register(username, password, deviceName, DeviceId.get(app)) }
        enqueue()
    }

    suspend fun recover(username: String, code: String, password: String, deviceName: String) {
        authenticate { api.recover(username, code, password, deviceName, DeviceId.get(app)) }
        enqueue()
    }

    private suspend fun authenticate(request: () -> AuthResult) = accountAction {
        val result = request()
        // Keep the once-only code in process memory, never in preferences or saved instance state.
        mutableRecovery.value = result.recoveryCode
        saveSession(result.session)
        claims.setAccount(result.session)
    }

    fun acknowledgeRecovery() {
        mutableRecovery.value = null
        enqueue()
    }

    suspend fun changePassword(currentPassword: String, newPassword: String) {
        accountAction {
            val session = requireSession()
            val updated = api.changePassword(session, currentPassword, newPassword)
            saveSession(updated)
            claims.setAccount(updated)
        }
        enqueue()
    }

    suspend fun regenerateRecoveryCode(password: String) = accountAction {
        mutableRecovery.value = api.regenerateRecoveryCode(requireSession().token, password)
    }

    suspend fun logout(localOnly: Boolean = false) = accountAction {
        // A failed remote logout keeps the session until the user confirms local-only logout.
        // A 401 means the server already dropped it, so treat that as success.
        if (!localOnly) tokens.load()?.let {
            try { api.logout(it.token) } catch (error: ApiException) { if (error.httpStatus != 401) throw error }
        }
        tokens.clear()
        claims.clearAccount()
        mutableRecovery.value = null
    }

    private suspend fun <T> accountAction(action: () -> T): T {
        if (!mutableBusy.compareAndSet(false, true)) throw ApiException("account_busy", 0)
        try { return engine.accountAction(action) }
        finally { mutableBusy.value = false }
    }

    private fun requireSession(): AccountSession = tokens.load() ?: throw ApiException("session_invalid", 401)

    private fun saveSession(session: AccountSession) {
        if (!tokens.save(session)) throw ApiException("storage_failed", 0)
    }

    private fun synchronize(session: AccountSession): MergeReport {
        // Manual sync is immediate; subsequent automatic runs still respect the 15s interval.
        synchronized(this) { lastStartMs = monotonicMs() }
        val renewed = api.me(session)
        // Renewal keeps the same token: a failed write can safely retain the old expiry.
        if (renewed.expiresAt != session.expiresAt) tokens.save(renewed)
        claims.setAccount(renewed)
        val deviceId = DeviceId.get(app)
        val entries = SegmentQueue.fromEntries(deviceId, store.rawEntries())
        val unclaimed = claims.unclaimed(entries, session.userId)
        // The server's upload window uses UTC. Out-of-contract records stay local and unclaimed.
        val utcToday = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val plan = SegmentQueue.plan(unclaimed + claims.changedOwnedEntries(entries, session.userId), utcToday)
        plan.skipped.forEach { claims.claimSkipped(it, session.userId) }
        val mergedDays = mutableSetOf<String>()
        val uploaded = SegmentQueue.upload(
            plan.uploadable,
            send = { api.uploadSegments(session.token, deviceId, it) },
            accepted = { batch ->
                batch.filter { claims.claimedBy(it.id) == null }.forEach { mergedDays.add(it.day) }
                // This runs only after the expected HTTP 200 and a complete accepted count.
                claims.accepted(batch, session.userId)
            })
        // Request the whole supported history so a long streak is not cut at 365 days.
        val days = api.fetchSummary(session.token, "1970-01-01", PracticeDay.today())
        claims.saveSnapshot(CloudSnapshot(session.userId, System.currentTimeMillis(), days), plan.localOnly.size)
        return MergeReport(mergedDays.size, uploaded, plan.localOnly.size)
    }

    suspend fun accountView(): AccountView = withContext(Dispatchers.IO) { view(tokens.load()) }

    private fun view(session: AccountSession?): AccountView {
        val current = if (session == null && claims.userId != null) SyncStatus(SyncState.AUTH_REQUIRED, "session_invalid")
            else status.value
        return AccountView(session?.username ?: claims.username, session?.userId, session?.expiresAt,
            claims.lastSyncAt, current, claims.localOnlySegments)
    }

    suspend fun homeData(): HomeSyncData = withContext(Dispatchers.IO) {
        val session = tokens.load()
        val account = view(session)
        val entries = store.rawEntries()
        val local = PracticeLog.decode(entries)
        val now = System.currentTimeMillis()
        val userId = session?.takeIf { it.expiresAt * 1_000L > now }?.userId
        val deltas = if (userId == null) emptyList() else PendingDeltas.fromSegments(
            SegmentQueue.fromEntries(DeviceId.get(app), entries), userId,
            claims::claimedBy, claims::uploadedMs, claims::skipped)
        HomeSyncData(ActivityViewData.choose(local, userId,
            claims.snapshot(), account.status.state, deltas), account)
    }

    suspend fun takeMergeNotice(): Int = withContext(Dispatchers.IO) {
        val session = tokens.load() ?: return@withContext 0
        if (mutableRecovery.value != null) return@withContext 0
        claims.takeMergeNotice(session.userId)
    }

    private fun monotonicMs() = System.nanoTime() / 1_000_000L

    companion object {
        @Volatile private var instance: PracticeSyncer? = null

        fun get(context: Context): PracticeSyncer = instance ?: synchronized(this) {
            instance ?: PracticeSyncer(context.applicationContext).also { instance = it }
        }

        fun requestSync(context: Context) { get(context.applicationContext).enqueue() }
    }
}
