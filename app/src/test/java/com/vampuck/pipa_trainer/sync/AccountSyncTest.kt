package com.vampuck.pipa_trainer.sync

import com.vampuck.pipa_trainer.practice.ActivityViewData
import com.vampuck.pipa_trainer.practice.PracticeDay
import com.vampuck.pipa_trainer.practice.PracticeLog
import com.vampuck.pipa_trainer.practice.PracticeSource
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SegmentQueueTest {
    private fun segment(id: String) = UploadSegment(id, "2026-11-26", PracticeSource.FREE, 1L)

    @Test fun `segment ids preserve the original key and separate devices`() {
        val key = "123_456:2:2026-11-26"
        assertEquals("d:device-a:$key", SegmentQueue.segmentId("device-a", key))
        assertNotEquals(SegmentQueue.segmentId("a", key), SegmentQueue.segmentId("b", key))
        assertEquals(SegmentQueue.segmentId("a", key), SegmentQueue.segmentId("a", key))
    }

    @Test fun `raw entries keep their exact source date duration and identity`() {
        val entry = PracticeLog.Entry(PracticeSource.BASICS, "2026-11-25", 12_345L)
        assertEquals(listOf(UploadSegment("d:uuid:key", entry.day, entry.source, entry.elapsedMs)),
            SegmentQueue.fromEntries("uuid", listOf("key" to entry)))
    }

    @Test fun `claims from either account exclude a segment and leave the input intact`() {
        val entries = listOf(segment("new"), segment("mine"), segment("theirs"))
        val owners = mapOf("mine" to "alice", "theirs" to "bob")
        assertEquals(listOf(segment("new")), SegmentQueue.unclaimed(entries, "alice", owners::get))
        assertEquals(listOf(segment("new")), SegmentQueue.unclaimed(entries, "bob", owners::get))
        assertEquals(3, entries.size)
    }

    @Test fun `nonpositive entries reach the coordinator to be claimed skipped`() {
        val entries = SegmentQueue.fromEntries("uuid", listOf(
            "zero" to PracticeLog.Entry(PracticeSource.FREE, "2026-11-26", 0),
            "negative" to PracticeLog.Entry(PracticeSource.FREE, "2026-11-26", -1)))
        assertEquals(listOf(0L, -1L), SegmentQueue.unclaimed(entries, "alice") { null }.map { it.elapsedMs })
    }

    @Test fun `batches cover boundaries without losing or repeating entries`() {
        for (size in listOf(0, 1, 199, 200, 201, 400, 501)) {
            val entries = (0 until size).map { segment(it.toString()) }
            val batches = SegmentQueue.batches(entries)
            assertTrue(batches.all { it.isNotEmpty() && it.size <= 200 })
            assertEquals(entries, batches.flatten())
        }
        assertEquals(listOf(200, 200, 101), SegmentQueue.batches((0..500).map { segment("$it") }).map { it.size })
    }

    @Test fun `nonpositive entries are claimed skipped and never become eligible again`() {
        val entries = listOf(segment("zero").copy(elapsedMs = 0), segment("negative").copy(elapsedMs = -1), segment("valid"))
        val plan = SegmentQueue.plan(entries, "2026-11-26")
        assertEquals(listOf("valid"), plan.uploadable.map { it.id })
        assertEquals(listOf("zero", "negative"), plan.skipped.map { it.id })
        val owners = plan.skipped.associate { it.id to "alice" }
        assertEquals(plan.uploadable, SegmentQueue.unclaimed(entries, "alice", owners::get))
        assertFalse(SegmentQueue.needsRefresh(segment("zero"), "alice", "alice", 0, skipped = true))
    }

    @Test fun `backend range and validation do not poison the valid upload batch`() {
        val valid = listOf(segment("oldest").copy(day = PracticeDay.shift("2026-11-26", -400)),
            segment("tomorrow").copy(day = "2026-11-27", elapsedMs = 86_400_000L))
        val invalid = listOf(segment("expired").copy(day = PracticeDay.shift("2026-11-26", -401)),
            segment("future").copy(day = "2026-11-28"), segment("bad-date").copy(day = "2026-02-30"),
            segment("long").copy(elapsedMs = 86_400_001L), segment("x".repeat(161)), segment("非英文编号"))
        val plan = SegmentQueue.plan(valid + invalid, "2026-11-26")
        assertEquals(valid, plan.uploadable)
        assertEquals(invalid, plan.localOnly)
        assertTrue(plan.skipped.isEmpty())
    }

    @Test fun `only growing checkpoints owned by this account can refresh a claim`() {
        val entry = segment("checkpoint").copy(elapsedMs = 30_000)
        assertTrue(SegmentQueue.needsRefresh(entry, "alice", "alice", 25_000, false))
        assertFalse(SegmentQueue.needsRefresh(entry, "bob", "alice", 25_000, false))
        assertFalse(SegmentQueue.needsRefresh(entry, "alice", "alice", 30_000, false))
        assertFalse(SegmentQueue.needsRefresh(entry, "alice", "alice", 31_000, false))
        assertFalse(SegmentQueue.needsRefresh(entry, "alice", null, 0, false))
    }

    @Test fun `a failed later batch leaves that batch unclaimed and preserves earlier acknowledgements`() {
        val entries = (0..400).map { segment(it.toString()) }
        val owners = mutableMapOf<String, String>()
        var requests = 0
        try {
            SegmentQueue.upload(entries, send = {
                requests++
                if (requests == 2) throw IOException("lost response after a possible server write")
                assertTrue(it.none { entry -> entry.id in owners })
            }, accepted = { batch -> batch.forEach { owners[it.id] = "alice" } })
            fail("must stop on failed upload")
        } catch (_: IOException) {
            assertEquals(200, owners.size)
            assertEquals(entries.drop(200), SegmentQueue.unclaimed(entries, "alice", owners::get))
        }
        val remaining = SegmentQueue.unclaimed(entries, "alice", owners::get)
        assertEquals(201, SegmentQueue.upload(remaining, {}, { batch -> batch.forEach { owners[it.id] = "alice" } }))
        assertEquals(401, owners.size)
    }
}

