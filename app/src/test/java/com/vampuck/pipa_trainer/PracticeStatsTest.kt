package com.vampuck.pipa_trainer

import com.vampuck.pipa_trainer.practice.PracticeRecorder
import com.vampuck.pipa_trainer.practice.PracticeSource
import com.vampuck.pipa_trainer.practice.PracticeStats
import com.vampuck.pipa_trainer.practice.PracticeLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PracticeLogTest {
    @Test
    fun `a session replaces its previous checkpoint instead of double counting`() {
        val log = PracticeLog()
        log.put("a", PracticeSource.BASICS, "2026-10-27", 10_000)
        log.put("a", PracticeSource.BASICS, "2026-10-27", 25_000)
        assertEquals(25_000, log.totalMs("2026-10-27"))
        assertEquals(25_000, log.totalMs("2026-10-27", PracticeSource.BASICS))
    }

    @Test
    fun `different sources and sessions accumulate independently`() {
        val log = PracticeLog()
        log.put("a", PracticeSource.BASICS, "2026-10-27", 20_000)
        log.put("b", PracticeSource.METRONOME, "2026-10-27", 12_000)
        assertEquals(32_000, log.totalMs("2026-10-27"))
        assertEquals(12_000, log.totalMs("2026-10-27", PracticeSource.METRONOME))
    }

    @Test
    fun `invalid duration and day are rejected without corrupting the log`() {
        val log = PracticeLog()
        assertFalse(log.put("bad", PracticeSource.BASICS, "not-a-day", 1_000))
        assertFalse(log.put("bad", PracticeSource.BASICS, "2026-10-27", -1))
        assertEquals(0, log.totalMs("2026-10-27"))
    }

    @Test
    fun `an old session can contribute its elapsed time across midnight`() {
        val log = PracticeLog()
        log.put("a", PracticeSource.BASICS, "2026-10-26", 50_000)
        log.put("a", PracticeSource.BASICS, "2026-10-27", 70_000)
        assertEquals(70_000, log.totalMs("2026-10-27"))
        assertEquals(0, log.totalMs("2026-10-26"))
    }
}

class PracticeRecorderTest {
    @Test
    fun `pause and stop save only active time and do not double count`() {
        val log = PracticeLog()
        val r = PracticeRecorder { id, source, day, ms -> log.put(id, source, day, ms) }
        r.start(PracticeSource.METRONOME, 100, 1_000_000)
        r.checkpoint(2_100)
        r.pause(5_100)
        r.pause(8_100)
        r.resume(10_100)
        r.stop(13_100)
        r.stop(14_100)
        assertEquals(8_000, log.totalMs("1970-01-01"))
    }

    @Test
    fun `paused midnight stop does not add the paused interval`() {
        val log = PracticeLog()
        val zone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
        try {
            val wall = java.time.ZonedDateTime.parse("2026-10-27T23:59:58+08:00").toInstant().toEpochMilli()
            val r = PracticeRecorder { id, source, day, ms -> log.put(id, source, day, ms) }
            r.start(PracticeSource.BASICS, 0, wall)
            r.pause(1_000)
            r.stop(10_000)
            assertEquals(1_000, log.totalMs("2026-10-27"))
            assertEquals(0, log.totalMs("2026-10-28"))
        } finally { java.util.TimeZone.setDefault(zone) }
    }

    @Test
    fun `checkpoint moves a session to the new local day across midnight`() {
        val log = PracticeLog()
        val wall = java.time.ZonedDateTime.parse("2026-10-27T23:59:58+08:00").toInstant().toEpochMilli()
        val previousZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
        try {
        val r = PracticeRecorder { id, source, day, ms -> log.put(id, source, day, ms) }
        r.start(PracticeSource.BASICS, 0, wall)
        r.checkpoint(1_000)
        assertEquals(1_000, log.totalMs("2026-10-27"))
        r.stop(4_000)
        assertEquals(2_000, log.totalMs("2026-10-28"))
        assertEquals(2_000, log.totalMs("2026-10-27"))
        } finally {
            java.util.TimeZone.setDefault(previousZone)
        }
    }
}
class PracticeStatsTest {
    @Test
    fun `heatmap levels follow the approved minute bands`() {
        assertEquals(0, PracticeStats.level(0))
        assertEquals(1, PracticeStats.level(1))
        assertEquals(1, PracticeStats.level(9 * 60_000L))
        assertEquals(2, PracticeStats.level(10 * 60_000L))
        assertEquals(3, PracticeStats.level(20 * 60_000L))
        assertEquals(4, PracticeStats.level(30 * 60_000L))
    }

    @Test
    fun `streak counts consecutive practiced days through today`() {
        val log = PracticeLog()
        for (day in listOf("2026-10-25", "2026-10-26", "2026-10-27")) {
            log.put(day, PracticeSource.BASICS, day, 60_000)
        }
        log.put("old", PracticeSource.BASICS, "2026-10-20", 60_000)
        assertEquals(3, PracticeStats.streak(log, "2026-10-27"))
    }

    @Test
    fun `a gap breaks the streak and an unpracticed today does not erase yesterday`() {
        val log = PracticeLog()
        log.put("a", PracticeSource.BASICS, "2026-10-25", 60_000)
        log.put("b", PracticeSource.BASICS, "2026-10-27", 60_000)
        assertEquals(1, PracticeStats.streak(log, "2026-10-27"))
        assertEquals(0, PracticeStats.streak(log, "2026-10-28"))
    }
}
