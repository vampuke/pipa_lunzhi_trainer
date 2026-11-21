package com.vampuck.pipa_trainer

import com.vampuck.pipa_trainer.practice.*
import com.vampuck.pipa_trainer.training.StrengthPracticeSession
import com.vampuck.pipa_trainer.training.TrainingPlan
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class PracticePresentationTest {
    @Test fun `day details render all three sources and their notes`() {
        val log = PracticeLog()
        log.put("b", PracticeSource.BASICS, "2026-10-27", 120_000)
        log.put("m", PracticeSource.METRONOME, "2026-10-27", 30_000)
        log.put("f", PracticeSource.FREE, "2026-10-27", 119_950)
        val labels = mapOf(
            PracticeSource.BASICS to ("基本功" to "只计正式训练"),
            PracticeSource.METRONOME to ("节拍器" to "实际运行时间"),
            PracticeSource.FREE to ("自由练习" to "不含暂停"))
        assertEquals("当日共练习：4 分钟 29 秒\n\n基本功：2 分钟\n只计正式训练\n\n" +
            "节拍器：30 秒\n实际运行时间\n\n自由练习：1 分钟 59 秒\n不含暂停\n\n仅本机",
            PracticeSummary.day(log, "2026-10-27", "当日共练习", labels, "仅本机"))
        assertEquals(269_950L, log.totalMs("2026-10-27"))
    }

    @Test fun `durations expose seconds without rounding to a completed minute`() {
        val cases = mapOf(0L to "0 分钟", 30_000L to "30 秒", 60_000L to "1 分钟",
            119_950L to "1 分钟 59 秒", 3_600_000L to "1 小时",
            3_661_950L to "1 小时 1 分钟 1 秒", 7_230_000L to "2 小时 30 秒")
        cases.forEach { (ms, expected) -> assertEquals("$ms ms", expected, PracticeFormat.duration(ms)) }
    }

    @Test fun `subminute practice keeps the first band and streak alive`() {
        val log = PracticeLog()
        log.put("a", PracticeSource.FREE, "2026-10-26", 30_000)
        log.put("b", PracticeSource.BASICS, "2026-10-27", 1)
        assertEquals(1, PracticeStats.level(30_000))
        assertEquals(1, PracticeStats.level(599_999))
        assertEquals(2, PracticeStats.level(600_000))
        assertEquals(2, PracticeStats.streak(log, "2026-10-27"))
    }
}

private class Recording {
    val log = PracticeLog()
    val totalsAfterSave = mutableListOf<Long>()
    val recorder = PracticeRecorder { id, source, day, ms ->
        log.put(id, source, day, ms)
        totalsAfterSave.add(log.snapshot().sumOf { it.second.elapsedMs })
    }
    fun total(): Long = log.snapshot().sumOf { it.second.elapsedMs }
}

private val WALL = java.time.Instant.parse("2026-10-27T12:00:00Z").toEpochMilli()

class FreePracticeAccountingTest {
    @Test fun `stalled callback caps every checkpoint before completing`() {
        val r = Recording()
        val s = FreePracticeSession(FreePracticeTimer(FreePracticeMode.COUNTDOWN, 30_000), r.recorder)
        s.start(1_000, WALL)
        s.checkpoint(21_000)
        s.checkpoint(91_000)
        assertEquals(30_000L, r.total())
        assertTrue(r.totalsAfterSave.all { it <= 30_000 })
        assertTrue(s.pollCompletion(91_000))
        assertFalse(s.pollCompletion(100_000))
        s.stop(200_000)
        assertEquals(30_000L, r.total())
    }

    @Test fun `paused countdown resumes with only its active remaining time`() {
        val r = Recording()
        val s = FreePracticeSession(FreePracticeTimer(FreePracticeMode.COUNTDOWN, 30_000), r.recorder)
        s.start(0, WALL)
        s.pause(10_000)
        s.checkpoint(80_000)
        s.resume(100_000, WALL + 100_000)
        s.checkpoint(150_000)
        assertEquals(30_000L, r.total())
        assertTrue(s.pollCompletion(150_000))
        s.stop(200_000)
        assertEquals(30_000L, r.total())
    }

    @Test fun `background pause at overdue deadline completes and cannot resume excess`() {
        val r = Recording()
        val s = FreePracticeSession(FreePracticeTimer(FreePracticeMode.COUNTDOWN, 30_000), r.recorder)
        s.start(0, WALL)
        s.pause(45_000)
        assertEquals(30_000L, r.total())
        assertTrue(s.timer.isCompleted)
        s.resume(100_000, WALL + 100_000)
        s.checkpoint(110_000)
        s.pause(120_000)
        s.stop(200_000)
        assertEquals(30_000L, r.total())
    }

