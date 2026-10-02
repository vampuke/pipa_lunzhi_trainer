package com.vampuck.pipa_trainer

import com.vampuck.pipa_trainer.training.TrainingPlan
import com.vampuck.pipa_trainer.training.TrainingPlan.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指力训练时间轴的边界测试。时间轴是这一模块的核心（倒数 → 训练 → 休息 → …），
 * 而且训练中允许动态增删次数，最容易出的错就是「阶段切换差一秒」和「删完之后
 * 时间轴错位」，所以这里把边界逐个钉死。
 */
class TrainingPlanTest {

    private fun plan(rounds: List<Pair<Double, Int>>, rest: Int = 30, lead: Int = 5): TrainingPlan {
        val p = TrainingPlan(rounds = mutableListOf(), restSec = rest, leadInSec = lead)
        rounds.forEach { p.rounds.add(TrainingPlan.Round(it.first, it.second)) }
        return p
    }

    @Test
    fun `defaults match the spec`() {
        val p = TrainingPlan()
        assertEquals(1, p.size)
        assertEquals(120, p.rounds[0].durationSec)     // 默认每次 2 分钟
        assertEquals(30, p.restSec)                    // 每次间隔 30 秒
        assertEquals(5, p.leadInSec)                   // 开始前倒数 5 秒
        assertEquals(5.0, p.rounds[0].cps, 1e-9)
    }

    @Test
    fun `speed conversion to bpm and strokes per minute`() {
        val r = TrainingPlan.Round(cps = 5.0, durationSec = 120)
        assertEquals(60.0, r.bpm, 1e-9)                // 5 音/秒 → 60 BPM（每拍一轮）
        assertEquals(300.0, r.strokesPerMin, 1e-9)
        assertEquals(144.0, TrainingPlan.Round(12.0, 60).bpm, 1e-9)
    }

    @Test
    fun `total time counts lead-in rests and work`() {
        val p = plan(listOf(5.0 to 120, 6.0 to 60, 5.5 to 120))
        assertEquals(300, p.workSec)                   // 120 + 60 + 120
        assertEquals(60, p.restTotalSec)               // 2 个间隔 × 30
        assertEquals(365, p.totalSec)                  // 5 + 300 + 60
    }

    @Test
    fun `stage boundaries are exact`() {
        val p = plan(listOf(5.0 to 120, 6.0 to 60))

        // 倒数 5 秒
        assertEquals(Phase.LEAD_IN, p.stageAt(0.0).phase)
        assertEquals(5.0, p.stageAt(0.0).remainingSec, 1e-9)
        assertEquals(Phase.LEAD_IN, p.stageAt(4.999).phase)

        // 第 1 次训练 120 秒（5.0 ～ 125.0）
        val w1 = p.stageAt(5.0)
        assertEquals(Phase.WORK, w1.phase)
        assertEquals(0, w1.roundIndex)
        assertEquals(120.0, w1.remainingSec, 1e-9)
        assertEquals(1.0, p.stageAt(124.0).remainingSec, 1e-9)

        // 休息 30 秒，且指向「即将开始的」第 2 次
        val r1 = p.stageAt(125.0)
        assertEquals(Phase.REST, r1.phase)
        assertEquals(1, r1.roundIndex)
        assertEquals(30.0, r1.remainingSec, 1e-9)
        assertEquals(1.0, p.stageAt(154.0).remainingSec, 1e-9)

        // 第 2 次训练 60 秒
        val w2 = p.stageAt(155.0)
        assertEquals(Phase.WORK, w2.phase)
        assertEquals(1, w2.roundIndex)
        assertEquals(60.0, w2.remainingSec, 1e-9)

        // 最后一次之后没有休息，直接结束
        assertEquals(Phase.DONE, p.stageAt(215.0).phase)
        assertEquals(Phase.DONE, p.stageAt(9999.0).phase)
    }

    @Test
    fun `single round has no rest at all`() {
        val p = plan(listOf(5.0 to 120))
        assertEquals(0, p.restTotalSec)
        assertEquals(Phase.WORK, p.stageAt(124.999).phase)
        assertEquals(Phase.DONE, p.stageAt(125.0).phase)
    }

    @Test
    fun `round start times`() {
        val p = plan(listOf(5.0 to 120, 6.0 to 60, 5.5 to 90))
        assertEquals(5.0, p.roundStartSec(0), 1e-9)
        assertEquals(155.0, p.roundStartSec(1), 1e-9)   // 5 + 120 + 30
        assertEquals(245.0, p.roundStartSec(2), 1e-9)   // + 60 + 30
    }

