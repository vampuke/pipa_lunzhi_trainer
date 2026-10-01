package com.vampuck.pipa_trainer.dsp

import kotlin.math.roundToInt

/**
 * 把指标转成直观的中文评估。均匀度不再只看裸 CV，而是用合成音频标定的
 * 换算：jitter% ≈ modalCv × 74，直接读成「时值抖动约百分之几」。
 *
 * 注意：速度**不参与评分**——轮指快慢都是正常练习状态，只作参考显示。
 * 综合分只由「均匀度（权重 72%）」和「轮内各位力度均衡（权重 28%）」决定。
 *
 * 力度剖面按「第1击…第5击」报告，第1击 = 本段检测到的第一击。麦克风无法知道
 * 哪一击是哪一个手指，任何「食指/小指」的绝对命名都是猜测（fiveFold 原先按
 * 最大离散度选相位，实测五个相位的离散度只差不到 3%，等于随机命名）。
 */
object Evaluation {

    data class Dimension(
        val title: String,
        val valueText: String,
        val subText: String,
        val grade: String,
        val level: Int          // 0好 1一般 2较差 3仅参考
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

        // 速度：仅显示，不评分
        val cps = m.strokesPerSec
        val speedText = "${m.strokesPerMin.roundToInt()} 音/分"
        val speedSub = "约 ${"%.1f".format(cps)} 音/秒 · ${"%.1f".format(cps / 5)} 轮/秒"

        // 轮内各位力度（第1击 = 本段第一击）
        var fingerGrade = "—"; var fingerLevel = 3
        var fingerValue = "数据不足"; var fingerSub = ""
        var weakestPos = -1; var fingerRatio = 1.0
        if (m.positionProfile.size == 5 && m.strokes >= 10) {
            val strongest = m.positionProfile.indices.maxByOrNull { m.positionProfile[it] } ?: 0
            val weakest = m.positionProfile.indices.minByOrNull { m.positionProfile[it] } ?: 0
            weakestPos = weakest
            fingerRatio = if (m.positionProfile[weakest] > 0)
                m.positionProfile[strongest] / m.positionProfile[weakest] else 1.0
            val pct = m.positionProfile.map { (it * 100).roundToInt() }
            fingerValue = "最弱 ${positionName(weakest)} ${pct[weakest]}%"
            fingerSub = "以本段第1击为第1位 · " +
                positionNames().indices.joinToString("  ") { "${positionName(it)}${pct[it]}%" }
            when {
                fingerRatio < 1.2 -> { fingerGrade = "均衡"; fingerLevel = 0 }
                fingerRatio < 1.5 -> { fingerGrade = "略偏"; fingerLevel = 1 }
                else -> { fingerGrade = "不均"; fingerLevel = 2 }
            }
        }

        // 综合分：只看均匀度与轮内五位力度均衡（速度不计分）
        val fingerScore = when (fingerLevel) { 0 -> 90; 1 -> 70; 2 -> 50; else -> null }
        val score = if (fingerScore != null)
            (evenScore * 0.72 + fingerScore * 0.28).roundToInt()
        else evenScore
        val (grade, level) = when {
            score >= 85 -> "优秀" to 0
            score >= 70 -> "良好" to 0
            score >= 55 -> "中等" to 1
            else -> "待提升" to 2
        }

        val headline = "综合 $score 分 · $grade   |   时值抖动 ≈ ${jitter.roundToInt()}%，$speedText"

        val dimensions = listOf(
            Dimension("速度（参考）", speedText, speedSub, "参考", 3),
            Dimension("均匀度", "≈ ${jitter.roundToInt()}% 抖动",
                "主带 CV ${"%.3f".format(effCv)} · 全段 ${"%.3f".format(m.cv)}", evenGrade, evenLevel),
            Dimension("轮内各位力度", fingerValue, fingerSub, fingerGrade, fingerLevel)
        )

        val sb = StringBuilder()
        sb.append("检测到 ${m.strokes} 击，平均间隔 ${m.meanIoiMs.roundToInt()}ms，")
        sb.append("时值抖动 ±${m.stdIoiMs.roundToInt()}ms（约 ${jitter.roundToInt()}%）。")
        sb.append(evennessComment(effCv, jitter)).append(" ")
        if (weakestPos >= 0) {
            if (fingerRatio < 1.2) {
                sb.append("轮内五位力度差异不大，主要问题是击与击之间的随机忽轻忽重，而非某一位固定偏弱。")
            } else {
                sb.append("第${weakestPos + 1}击力度明显偏轻（最强/最弱约 ${"%.2f".format(fingerRatio)} 倍）；")
                sb.append("若你按拇指起轮，这一位多半落在名指/小指上。")
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
        if (fingerRatio >= 1.5) advice.add("单独强化第${weakestPos + 1}击的发力，慢速轮指让轮内五位音量趋同。")
        if (m.perWindow.size >= 2) {
            val first = m.perWindow.first().cv; val last = m.perWindow.last().cv
            if (last > first * 1.3) advice.add("越到后段越乱，注意耐力与收尾段控制。")
        }
        if (advice.isEmpty()) advice.add("整体不错，可尝试在保持均匀的前提下自然提速。")

        return Report(score, grade, level, headline, dimensions, sb.toString(), advice)
    }

    private fun positionNames() = listOf("第1击", "第2击", "第3击", "第4击", "第5击")
    private fun positionName(i: Int) = positionNames().getOrElse(i) { "第${i + 1}击" }

    private fun evennessComment(cv: Double, jitter: Double): String = when {
        cv <= 0.10 -> "颗粒非常均匀，接近专业水准。"
        cv <= 0.17 -> "均匀度良好（抖动约 ${jitter.roundToInt()}%，人耳大致听不出起伏）。"
        cv <= 0.26 -> "均匀度中等，约 ${jitter.roundToInt()}% 的时值抖动，快轮时可能听出轻微起伏。"
        else -> "均匀度偏差较大（约 ${jitter.roundToInt()}% 抖动），存在抢拍或拉空档。"
    }

    fun levelColor(level: Int): Int = when (level) {
        0 -> 0xFF2E9E5B.toInt()
        1 -> 0xFFE0A32E.toInt()
        2 -> 0xFFD64545.toInt()
        else -> 0xFF9E9E9E.toInt()   // 仅参考
    }
}
