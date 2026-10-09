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

    private fun plan(rounds: List<Pair<Int, Int>>, rest: Int = 30, lead: Int = 5): TrainingPlan {
        val p = TrainingPlan(rounds = mutableListOf(), restSec = rest, leadInSec = lead)
        rounds.forEach { p.rounds.add(TrainingPlan.Round(it.first, it.second)) }
        return p
    }

    @Test
    fun `defaults match the spec`() {
        val p = TrainingPlan()
        assertEquals(1, p.size)
        assertEquals(60, p.rounds[0].bpm)               // 默认 60 BPM = 5 音/秒
        assertEquals(120, p.rounds[0].durationSec)      // 默认每次 2 分钟
        assertEquals(30, p.restSec)                     // 每次间隔 30 秒
        assertEquals(5, p.leadInSec)                    // 开始前倒数 5 秒
    }

    @Test
    fun `bpm converts to strokes per second and per minute`() {
        val r = TrainingPlan.Round(bpm = 60, durationSec = 120)
        assertEquals(5.0, r.cps, 1e-9)                  // 60 BPM = 每拍一轮 = 5 音/秒
        assertEquals(300.0, r.strokesPerMin, 1e-9)
        assertEquals(60.0, r.bpmD, 1e-9)

        val fast = TrainingPlan.Round(bpm = 144, durationSec = 60)
        assertEquals(12.0, fast.cps, 1e-9)              // 144 BPM = 12 音/秒
        assertEquals(720.0, fast.strokesPerMin, 1e-9)

        val slow = TrainingPlan.Round(bpm = 24, durationSec = 60)
        assertEquals(2.0, slow.cps, 1e-9)
        assertEquals(120.0, slow.strokesPerMin, 1e-9)
    }

    @Test
    fun `total time counts lead-in rests and work`() {
        val p = plan(listOf(60 to 120, 72 to 60, 66 to 120))
        assertEquals(300, p.workSec)                   // 120 + 60 + 120
        assertEquals(60, p.restTotalSec)               // 2 个间隔 × 30
        assertEquals(365, p.totalSec)                  // 5 + 300 + 60
    }

    @Test
    fun `stage boundaries are exact`() {
        val p = plan(listOf(60 to 120, 72 to 60))

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
        val p = plan(listOf(60 to 120))
        assertEquals(0, p.restTotalSec)
        assertEquals(Phase.WORK, p.stageAt(124.999).phase)
        assertEquals(Phase.DONE, p.stageAt(125.0).phase)
    }

    @Test
    fun `round start times`() {
        val p = plan(listOf(60 to 120, 72 to 60, 66 to 90))
        assertEquals(5.0, p.roundStartSec(0), 1e-9)
        assertEquals(155.0, p.roundStartSec(1), 1e-9)   // 5 + 120 + 30
        assertEquals(245.0, p.roundStartSec(2), 1e-9)   // + 60 + 30
    }

    @Test
    fun `skip jumps to the end of the current stage`() {
        val p = plan(listOf(60 to 120, 72 to 60))
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
        val p = plan(listOf(60 to 120))
        val before = p.stageAt(100.0)
        p.addRound(bpm = 84, durationSec = 60)
        val after = p.stageAt(100.0)
        assertEquals(Phase.WORK, after.phase)
        assertEquals(before.roundIndex, after.roundIndex)
        assertEquals(before.remainingSec, after.remainingSec, 1e-9)
        // 新的一次排在 120 秒训练 + 30 秒休息之后
        assertEquals(155.0, p.roundStartSec(1), 1e-9)
        assertEquals(84, p.rounds[1].bpm)
        assertEquals(2, p.size)
    }

    @Test
    fun `remove keeps at least one round and respects the running one`() {
        val p = plan(listOf(60 to 120, 72 to 60, 66 to 90))
        assertFalse(p.removeRound(3))                       // 越界
        assertTrue(p.removeRound(2))                        // 取消最后一次
        assertEquals(2, p.size)
        assertTrue(p.removeRound(1))
        assertFalse(p.removeRound(0))                       // 至少保留一次
        assertEquals(1, p.size)
    }

    @Test
    fun `canRemoveDuringRun guards the current round`() {
        val p = plan(listOf(60 to 120, 72 to 60, 66 to 90))
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
        val p = plan(listOf(60 to 120, 72 to 60, 66 to 90))
        val t = 130.0                                    // 休息中（即将第 2 次）
        assertEquals(Phase.REST, p.stageAt(t).phase)
        assertTrue(p.removeRound(1))
        val after = p.stageAt(t)
        assertEquals(Phase.REST, after.phase)            // 还是休息，只是接下来换人
        assertEquals(30.0 - 5.0, after.remainingSec, 1e-9)
        assertEquals(1, after.roundIndex)                // 现在预告的是原来的第 3 次
        assertEquals(66, p.rounds[1].bpm)
    }

    @Test
    fun `round values are clamped`() {
        val p = plan(listOf(60 to 120))
        p.addRound(bpm = 9999, durationSec = 100000)
        assertEquals(TrainingPlan.MAX_BPM, p.rounds[1].bpm)
        assertEquals(TrainingPlan.MAX_DURATION_SEC, p.rounds[1].durationSec)
        p.addRound(bpm = -1, durationSec = 0)
        assertEquals(TrainingPlan.MIN_BPM, p.rounds[2].bpm)
        assertEquals(TrainingPlan.MIN_DURATION_SEC, p.rounds[2].durationSec)
    }

    @Test
    fun `empty plan is done immediately`() {
        val p = plan(listOf())
        assertEquals(Phase.DONE, p.stageAt(0.0).phase)
        assertEquals(0, p.totalSec)
        assertEquals(0.0, p.stageEndSec(0.0), 1e-9)
    }

    // ---------------- 提前起节拍（倒数/休息的最后几秒） ----------------

    /** [TrainingPlan.preroll] 返回的「接下来要练的第几次」，没到窗口时是 -1。 */
    private fun prerollAt(p: TrainingPlan, t: Double): Int = p.preroll(t) ?: -1

    @Test
    fun `preroll is on by default and covers the whole countdown`() {
        assertEquals(TrainingPlan.DEFAULT_PREROLL_SEC, TrainingPlan().prerollSec)
        val p = plan(listOf(60 to 120, 60 to 120), rest = 30, lead = 5)

        assertEquals(0, prerollAt(p, 0.0))            // 倒数一开始就响
        assertEquals(0, prerollAt(p, 4.9))
        assertEquals(60, p.rounds[0].bpm)            // 确认第 1 次的速度就是 60
        assertEquals(-1, prerollAt(p, 5.0))          // 进入训练：交给正常引导音
        assertEquals(-1, prerollAt(p, 60.0))
    }

    @Test
    fun `preroll starts only in the last seconds of a rest`() {
        val p = plan(listOf(60 to 120, 72 to 60), rest = 30, lead = 5)
        // 休息 125.0 ～ 155.0；提前窗口 = 5 拍 × 1.0s = 5.0s
        assertEquals(-1, prerollAt(p, 125.0))
        assertEquals(-1, prerollAt(p, 149.9))
        assertEquals(1, prerollAt(p, 150.0))         // 剩下正好 5 秒
        assertEquals(1, prerollAt(p, 154.9))         // 预告的是即将开始的第 2 次
        assertEquals(-1, prerollAt(p, 155.0))        // 开始训练
    }

    @Test
    fun `preroll window is a whole number of beats of the upcoming round`() {
        // 60 BPM → 1 拍 1 秒 → 窗口正好 5 秒
        assertEquals(5.0, plan(listOf(60 to 120)).prerollWindowSec(0), 1e-9)
        // 72 BPM → 1 拍 0.8333 秒 → 正好 6 拍 = 5.0 秒
        assertEquals(5.0, plan(listOf(72 to 60)).prerollWindowSec(0), 1e-9)
        // 100 BPM → 1 拍 0.6 秒 → 8 拍 = 4.8 秒（第 9 拍会超，所以取 8）
        assertEquals(4.8, plan(listOf(100 to 60)).prerollWindowSec(0), 1e-9)
        // 20 BPM → 1 拍 3 秒 → 只有 1 拍
        assertEquals(3.0, plan(listOf(20 to 60)).prerollWindowSec(0), 1e-9)
        // 240 BPM → 1 拍 0.25 秒 → 20 拍
        assertEquals(5.0, plan(listOf(240 to 60)).prerollWindowSec(0), 1e-9)
    }

    @Test
    fun `preroll window takes a round index not a bpm`() {
        // 第 0 次 60 BPM（窗口 5s）、第 1 次 240 BPM（窗口 5s）、第 2 次 20 BPM（窗口 3s）。
        // 这条锁死「传下标」：若误传这一轮的 BPM 当参数，60 会被当成「第 60 次」取不到 →
        // 窗口 0 → 永远不起拍（v1.20.0 首跑就是这么挂的 5 条测试）。
        val p = plan(listOf(60 to 120, 240 to 60, 20 to 60))
        assertEquals(5.0, p.prerollWindowSec(0), 1e-9)
        assertEquals(5.0, p.prerollWindowSec(1), 1e-9)
        assertEquals(3.0, p.prerollWindowSec(2), 1e-9)
        // 越界下标（如把 BPM 当参数传进来）返回 0，而不是抛异常
        assertEquals(0.0, p.prerollWindowSec(60), 1e-9)
        assertEquals(0.0, p.prerollWindowSec(9), 1e-9)
    }

    @Test
    fun `preroll turns on exactly one window before the next round starts`() {
        // 不写死 150/152，直接由窗口推边界：起拍时刻 = 本轮开始 − 窗口(下一轮)
        val p = plan(listOf(60 to 120, 72 to 60), rest = 30, lead = 5)
        val w = p.prerollWindowSec(1)                    // 72 BPM → 5.0s
        val boundary = p.roundStartSec(1) - w            // 155.0 - 5.0 = 150.0
        assertEquals(150.0, boundary, 1e-9)
        assertEquals(-1, prerollAt(p, boundary - 0.01))
        assertEquals(1, prerollAt(p, boundary))
        assertEquals(1, prerollAt(p, p.roundStartSec(1) - 1e-6))
        assertEquals(-1, prerollAt(p, p.roundStartSec(1)))
    }

    @Test
    fun `preroll honours the per-round speed of the upcoming round`() {
        // 第 2 次很快（240 BPM → 窗口 5.0s），第 3 次很慢（20 BPM → 窗口 3.0s）
        val p = plan(listOf(60 to 120, 240 to 60, 20 to 60), rest = 30, lead = 5)
        // 第 1 次结束 125.0，休息到 155.0：最后 5 秒起 240 BPM 的节拍
        assertEquals(-1, prerollAt(p, 149.9))
        assertEquals(1, prerollAt(p, 150.0))
        // 第 2 次 155.0 ～ 215.0，休息到 245.0：第 3 次很慢（20 BPM），窗口只有 3 秒
        assertEquals(-1, prerollAt(p, 241.9))
        assertEquals(2, prerollAt(p, 242.0))
    }

    @Test
    fun `preroll can be switched off`() {
        val p = plan(listOf(60 to 120, 60 to 120), rest = 30, lead = 5)
        p.prerollSec = 0
        assertEquals(-1, prerollAt(p, 0.0))
        assertEquals(-1, prerollAt(p, 152.0))
        assertEquals(0.0, p.prerollWindowSec(0), 1e-9)
    }

    @Test
    fun `preroll never fires on a single round outside the countdown`() {
        val p = plan(listOf(60 to 120))
        assertEquals(0, prerollAt(p, 0.0))
        assertEquals(-1, prerollAt(p, 5.0))
        assertEquals(-1, prerollAt(p, 124.0))
        assertEquals(-1, prerollAt(p, 125.0))
    }

    @Test
    fun `preroll follows a round added while resting`() {
        val p = plan(listOf(60 to 120, 60 to 120), rest = 30, lead = 5)
        assertEquals(1, prerollAt(p, 152.0))         // 预告的还是第 2 次
        p.addRound(bpm = 120, durationSec = 60)      // 追加在最后，不影响当前预告
        assertEquals(3, p.size)
        assertEquals(1, prerollAt(p, 152.0))
        assertEquals(60, p.rounds[0].bpm)            // 已排好的计划没被打乱
    }
}