    @Test
    fun `skip jumps to the end of the current stage`() {
        val p = plan(listOf(5.0 to 120, 6.0 to 60))
        assertEquals(5.0, p.stageEndSec(2.0), 1e-9)          // 倒数 → 训练 1
        assertEquals(125.0, p.stageEndSec(60.0), 1e-9)       // 训练 1 → 休息
        assertEquals(155.0, p.stageEndSec(130.0), 1e-9)      // 休息 → 训练 2
        assertEquals(215.0, p.stageEndSec(160.0), 1e-9)      // 训练 2 → 结束
        // 跳到边界后正好进入下一阶段
        assertEquals(Phase.WORK, p.stageAt(p.stageEndSec(2.0)).phase)
        assertEquals(Phase.REST, p.stageAt(p.stageEndSec(60.0)).phase)
        assertEquals(Phase.WORK, p.stageAt(p.stageEndSec(130.0)).phase)
        assertEquals(Phase.DONE, p.stageAt(p.stageEndSec(160.0)).phase)
    }

    @Test
    fun `adding a round mid-run does not disturb the elapsed timeline`() {
        val p = plan(listOf(5.0 to 120))
        val before = p.stageAt(100.0)
        p.addRound(cps = 7.0, durationSec = 60)
        val after = p.stageAt(100.0)
        assertEquals(Phase.WORK, after.phase)
        assertEquals(before.roundIndex, after.roundIndex)
        assertEquals(before.remainingSec, after.remainingSec, 1e-9)
        // 新的一次排在 120 秒训练 + 30 秒休息之后
        assertEquals(155.0, p.roundStartSec(1), 1e-9)
        assertEquals(7.0, p.rounds[1].cps, 1e-9)
        assertEquals(2, p.size)
    }

    @Test
    fun `remove keeps at least one round and respects the running one`() {
        val p = plan(listOf(5.0 to 120, 6.0 to 60, 5.5 to 90))
        assertFalse(p.removeRound(3))                       // 越界
        assertTrue(p.removeRound(2))                        // 取消最后一次
        assertEquals(2, p.size)
        assertTrue(p.removeRound(1))
        assertFalse(p.removeRound(0))                       // 至少保留一次
        assertEquals(1, p.size)
    }

    @Test
    fun `canRemoveDuringRun guards the current round`() {
        val p = plan(listOf(5.0 to 120, 6.0 to 60, 5.5 to 90))
        val working = p.stageAt(60.0)                    // 正在做第 1 次
        assertEquals(Phase.WORK, working.phase)
        assertEquals(0, working.roundIndex)
        assertFalse(p.canRemoveDuringRun(0, working))    // 不能取消正在做的
        assertTrue(p.canRemoveDuringRun(1, working))
        assertTrue(p.canRemoveDuringRun(2, working))

        val resting = p.stageAt(130.0)                   // 休息，即将开始第 2 次
        assertEquals(Phase.REST, resting.phase)
        assertEquals(1, resting.roundIndex)
        assertTrue(p.canRemoveDuringRun(1, resting))     // 还没开始，可以取消
        assertFalse(p.canRemoveDuringRun(0, resting))    // 已经做完的不能取消
    }

    @Test
    fun `removing a pending round while resting shortens the tail without a jump`() {
        val p = plan(listOf(5.0 to 120, 6.0 to 60, 5.5 to 90))
        val t = 130.0                                    // 休息中（即将第 2 次）
        assertEquals(Phase.REST, p.stageAt(t).phase)
        assertTrue(p.removeRound(1))
        val after = p.stageAt(t)
        assertEquals(Phase.REST, after.phase)            // 还是休息，只是接下来换人
        assertEquals(30.0 - 5.0, after.remainingSec, 1e-9)
        assertEquals(1, after.roundIndex)                // 现在预告的是原来的第 3 次
        assertEquals(5.5, p.rounds[1].cps, 1e-9)
    }

    @Test
    fun `round values are clamped`() {
        val p = plan(listOf(5.0 to 120))
        p.addRound(cps = 999.0, durationSec = 100000)
        assertEquals(TrainingPlan.MAX_CPS, p.rounds[1].cps, 1e-9)
        assertEquals(TrainingPlan.MAX_DURATION_SEC, p.rounds[1].durationSec)
        p.addRound(cps = -1.0, durationSec = 0)
        assertEquals(TrainingPlan.MIN_CPS, p.rounds[2].cps, 1e-9)
        assertEquals(TrainingPlan.MIN_DURATION_SEC, p.rounds[2].durationSec)
    }

    @Test
    fun `empty plan is done immediately`() {
        val p = plan(listOf())
        assertEquals(Phase.DONE, p.stageAt(0.0).phase)
        assertEquals(0, p.totalSec)
    }
}