class CloudCacheTest {
    @Test fun `JSON cache round trip preserves all three millisecond sources`() {
        val days = mapOf("2026-11-26" to CloudDay(1, 60_001, 3_600_999), "2026-11-25" to CloudDay(0, 0, 0))
        val json = CloudCache.encode(days)
        assertTrue(json.startsWith("{\"2026-11-25\":{"))
        assertEquals(days, CloudCache.decode(json))
    }

    @Test fun `empty cloud is a valid cache snapshot`() {
        assertEquals("{}", CloudCache.encode(emptyMap()))
        assertEquals(emptyMap<String, CloudDay>(), CloudCache.decode(" { } \n"))
    }

    @Test fun `JSON whitespace and source order do not change totals`() {
        assertEquals(mapOf("2026-11-26" to CloudDay(3, 2, 1)), CloudCache.decode(
            " { \"2026-11-26\" : { \"free\":1, \"metronome\":2, \"basics\":3 } } "))
    }

    @Test fun `corrupt missing negative overflowing and duplicate cache fields are rejected`() {
        val valid = "{\"2026-11-26\":{\"basics\":1,\"metronome\":2,\"free\":3}}"
        for (json in listOf("", "null", valid + "tail", valid.dropLast(1), valid.replace("11-26", "02-30"),
                valid.replace("\"free\":3", "\"free\":-3"), valid.replace("\"free\":3", "\"free\":03"),
                valid.replace("\"free\":3", "\"free\":1.5"), valid.replace("\"free\":3", "\"free\":9223372036854775808"),
                valid.replace("\"free\":3", "\"free\":9223372036854775807"),
                valid.replace("\"free\":3", "\"basics\":3"), valid.replace(",\"free\":3", ""),
                valid.replace("\"free\":3", "\"other\":3"), valid.dropLast(1) + "," + valid.drop(1))) {
            assertNull(json, CloudCache.decode(json))
        }
        assertNull(CloudCache.decode(valid.replace("\"basics\"", "\"basics \"")))
    }
}