    @Test fun `reset or destroy before polling still stops at countdown deadline`() {
        val r = Recording()
        val s = FreePracticeSession(FreePracticeTimer(FreePracticeMode.COUNTDOWN, 30_000), r.recorder)
        s.start(0, WALL)
        s.stop(90_000)
        s.timer.reset()
        s.stop(100_000)
        assertEquals(30_000L, r.total())
        assertTrue(r.totalsAfterSave.all { it <= 30_000 })
    }

    @Test fun `stopwatch preserves uncapped active time and repeated pause stop`() {
        val r = Recording()
        val s = FreePracticeSession(FreePracticeTimer(FreePracticeMode.STOPWATCH), r.recorder)
        s.start(0, WALL)
        s.pause(45_000)
        s.pause(60_000)
        s.resume(100_000, WALL + 100_000)
        s.checkpoint(160_000)
        s.stop(170_000)
        s.stop(190_000)
        assertEquals(115_000L, r.total())
    }

    @Test fun `late countdown endpoint splits the true active dates at midnight`() = inShanghai {
        val wall = java.time.Instant.parse("2026-10-27T15:59:58Z").toEpochMilli()
        val r = Recording()
        val s = FreePracticeSession(FreePracticeTimer(FreePracticeMode.COUNTDOWN, 4_000), r.recorder)
        s.start(0, wall)
        s.checkpoint(90_000)
        s.pollCompletion(90_000)
        assertEquals(2_000L, r.log.totalMs("2026-10-27"))
        assertEquals(2_000L, r.log.totalMs("2026-10-28"))
    }
}

class StrengthPracticeAccountingTest {
    private fun plan(vararg seconds: Int) = TrainingPlan(
        rounds = seconds.map { TrainingPlan.Round(60, it) }.toMutableList())

    @Test fun `late first render and completed 120 second work record exactly 120000`() {
        val r = Recording()
        val s = StrengthPracticeSession(plan(120), r.recorder)
        s.start(0, WALL)
        s.update(5_050)
        s.checkpoint(124_950)
        s.update(125_000)
        assertEquals(120_000L, r.total())
        assertEquals(120_000L, s.playedMs(0))
        assertEquals(TrainingPlan.Phase.DONE, s.stage.phase)
        s.stop(190_000)
        assertEquals(120_000L, r.total())
    }

    @Test fun `one delayed update spanning entirely missed work and rest counts only work`() {
        val r = Recording()
        val s = StrengthPracticeSession(plan(120, 60, 30), r.recorder)
        s.start(0, WALL)
        s.update(500_000)
        assertEquals(210_000L, r.total())
        assertEquals(120_000L, s.playedMs(0))
        assertEquals(60_000L, s.playedMs(1))
        assertEquals(30_000L, s.playedMs(2))
        assertEquals(275_000L, s.activeMs)
        assertFalse(s.isRunning)
    }

    @Test fun `delayed work to work callback excludes the intervening rest`() {
        val r = Recording()
        val s = StrengthPracticeSession(plan(120, 60), r.recorder)
        s.start(0, WALL)
        s.update(5_050)
        s.checkpoint(100_000)
        s.update(160_000)
        assertEquals(125_000L, r.total())
        assertEquals(5_000L, s.playedMs(1))
        assertEquals(TrainingPlan.Phase.WORK, s.stage.phase)
        assertEquals(1, s.stage.roundIndex)
        s.stop(160_000)
        assertEquals(125_000L, r.total())
    }

    @Test fun `lead in and rest never count even before a boundary render`() {
        val r = Recording()
        val s = StrengthPracticeSession(plan(120, 60), r.recorder)
        s.start(0, WALL)
        s.update(4_999)
        assertEquals(0L, r.total())
        s.pause(140_000)
        assertEquals(120_000L, r.total())
        assertEquals(TrainingPlan.Phase.REST, s.stage.phase)
        s.stop(180_000)
        assertEquals(120_000L, r.total())
    }

    @Test fun `manual pause saves the tail since last tick and resumes without losing work`() {
        val r = Recording()
        val s = StrengthPracticeSession(plan(120), r.recorder)
        s.start(0, WALL)
        s.update(10_000)
        s.pause(10_250)
        assertEquals(10_250L, s.elapsedMs)
        assertEquals(5_250L, r.total())
        s.pause(30_000)
        s.update(100_000)
        s.resume(100_000, WALL + 100_000)
        s.update(214_750)
        assertEquals(120_000L, r.total())
        assertEquals(120_000L, s.playedMs(0))
    }

    @Test fun `background tail crossing multiple phases updates stage before pausing`() {
        val r = Recording()
        val s = StrengthPracticeSession(plan(120, 60), r.recorder)
        s.start(0, WALL)
        s.update(124_900)
        s.pause(156_000)
        assertEquals(121_000L, r.total())
        assertEquals(1, s.stage.roundIndex)
        assertEquals(TrainingPlan.Phase.WORK, s.stage.phase)
        s.resume(200_000, WALL + 200_000)
        s.stop(201_000)
        assertEquals(122_000L, r.total())
    }

