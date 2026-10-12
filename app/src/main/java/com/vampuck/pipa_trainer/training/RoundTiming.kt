package com.vampuck.pipa_trainer.training

/**
 * 一轮训练的结算时长（纯逻辑，便于单测）。
 *
 * 这里的坑是「暂停」：ticker 在暂停时**不让 elapsedMs 累加**，所以一轮按计划跑完时，
 * 这一轮真正占用的弹奏时间就是它自己的时长。旧实现又减了一次「暂停累计」
 * （`durationSec*1000 - 暂停毫秒`），等于把暂停时间扣了两遍 → 分母偏小 →
 * 实测速度虚高：120 秒的轮次中间暂停 20 秒，实弹 5.0 音/秒会显示成 6.0（+20%）。
 *
 * 「跳过本阶段」那条路径传的是 `elapsedMs - roundStartMs`，本来就是真实弹奏时长，
 * 两个分支在这里统一。
 */
object RoundTiming {

    /**
     * @param roundDurationSec 这一轮的计划时长（秒）
     * @param activeOverrideMs 提前结束时真实弹奏的毫秒数（null = 按计划跑满整轮）
     */
    fun playedMs(roundDurationSec: Int, activeOverrideMs: Long?): Long {
        val base = activeOverrideMs ?: roundDurationSec.toLong() * 1000L
        return base.coerceAtLeast(1L)
    }
}