class ActivityViewDataTest {
    private val day = "2026-11-26"
    private val now = requireNotNull(PracticeDay.parse(day)).time + 12 * 3_600_000L
    private val local = PracticeLog().apply { put("local", PracticeSource.FREE, day, 19L) }
    private val cloud = CloudSnapshot("alice", now, mapOf(day to CloudDay(10, 20, 30)))

    @Test fun `successful snapshot for the current account supplies all displays`() {
        val data = ActivityViewData.choose(local, "alice", cloud, SyncState.OK)
        assertTrue(data.isCloud)
        assertEquals(60L, data.totalMs(day))
        assertEquals(10L, data.totalMs(day, PracticeSource.BASICS))
        assertEquals(20L, data.totalMs(day, PracticeSource.METRONOME))
        assertEquals(30L, data.totalMs(day, PracticeSource.FREE))
        assertEquals(60L, data.totalMsSince("2026-01-01"))
        assertEquals(1, data.streak(day))
    }

    @Test fun `guest missing and other account caches fall back without changing local records`() {
        for (state in SyncState.values()) {
            for (deltas in listOf(emptyList(), listOf(Delta(day, PracticeSource.FREE, 19L)))) {
                for ((userId, snapshot) in listOf(null to cloud, "bob" to cloud, "alice" to null)) {
                    val data = ActivityViewData.choose(local, userId, snapshot, state, deltas)
                    assertFalse("$userId $state deltas=$deltas", data.isCloud)
                    assertSame(local, data.log)
                }
            }
        }
        assertEquals(19L, local.totalMs(day))
    }

    @Test fun `auth required idle and syncing show local details and totals`() {
        val deltas = PendingDeltas.fromSegments(SegmentQueue.fromEntries("device", local.snapshot()),
            "alice", { null }, { Long.MAX_VALUE }, { false })
        for (state in listOf(SyncState.AUTH_REQUIRED, SyncState.IDLE, SyncState.SYNCING)) {
            val data = ActivityViewData.choose(local, "alice", cloud, state, deltas)
            assertFalse(state.name, data.isCloud)
            assertSame(local, data.log)
            assertEquals(19L, data.totalMs(day, PracticeSource.FREE))
            assertEquals(19L, data.totalMsSince("2026-01-01"))
            assertEquals(1, data.streak(day))
        }
    }

    @Test fun `owned snapshots survive staleness midnight and clock rollback even offline`() {
        val midnight = requireNotNull(PracticeDay.parse(day)).time
        for (state in listOf(SyncState.OK, SyncState.OFFLINE)) {
            for (fetchedAt in listOf(now, now - 15 * 60_000L, now - 30 * 86_400_000L, midnight - 1, now + 1)) {
                val data = ActivityViewData.choose(local, "alice", cloud.copy(fetchedAtMs = fetchedAt), state)
                assertTrue("$state fetchedAt=$fetchedAt", data.isCloud)
                assertEquals(60L, data.totalMs(day))
                assertEquals(30L, data.totalMs(day, PracticeSource.FREE))
                assertEquals(1, data.streak(day))
            }
        }
    }

