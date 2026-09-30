package com.vampuck.pipa_trainer.dsp

import kotlin.math.roundToInt

/**
 * 把指标转成直观的中文评估。均匀度不再只看裸 CV，而是用合成音频标定的
 * 换算：jitter% ≈ modalCv × 74，直接读成「时值抖动约百分之几」，更好判断。
 */
object Evaluation {

    data class Dimension(
        val title: String,
        val valueText: String,
        val subText: String,
        val grade: String,
        val level: Int          // 0好 1一般 2较差
    )

    data class Report(
        val score: Int,
        val grade: String,
        val level: Int,
        val headline: String,
        val dimensions: List<Dimension>,
        val summary: String,
        val advice: List<String>
    )

    /** 均匀度评级：基于主带 CV（≈ 抖动百分比）。 */
    fun evennessGrade(cv: Double): Pair<String, Int> = when {
        cv <= 0.10 -> "优" to 0
        cv <= 0.17 -> "良" to 0
        cv <= 0.26 -> "中" to 1
        else -> "待提升" to 2
    }

    private fun evennessScore(jitterPct: Double): Int =
        (110.0 - jitterPct * 3.5).coerceIn(0.0, 100.0).roundToInt()

    fun build(m: LunzhiAnalyzer.Metrics): Report {
        val effCv = m.modalCv.takeIf { it > 0 } ?: m.cv
        val jitter = m.jitterPct
        val (evenGrade, evenLevel) = evennessGrade(effCv)
        val evenScore = evennessScore(jitter)

        // 速度
        val cps = m.strokesPerSec
        val speedText = "${m.strokesPerMin.roundToInt()} 音/分"
        val speedSub = "约 ${"%.1f".format(cps)} 音/秒 · ${"%.1f".format(cps / 5)} 轮/秒"
        val (speedGrade, speedLevel) = when {
            cps < 4 -> "较慢" to 1
            cps <= 9 -> "适中" to 0
            else -> "偏快" to 1
        }

        // 手指力度
        var fingerGrade = "—"; var fingerLevel = 0
        var fingerValue = "—"; var fingerSub = ""
        var weakestPos = -1; var fingerRatio = 1.0
        if (m.fingerProfile.size == 5 && m.strokes >= 10) {
            val strongest = m.fingerProfile.indices.maxByOrNull { m.fingerProfile[it] } ?: 0
            val weakest = m.fingerProfile.indices.minByOrNull { m.fingerProfile[it] } ?: 0
            weakestPos = weakest
            fingerRatio = if (m.fingerProfile[weakest] > 0)
                m.fingerProfile[strongest] / m.fingerProfile[weakest] else 1.0
            val pct = m.fingerProfile.map { (it * 100).roundToInt() }
            fingerValue = "最弱 ${fingerName(weakest)} ${pct[weakest]}%"
            fingerSub = fingerNames().indices.joinToString("  ") { "${fingerName(it)}${pct[it]}%" }
            when {
                fingerRatio < 1.2 -> { fingerGrade = "均衡"; fingerLevel = 0 }
                fingerRatio < 1.5 -> { fingerGrade = "略偏"; fingerLevel = 1 }
                else -> { fingerGrade = "不均"; fingerLevel = 2 }
            }
        }

        val fingerScore = when (fingerLevel) { 0 -> 90; 1 -> 70; else -> 50 }
        val speedScore = when (speedLevel) { 0 -> 90; 1 -> 75; else -> 60 }
        val score = (evenScore * 0.6 + fingerScore * 0.25 + speedScore * 0.15).roundToInt()
        val (grade, level) = when {
            score >= 85 -> "优秀" to 0
            score >= 70 -> "良好" to 0
            score >= 55 -> "中等" to 1
            else -> "待提升" to 2
        }

        val headline = "综合 $score 分 · $grade   |   ${speedText}，时值抖动 ≈ ${jitter.roundToInt()}%"

        val dimensions = listOf(
            Dimension("速度", speedText, speedSub, speedGrade, speedLevel),
            Dimension("均匀度", "≈ ${jitter.roundToInt()}% 抖动",
                "主带 CV ${"%.3f".format(effCv)} · 全段 ${"%.3f".format(m.cv)}", evenGrade, evenLevel),
            Dimension("手指力度", fingerValue, fingerSub, fingerGrade, fingerLevel)
        )

        val sb = StringBuilder()
        sb.append("检测到 ${m.strokes} 击，平均间隔 ${m.meanIoiMs.roundToInt()}ms，")
        sb.append("时值抖动 ±${m.stdIoiMs.roundToInt()}ms（约 ${jitter.roundToInt()}%）。")
        sb.append(evennessComment(effCv, jitter)).append(" ")
        if (weakestPos >= 0) {
            if (fingerRatio < 1.2) {
                sb.append("各指力度差异不大，主要问题是击与击之间的随机忽轻忽重，而非某指固定偏弱。")
            } else {
                sb.append("${fingerName(weakestPos)}力度明显偏轻（最强/最弱约 ${"%.2f".format(fingerRatio)} 倍），多为名指、小指托底不足。")
            }
        } else if (m.strokes < 10) {
            sb.append("击数太少，不足以给出可靠评估。")
        }
        if (m.outlierCount > 0 && m.strokes > 20) {
            val pctOut = 100.0 * m.outlierCount / (m.strokes - 1).coerceAtLeast(1)
            if (pctOut > 15) {
                sb.append(" 另有 ${m.outlierCount} 处间隔异常（占 ${pctOut.roundToInt()}%），可能是漏检或换音停顿，已尽量排除。")
            }
        }

        val advice = ArrayList<String>()
        if (effCv > 0.17) advice.add("放慢到能保持均匀的档位，用节拍器把每一击咬在同一时值上。")
        if (fingerRatio >= 1.5) advice.add("单独强化${fingerName(weakestPos)}的发力，慢速轮指让各指音量趋同。")
        if (cps > 9 && effCv > 0.17) advice.add("当前偏快且不稳，建议降速再逐步提速。")
        if (m.perWindow.size >= 2) {
            val first = m.perWindow.first().cv; val last = m.perWindow.last().cv
            if (last > first * 1.3) advice.add("越到后段越乱，注意耐力与收尾段控制。")
        }
        if (advice.isEmpty()) advice.add("整体不错，可尝试在更高速度下维持同样的均匀度。")

        return Report(score, grade, level, headline, dimensions, sb.toString(), advice)
    }

    private fun fingerNames() = listOf("食指", "中指", "名指", "小指", "挑(大指)")
    private fun fingerName(i: Int) = fingerNames().getOrElse(i) { "第${i + 1}指" }

    private fun evennessComment(cv: Double, jitter: Double): String = when {
        cv <= 0.10 -> "颗粒非常均匀，接近专业水准。"
        cv <= 0.17 -> "均匀度良好（抖动约 ${jitter.roundToInt()}%，人耳大致听不出起伏）。"
        cv <= 0.26 -> "均匀度中等，约 ${jitter.roundToInt()}% 的时值抖动，快轮时可能听出轻微起伏。"
        else -> "均匀度偏差较大（约 ${jitter.roundToInt()}% 抖动），存在抢拍或拉空档。"
    }

    fun levelColor(level: Int): Int = when (level) {
        0 -> 0xFF2E9E5B.toInt()
        1 -> 0xFFE0A32E.toInt()
        else -> 0xFFD64545.toInt()
    }
}
