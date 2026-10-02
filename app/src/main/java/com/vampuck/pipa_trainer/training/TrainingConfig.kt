package com.vampuck.pipa_trainer.training

/**
 * 一套可保存 / 复用的指力训练配置（纯 Kotlin，便于单元测试）。
 *
 * 编码成**一行文本**存进 SharedPreferences，方便持久化也方便测：
 *   `休息秒数|开关(guide/accent/mic 各 0 或 1)|bpm:秒,bpm:秒…|名字`
 * 名字放最后一段（`split('|', limit = 4)`），所以除非名字里带 `|` 否则不用转义；
 * [sanitize] 会把换行和 `|` 换成空格，避免存进去读不回来。
 */
data class TrainingConfig(
    val name: String,
    val rounds: List<TrainingPlan.Round>,
    val restSec: Int,
    val guidePerBeat: Boolean,
    val accentFirst: Boolean,
    val useMic: Boolean
) {

    fun encode(): String {
        val flags = buildString {
            append(if (guidePerBeat) '1' else '0')
            append(if (accentFirst) '1' else '0')
            append(if (useMic) '1' else '0')
        }
        val rs = rounds.joinToString(",") { "${it.bpm}:${it.durationSec}" }
        return "${restSec}|$flags|$rs|${sanitize(name)}"
    }

    /** 名字以外都相同（用来判重：同一套计划不该在列表里出现两次）。 */
    fun samePlanAs(other: TrainingConfig): Boolean =
        restSec == other.restSec && guidePerBeat == other.guidePerBeat &&
            accentFirst == other.accentFirst && useMic == other.useMic && rounds == other.rounds

    fun applyTo(plan: TrainingPlan) {
        plan.rounds.clear()
        rounds.forEach { plan.rounds.add(TrainingPlan.Round(it.bpm, it.durationSec)) }
        plan.restSec = restSec.coerceIn(TrainingPlan.MIN_REST_SEC, TrainingPlan.MAX_REST_SEC)
    }

    companion object {
        const val MAX_NAME_LEN = 24

        /** 去掉换行与分隔符、限长——名字要能安全地存进一行文本。 */
        fun sanitize(raw: String): String =
            raw.replace('\n', ' ').replace('\r', ' ').replace('|', ' ').trim().take(MAX_NAME_LEN)

        /** 按计划生成默认名字，如「3 次 · 60→84 BPM · 25 分」。 */
        fun autoName(rounds: List<TrainingPlan.Round>, restSec: Int, leadInSec: Int): String {
            if (rounds.isEmpty()) return "空计划"
            val n = rounds.size
            val bpmText = if (rounds.all { it.bpm == rounds[0].bpm }) "${rounds[0].bpm} BPM"
            else "${rounds.first().bpm}→${rounds.last().bpm} BPM"
            val totalSec = leadInSec + rounds.sumOf { it.durationSec } +
                restSec * (n - 1).coerceAtLeast(0)
            val minutes = (totalSec + 59) / 60
            return sanitize("$n 次 · $bpmText · $minutes 分")
        }

        fun of(
            plan: TrainingPlan, name: String,
            guidePerBeat: Boolean, accentFirst: Boolean, useMic: Boolean
        ): TrainingConfig = TrainingConfig(
            name = sanitize(name),
            rounds = plan.rounds.map { TrainingPlan.Round(it.bpm, it.durationSec) },
            restSec = plan.restSec,
            guidePerBeat = guidePerBeat,
            accentFirst = accentFirst,
            useMic = useMic
        )

        fun decode(text: String): TrainingConfig? {
            val parts = text.split('|', limit = 4)
            if (parts.size < 4) return null
            val rest = parts[0].toIntOrNull()
                ?.coerceIn(TrainingPlan.MIN_REST_SEC, TrainingPlan.MAX_REST_SEC) ?: return null
            val flags = parts[1]
            if (flags.length < 3) return null
            val rounds = ArrayList<TrainingPlan.Round>()
            for (token in parts[2].split(',')) {
                if (token.isBlank()) continue
                val kv = token.split(':')
                if (kv.size != 2) return null
                val bpm = kv[0].toIntOrNull() ?: return null
                val dur = kv[1].toIntOrNull() ?: return null
                rounds.add(
                    TrainingPlan.Round(
                        bpm.coerceIn(TrainingPlan.MIN_BPM, TrainingPlan.MAX_BPM),
                        dur.coerceIn(TrainingPlan.MIN_DURATION_SEC, TrainingPlan.MAX_DURATION_SEC)
                    )
                )
            }
            if (rounds.isEmpty()) return null
            return TrainingConfig(
                name = sanitize(parts[3]),
                rounds = rounds,
                restSec = rest,
                guidePerBeat = flags[0] == '1',
                accentFirst = flags[1] == '1',
                useMic = flags[2] == '1'
            )
        }
    }
}