    @Test fun `cloud plus unclaimed practice preserves both devices totals details and streak`() {
        val practiced = PracticeLog.decode(local.snapshot()).apply {
            put("second", PracticeSource.FREE, day, 11L)
        }
        val snapshot = cloud.copy(days = cloud.days + mapOf(
            "2026-11-25" to CloudDay(15, 15, 15), "2026-11-24" to CloudDay(0, 15, 0)))
        val deltas = PendingDeltas.fromSegments(SegmentQueue.fromEntries("device", practiced.snapshot()),
            "alice", { null }, { Long.MAX_VALUE }, { false })
        assertEquals(listOf(Delta(day, PracticeSource.FREE, 30L)), deltas)
        for (state in listOf(SyncState.OK, SyncState.OFFLINE)) {
            val data = ActivityViewData.choose(practiced, "alice", snapshot, state, deltas)
            assertTrue(state.name, data.isCloud)
            assertEquals(90L, data.totalMs(day))
            assertEquals(10L, data.totalMs(day, PracticeSource.BASICS))
            assertEquals(20L, data.totalMs(day, PracticeSource.METRONOME))
            assertEquals(60L, data.totalMs(day, PracticeSource.FREE))
            assertEquals(45L, data.totalMs("2026-11-25"))
            assertEquals(150L, data.totalMsSince("2026-01-01"))
            assertEquals(3, data.streak(day))
            assertEquals(listOf("pending:$day:free"), data.log.snapshot().map { it.first }.filter { it.startsWith("pending:") })
        }
        assertEquals(30L, practiced.totalMs(day))
        assertEquals(60L, snapshot.days.getValue(day).totalMs)
    }

    @Test fun `partially uploaded checkpoint adds only growth and no double count after acceptance`() {
        val practiced = PracticeLog().apply { put("checkpoint", PracticeSource.FREE, day, 45_000L) }
        val segments = SegmentQueue.fromEntries("device", practiced.snapshot())
        val snapshot = cloud.copy(days = mapOf(day to CloudDay(10_000, 20_000, 30_000)))
        for (state in listOf(SyncState.OK, SyncState.OFFLINE)) {
            val deltas = PendingDeltas.fromSegments(segments, "alice", { "alice" }, { 30_000L }, { false })
            assertEquals(listOf(Delta(day, PracticeSource.FREE, 15_000L)), deltas)
            val data = ActivityViewData.choose(practiced, "alice", snapshot, state, deltas)
            assertTrue(data.isCloud)
            assertEquals(45_000L, data.totalMs(day, PracticeSource.FREE))
            assertEquals(75_000L, data.totalMs(day))
            assertEquals(75_000L, data.totalMsSince("2026-01-01"))
        }
        val acceptedDeltas = PendingDeltas.fromSegments(segments, "alice", { "alice" }, { 45_000L }, { false })
        assertTrue(acceptedDeltas.isEmpty())
        val refreshed = snapshot.copy(days = mapOf(day to CloudDay(10_000, 20_000, 45_000)))
        assertEquals(75_000L, ActivityViewData.choose(practiced, "alice", refreshed, SyncState.OK, acceptedDeltas).totalMs(day))
    }

    @Test fun `skipped nonpositive foreign owned invalid and nongrowing entries add nothing`() {
        val segments = listOf(
            UploadSegment("skipped", day, PracticeSource.FREE, 100L),
            UploadSegment("unclaimed-skipped", day, PracticeSource.FREE, 100L),
            UploadSegment("zero", day, PracticeSource.BASICS, 0L),
            UploadSegment("negative", day, PracticeSource.METRONOME, -1L),
            UploadSegment("foreign", day, PracticeSource.FREE, 100L),
            UploadSegment("invalid", "2026-02-30", PracticeSource.FREE, 100L),
            UploadSegment("malformed", "2026-11-6", PracticeSource.FREE, 100L),
            UploadSegment("equal", day, PracticeSource.FREE, 30L),
            UploadSegment("smaller", day, PracticeSource.FREE, 29L),
            UploadSegment("legacy", day, PracticeSource.FREE, 100L))
        val owners = mapOf("skipped" to "alice", "foreign" to "bob",
            "equal" to "alice", "smaller" to "alice", "legacy" to "alice")
        val uploads = mapOf("skipped" to 0L, "equal" to 30L, "smaller" to 30L)
        val deltas = PendingDeltas.fromSegments(segments, "alice", owners::get,
            { uploads[it] ?: Long.MAX_VALUE }, { it == "skipped" || it == "unclaimed-skipped" })
        assertTrue(deltas.isEmpty())
        for (state in listOf(SyncState.OK, SyncState.OFFLINE)) {
            val data = ActivityViewData.choose(local, "alice", cloud, state, deltas)
            assertTrue(data.isCloud)
            assertEquals(60L, data.totalMs(day))
            assertEquals(60L, data.totalMsSince("2026-01-01"))
        }
    }