    @Test fun `skips consume no planned work including zero and subsecond work`() {
        val r = Recording()
        val s = StrengthPracticeSession(plan(120, 60), r.recorder)
        s.start(0, WALL)
        s.skip(1_000) // lead-in
        assertEquals(0L, r.total())
        assertEquals(TrainingPlan.Phase.WORK, s.stage.phase)
        s.skip(1_050) // only 50ms actually played
        assertEquals(50L, r.total())
        assertEquals(50L, s.playedMs(0))
        s.skip(1_050) // rest
        s.skip(1_050) // zero-time second work
        assertEquals(50L, r.total())
        assertEquals(0L, s.playedMs(1))
        assertTrue(s.hasWork(1))
        assertEquals(1_050L, s.activeMs)
    }

    @Test fun `skip resolves the actual phase at click time after a stalled tick`() {
        val r = Recording()
        val s = StrengthPracticeSession(plan(120, 60), r.recorder)
        s.start(0, WALL)
        s.update(10_000)
        s.skip(140_000) // now REST, skip that, not WORK
        assertEquals(120_000L, r.total())
        assertEquals(TrainingPlan.Phase.WORK, s.stage.phase)
        assertEquals(1, s.stage.roundIndex)
        s.stop(141_000)
        assertEquals(121_000L, r.total())
    }

    @Test fun `early end uses exact tail and repeated stop cannot add time`() {
        val r = Recording()
        val s = StrengthPracticeSession(plan(120), r.recorder)
        s.start(0, WALL)
        s.update(5_000)
        s.stop(5_250)
        s.stop(10_000)
        s.update(20_000)
        assertEquals(250L, r.total())
        assertEquals(250L, s.playedMs(0))
        assertEquals(5_250L, s.activeMs)
    }

    @Test fun `append and drop pending rounds settle current time before editing plan`() {
        val r = Recording()
        val p = plan(120)
        val s = StrengthPracticeSession(p, r.recorder)
        s.start(0, WALL)
        s.append(10_000)
        assertEquals(2, p.size)
        assertEquals(10_000L, s.elapsedMs)
        s.append(20_000)
        assertTrue(s.dropLast(21_000))
        s.stop(999_000)
        assertEquals(240_000L, r.total())
        assertEquals(0L, s.playedMs(2))
    }

    @Test fun `drop resolves current phase so an already started round cannot be deleted`() {
        val r = Recording()
        val s = StrengthPracticeSession(plan(120, 60), r.recorder)
        s.start(0, WALL)
        s.update(10_000)
        assertFalse(s.dropLast(156_000))
        assertEquals(1_000L, s.playedMs(1))
        s.stop(156_000)
        assertEquals(121_000L, r.total())
    }

    @Test fun `dropping the last pending round in rest completes without counting rest`() {
        val r = Recording()
        val s = StrengthPracticeSession(plan(120, 60), r.recorder)
        s.start(0, WALL)
        assertTrue(s.dropLast(140_000))
        assertEquals(120_000L, r.total())
        assertEquals(TrainingPlan.Phase.DONE, s.stage.phase)
        s.stop(999_000)
        assertEquals(120_000L, r.total())
    }

    @Test fun `checkpoints are idempotent and midnight is attributed to true work boundaries`() = inShanghai {
        val wall = java.time.Instant.parse("2026-10-27T15:59:53Z").toEpochMilli()
        val r = Recording()
        val s = StrengthPracticeSession(plan(120), r.recorder)
        s.start(0, wall)
        s.checkpoint(9_000)
        s.checkpoint(9_000)
        assertEquals(4_000L, r.total())
        s.update(190_000)
        assertEquals(2_000L, r.log.totalMs("2026-10-27"))
        assertEquals(118_000L, r.log.totalMs("2026-10-28"))
        assertTrue(r.totalsAfterSave.all { it <= 120_000 })
    }

    @Test fun `resume after midnight uses resume wall time and excludes paused gap`() = inShanghai {
        val wall = java.time.Instant.parse("2026-10-27T15:59:53Z").toEpochMilli()
        val r = Recording()
        val s = StrengthPracticeSession(plan(120), r.recorder)
        s.start(0, wall)
        s.pause(6_000)
        s.resume(60_000, wall + 60_000)
        s.stop(61_000)
        assertEquals(1_000L, r.log.totalMs("2026-10-27"))
        assertEquals(1_000L, r.log.totalMs("2026-10-28"))
    }
}

private fun inShanghai(block: () -> Unit) {
    val previous = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    try { block() } finally { TimeZone.setDefault(previous) }
}
