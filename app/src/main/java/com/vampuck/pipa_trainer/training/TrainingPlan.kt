package com.vampuck.pipa_trainer.training

/**
 * 指力训练的分次计划与时间轴（纯 Kotlin，无 Android 依赖，便于单元测试）。
 *
 * 时间轴：
 *   倒数 5 秒 → 第 1 次训练 → 休息 30 秒 → 第 2 次训练 → 休息 30 秒 → … → 第 N 次训练 → 完成
 *
 * 每一次训练有**独立的速度**（节拍器 BPM）与时长（默认 2:00），次数可随时增删。
 * [stageAt] 把「已经过去的秒数」映射成当前阶段，所以训练进行中追加一次训练、
 * 或取消还没开始的那些次，都不会打乱已经过去的时间。
 *
 * [preroll] 给出「提前起节拍」的窗口：倒数与休息的最后 [DEFAULT_PREROLL_SEC] 秒里，
 * 先用**下一轮**的速度把节拍响起来，让人在阶段真正开始前就对上节奏。
 *
 * 速度用 BPM 表示（轮指每拍 5 响，1 拍 = 1 轮）：
 *   音/秒 = BPM ÷ 12；音/分 = BPM × 5。
 */
class TrainingPlan(
    val rounds: MutableList<Round> = mutableListOf(Round()),
    var restSec: Int = DEFAULT_REST_SEC,
    var leadInSec: Int = DEFAULT_LEAD_IN_SEC,
    /** 倒数/休息的最后几秒提前起节拍（0 = 关闭）。 */
    var prerollSec: Int = DEFAULT_PREROLL_SEC
) {

    /** 一次训练：目标速度（BPM）+ 时长。 */
    data class Round(
        var bpm: Int = DEFAULT_BPM,
        var durationSec: Int = DEFAULT_DURATION_SEC
    ) {
        /** 换算成节拍器用的 Double BPM。 */
        val bpmD: Double get() = bpm.toDouble()

        /** 目标轮指速度（音/秒）。 */
        val cps: Double get() = bpm / CPS_TO_BPM

        /** 目标轮指速度（音/分），与首页「速度（音/分）」同口径。 */
        val strokesPerMin: Double get() = bpm * STROKES_PER_BEAT
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

    /** 全程总时长（含倒数与休息）。空计划为 0。 */
    val totalSec: Int get() = if (rounds.isEmpty()) 0 else leadInSec + workSec + restTotalSec

    /** 第 [index] 次训练相对整个训练开始的起始秒。 */
    fun roundStartSec(index: Int): Double {
        var t = leadInSec.toDouble()
        for (k in 0 until index.coerceIn(0, rounds.size)) {
            t += rounds[k].durationSec + restSec
        }
        return t
    }

    /** 追加一次训练；不传参数时复制最后一次的设置。 */
    fun addRound(bpm: Int? = null, durationSec: Int? = null): Round {
        val last = rounds.lastOrNull()
        val r = Round(
            bpm = (bpm ?: last?.bpm ?: DEFAULT_BPM).coerceIn(MIN_BPM, MAX_BPM),
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

    /**
     * 「提前起节拍」：当前时刻是否已经进入倒数的最后几秒（或某次休息的最后几秒）。
     * 是的话返回**接下来要练的那一次**的下标，否则返回 null。
     *
     * 窗口取「不超过 [prerollSec] 的整数拍」（见 [prerollWindowSec]），于是提前起的
     * 这串节拍长度正好是下一轮的整数拍，起拍点落在拍上、不会出现半拍。注意调用方**不要**
     * 在阶段的边界重启节拍器——提前起的节拍要直接接进正式训练，才有「无缝续上」的听感。
     */
    fun preroll(elapsedSec: Double): Int? {
        if (rounds.isEmpty() || prerollSec <= 0) return null
        val st = stageAt(elapsedSec)
        val next = when (st.phase) {
            Phase.LEAD_IN -> 0
            Phase.REST -> st.roundIndex
            else -> return null
        }
        val r = rounds.getOrNull(next) ?: return null
        if (st.remainingSec > prerollWindowSec(r.bpm)) return null
        return next
    }

    /** 第 [index] 次训练实际提前多少秒起节拍（整拍对齐后 ≤ [prerollSec]）。 */
    fun prerollWindowSec(index: Int): Double {
        if (prerollSec <= 0) return 0.0
        val bpm = rounds.getOrNull(index)?.bpm ?: return 0.0
        val beat = 60.0 / bpm
        val beats = (prerollSec / beat).toInt()
        return if (beats < 1) 0.0 else beats * beat
    }

    /**
     * 把已过去的秒数映射成当前阶段。
     */
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
        const val DEFAULT_BPM = 60                 // = 5 音/秒 = 300 音/分
        const val DEFAULT_DURATION_SEC = 120      // 默认每次 2 分钟
        const val DEFAULT_REST_SEC = 30           // 每次之间自动间隔 30 秒
        const val DEFAULT_LEAD_IN_SEC = 5         // 开始后倒数 5 秒
        /** 倒数/休息的最后 5 秒提前起节拍，先适应节奏再开始。 */
        const val DEFAULT_PREROLL_SEC = 5
        /** 提前起节拍的取值范围（上限不超过倒数时长本身由 UI 约束）。 */
        const val MIN_PREROLL_SEC = 0
        const val MAX_PREROLL_SEC = 10
        const val MIN_BPM = 20
        const val MAX_BPM = 240
        const val MIN_DURATION_SEC = 30
        const val MAX_DURATION_SEC = 600
        const val MIN_REST_SEC = 5
        const val MAX_REST_SEC = 180
        /** 轮指每拍 5 响：音/秒 = BPM ÷ 12，音/分 = BPM × 5。 */
        const val CPS_TO_BPM = 12.0
        const val STROKES_PER_BEAT = 5.0
    }
}