    @Test fun `pending deltas sum by day and source including full unclaimed elapsed`() {
        val segments = listOf(
            UploadSegment("new-a", day, PracticeSource.BASICS, 10L),
            UploadSegment("new-b", day, PracticeSource.BASICS, 20L),
            UploadSegment("growing", day, PracticeSource.BASICS, 45L),
            UploadSegment("metronome", day, PracticeSource.METRONOME, 30L),
            UploadSegment("free", day, PracticeSource.FREE, 40L),
            UploadSegment("other-day", "2020-01-01", PracticeSource.BASICS, 50L))
        val deltas = PendingDeltas.fromSegments(segments, "alice",
            { if (it == "growing") "alice" else null }, { 30L }, { false })
        assertEquals(listOf(Delta(day, PracticeSource.BASICS, 45L),
            Delta(day, PracticeSource.METRONOME, 30L), Delta(day, PracticeSource.FREE, 40L),
            Delta("2020-01-01", PracticeSource.BASICS, 50L)), deltas)
    }

    @Test fun `empty owned cloud still shows pending practice as account data`() {
        val deltas = PendingDeltas.fromSegments(SegmentQueue.fromEntries("device", local.snapshot()),
            "alice", { null }, { 0L }, { false })
        val data = ActivityViewData.choose(local, "alice", cloud.copy(days = emptyMap()), SyncState.OK, deltas)
        assertTrue(data.isCloud)
        assertEquals(19L, data.totalMs(day))
        assertEquals(1, data.streak(day))
    }

    @Test fun `cloud streak includes yesterday before todays first practice and stops at a gap`() {
        val days = mapOf("2026-11-25" to CloudDay(1, 0, 0), "2026-11-24" to CloudDay(0, 1, 0),
            "2026-11-22" to CloudDay(0, 0, 100))
        val data = ActivityViewData.choose(local, "alice", cloud.copy(days = days), SyncState.OK)
        assertEquals(2, data.streak(day))
        assertEquals(0L, data.totalMs(day))
        assertEquals(102L, data.totalMsSince("2026-11-01"))
        assertEquals(1L, data.totalMsSince("2026-11-25"))
    }
}

class AccountErrorTest {
    @Test fun `server codes map to Chinese UI categories including safe unknown fallback`() {
        val cases = mapOf("invalid_credentials" to AccountError.CREDENTIALS, "session_invalid" to AccountError.SESSION,
            "username_taken" to AccountError.USERNAME_TAKEN, "invalid_recovery_code" to AccountError.RECOVERY_CODE,
            "rate_limited" to AccountError.RATE_LIMITED, "validation_failed" to AccountError.VALIDATION,
            "invalid_json" to AccountError.VALIDATION, "request_too_large" to AccountError.VALIDATION,
            "unsupported_media_type" to AccountError.VALIDATION, "internal_error" to AccountError.SERVER,
            "server_unavailable" to AccountError.SERVER, "invalid_response" to AccountError.RESPONSE,
            "storage_failed" to AccountError.STORAGE, "network_error" to AccountError.NETWORK,
            "future_code" to AccountError.UNKNOWN)
        cases.forEach { (code, expected) -> assertEquals(code, expected, AccountError.fromCode(code)) }
    }

    @Test fun `typed errors preserve status code and retry seconds`() {
        val error = ApiException("rate_limited", 429, 900L)
        assertEquals("rate_limited", error.code)
        assertEquals(429, error.httpStatus)
        assertEquals(900L, error.retryAfter)
    }
}

class SyncEngineTest {
    private val session = AccountSession("test-token", "alice", "alice", 1_900_000_000L)

    @Test fun `successful sync passes through a merge report and publishes OK`() = runBlocking {
        val engine = SyncEngine({ session }, { fail("must retain token") }, { MergeReport(3, 201) })
        assertEquals(MergeReport(3, 201), engine.sync())
        assertEquals(SyncState.OK, engine.status.value.state)
    }

