package com.vampuck.pipa_trainer

import com.vampuck.pipa_trainer.training.RoundTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结算用的「实际弹奏时长」。锁定一条回归：暂停不能让分母变小。
 */
class RoundTimingTest {

    @Test
    fun `a full round counts its own duration`() {
        assertEquals(120_000L, RoundTiming.playedMs(120, null))
        assertEquals(30_000L, RoundTiming.playedMs(30, null))
    }

    @Test
    fun `pausing shrinks nothing`() {
        // 旧实现会在这里返回 durationSec*1000 - 暂停毫秒；暂停是挂在 elapsedMs 之外的，
        // 已经不计入这一轮的时间，再减一次就是扣两遍（实测速度虚高）。
        val full = RoundTiming.playedMs(120, null)
        assertEquals(120_000L, full)
        // 用旧的（错误）口径做对照：暂停 20 秒会得到 100 秒 → 速度虚高 20%
        val wrong = 120_000L - 20_000L
        assertTrue(full > wrong)
    }

    @Test
    fun `an early skip reports the time actually played`() {
        assertEquals(45_000L, RoundTiming.playedMs(120, 45_000L))
        assertEquals(1_000L, RoundTiming.playedMs(120, 1_000L))
    }

    @Test
    fun `the denominator can never be zero`() {
        assertTrue(RoundTiming.playedMs(120, 0L) >= 1L)
        assertTrue(RoundTiming.playedMs(0, null) >= 1L)
    }
}
