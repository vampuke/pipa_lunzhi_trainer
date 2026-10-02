package com.vampuck.pipa_trainer.training

/**
 * 指力训练的分次计划与时间轴（纯 Kotlin，无 Android 依赖，便于单元测试）。
 *
 * 时间轴：
 *   倒数 5 秒 → 第 1 次训练 → 休息 30 秒 → 第 2 次训练 → 休息 30 秒 → … → 第 N 次训练 → 完成
 *
 * 每一次训练有**独立的速度**（音/秒）与时长（默认 2:00），次数可随时增删。
 * [stageAt] 把「已经过去的秒数」映射成当前阶段，所以训练进行中追加一次训练、
 * 或取消还没开始的那些次，都不会打乱已经过去的时间。
 *
 * 速度单位是「音/秒」（每秒击弦次数），与首页「速度（音/分）」的换算：
 *   音/分 = 音/秒 × 60；节拍器 BPM = 音/秒 × 12（轮指每拍 5 响，1 拍 = 1 轮）。
 */
class TrainingPlan(
    val rounds: MutableList<Round> = mutableListOf(Round()),
    var restSec: Int = DEFAULT_REST_SEC,
    var leadInSec: Int = DEFAULT_LEAD_IN_SEC
) {

    /** 一次训练：目标速度 + 时长。 */
    data class Round(
        var cps: Double = DEFAULT_CPS,
        var durationSec: Int = DEFAULT_DURATION_SEC
    ) {
        /** 换算成节拍器的 BPM（轮指每拍 5 响）。 */
        val bpm: Double get() = cps * CPS_TO_BPM

        /** 换算成首页用的「音/分」。 */
        val strokesPerMin: Double get() = cps * 60.0
    }

    enum class Phase { LEAD_IN, WORK, REST, DONE }

    /**
     * @param phase     当前阶段
     * @param roundIndex 相关的那一次训练（0 起）。[Phase.REST] 时指向**即将开始**的那一次，
     *                   便于休息界面直接预告下一轮速度。
     * @param remainingSec 当前阶段还剩多少秒
     * @param phaseTotalSec 当前阶段共多少秒
     */
    data class Stage(
        val phase: Phase,
        val roundIndex: Int,
        val remainingSec: Double,
        val phaseTotalSec: Double
    )

    val size: Int get() = rounds.size

    /** 训练总时长（不含休息）。 */
    val workSec: Int get() = rounds.sumOf { it.durationSec }

    /** 休息总时长。 */
    val restTotalSec: Int get() = restSec * (rounds.size - 1).coerceAtLeast(0)

    /** 全程总时长（含倒数与休息）。 */
    val totalSec: Int get() = leadInSec + workSec + restTotalSec

    /** 第 [index] 次训练相对整个训练开始的起始秒。 */
    fun roundStartSec(index: Int): Double {
        var t = leadInSec.toDouble()
        for (k in 0 until index.coerceIn(0, rounds.size)) {
            t += rounds[k].durationSec + restSec
        }
        return t
    }

    /** 追加一次训练；不传参数时复制最后一次的设置。 */
    fun addRound(cps: Double? = null, durationSec: Int? = null): Round {
        val last = rounds.lastOrNull()
        val r = Round(
            cps = (cps ?: last?.cps ?: DEFAULT_CPS).coerceIn(MIN_CPS, MAX_CPS),
            durationSec = (durationSec ?: last?.durationSec ?: DEFAULT_DURATION_SEC)
                .coerceIn(MIN_DURATION_SEC, MAX_DURATION_SEC)
        )
        rounds.add(r)
        return r
    }

    /**
     * 删掉第 [index] 次训练。至少保留一次；[index] 非法时返回 false。
     * 调用方负责保证不会删掉「正在进行或已经过去」的那一次。
     */
    fun removeRound(index: Int): Boolean {
        if (rounds.size <= 1 || index !in rounds.indices) return false
        rounds.removeAt(index)
        return true
    }

    /** 当前阶段结束的时刻（秒）——「跳过」按钮跳到这里。 */
    fun stageEndSec(elapsedSec: Double): Double {
        val st = stageAt(elapsedSec)
        return when (st.phase) {
            Phase.DONE -> totalSec.toDouble()
            else -> elapsedSec + st.remainingSec
        }
    }

    /** 把已过去的秒数映射成当前阶段。 */
    fun stageAt(elapsedSec: Double): Stage {
        if (rounds.isEmpty()) return Stage(Phase.DONE, 0, 0.0, 0.0)
        if (elapsedSec < leadInSec) {
            return Stage(Phase.LEAD_IN, 0, leadInSec - elapsedSec, leadInSec.toDouble())
        }
        var t = elapsedSec - leadInSec
        for (i in rounds.indices) {
            val d = rounds[i].durationSec.toDouble()
            if (t < d) return Stage(Phase.WORK, i, d - t, d)
            t -= d
            if (i < rounds.size - 1) {
                val r = restSec.toDouble()
                if (t < r) return Stage(Phase.REST, i + 1, r - t, r)
                t -= r
            }
        }
        return Stage(Phase.DONE, rounds.size - 1, 0.0, 0.0)
    }

    /**
     * 训练进行中能否取消第 [index] 次：
     *  - 至少保留一次；
     *  - 不能取消正在进行的这一次（WORK 中 [currentRound] 之后才可以）。
     */
    fun canRemoveDuringRun(index: Int, stage: Stage): Boolean {
        if (rounds.size <= 1 || index !in rounds.indices) return false
        return when (stage.phase) {
            Phase.WORK -> index > stage.roundIndex
            else -> index >= stage.roundIndex
        }
    }

    companion object {
        const val DEFAULT_CPS = 5.0
        const val DEFAULT_DURATION_SEC = 120      // 默认每次 2 分钟
        const val DEFAULT_REST_SEC = 30           // 每次之间自动间隔 30 秒
        const val DEFAULT_LEAD_IN_SEC = 5         // 开始后倒数 5 秒
        const val MIN_CPS = 1.0
        const val MAX_CPS = 20.0
        const val MIN_DURATION_SEC = 30
        const val MAX_DURATION_SEC = 600
        const val MIN_REST_SEC = 5
        const val MAX_REST_SEC = 180
        /** 轮指每拍 5 响 → BPM = 音/秒 × 60 / 5。 */
        const val CPS_TO_BPM = 12.0
    }
}