    @Test fun `guest triggers do not call the network`() = runBlocking {
        val engine = SyncEngine({ null }, {}, { fail("guest network"); MergeReport(0, 0) })
        assertNull(engine.sync())
        assertEquals(SyncState.IDLE, engine.status.value.state)
    }

    @Test fun `HTTP 401 clears the token and requires auth even on the next trigger`() = runBlocking {
        var token: AccountSession? = session
        val engine = SyncEngine({ token }, { token = null }, { throw ApiException("session_invalid", 401) })
        engine.sync()
        assertNull(token)
        assertEquals(SyncState.AUTH_REQUIRED, engine.status.value.state)
        engine.sync()
        assertEquals(SyncState.AUTH_REQUIRED, engine.status.value.state)
    }

    @Test fun `IO errors retain token wait for exponential backoff and reset after success`() = runBlocking {
        var now = 10_000L
        var calls = 0
        var succeed = false
        val engine = SyncEngine({ session }, { fail("must retain token") }, {
            calls++
            if (!succeed) throw IOException()
            MergeReport(0, 0)
        }, monotonicMs = { now })
        engine.sync()
        assertEquals(SyncState.OFFLINE, engine.status.value.state)
        assertEquals(1_000L, engine.retryDelayMs())
        engine.sync()
        assertEquals(1, calls)
        now += 1_000
        engine.sync()
        assertEquals(2_000L, engine.retryDelayMs())
        now += 2_000
        succeed = true
        engine.sync()
        assertEquals(SyncState.OK, engine.status.value.state)
        assertEquals(0L, engine.retryDelayMs())
        succeed = false
        engine.sync()
        assertEquals(1_000L, engine.retryDelayMs())
    }

    @Test fun `backoff caps at five minutes and server rate limits are honored`() = runBlocking {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), (1..4).map(SyncEngine::backoffMs))
        assertEquals(300_000L, SyncEngine.backoffMs(99))
        val engine = SyncEngine({ session }, {}, { throw ApiException("rate_limited", 429, 900) }, monotonicMs = { 0 })
        engine.sync()
        assertEquals(900_000L, engine.retryDelayMs())
    }

    @Test fun `concurrent triggers are single flight`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var calls = 0
        val engine = SyncEngine({ session }, {}, {
            calls++
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            MergeReport(1, 1)
        })
        val first = async(Dispatchers.Default) { engine.sync() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(SyncState.SYNCING, engine.status.value.state)
            assertNull(engine.sync())
        } finally { release.countDown() }
        assertEquals(MergeReport(1, 1), first.await())
        assertEquals(1, calls)
    }

    @Test fun `wrong current password preserves the session but revoked session does not`() = runBlocking {
        var cleared = false
        val engine = SyncEngine({ session }, { cleared = true }, { MergeReport(0, 0) })
        try { engine.accountAction<Unit> { throw ApiException("invalid_credentials", 401) }; fail() }
        catch (_: ApiException) { assertFalse(cleared) }
        try { engine.accountAction<Unit> { throw ApiException("session_invalid", 401) }; fail() }
        catch (_: ApiException) { assertTrue(cleared) }
        assertEquals(SyncState.AUTH_REQUIRED, engine.status.value.state)
    }

    @Test fun `account network failure keeps the token and invalidates cloud freshness`() = runBlocking {
        var cleared = false
        val engine = SyncEngine({ session }, { cleared = true }, { MergeReport(0, 0) })
        engine.sync()
        try { engine.accountAction<Unit> { throw IOException() }; fail() }
        catch (_: IOException) {
            assertFalse(cleared)
            assertEquals(SyncState.OFFLINE, engine.status.value.state)
            assertTrue(engine.retryDelayMs() > 0)
        }
    }

    @Test fun `session debug representation never includes the bearer token`() {
        assertFalse(session.toString().contains(session.token))
    }
}
