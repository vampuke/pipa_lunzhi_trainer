package com.vampuck.pipa_trainer.dsp

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 把原始指标转成直观的中文评估：总评分(0-100)、等级、分维度评价、
 * 总结段落与练习建议。保留全部原始数值，只是让结论更好读。
 */
object Evaluation {

    /** 单个维度的评价卡片。 */
    data class Dimension(
        val title: String,      // 维度名
        val valueText: String,  // 主数值（大字显示）
        val subText: String,    // 补充说明
        val grade: String,      // 优/良/中/待提升
        val level: Int          // 0好 1一般 2较差
    )

    data class Report(
        val score: Int,                 // 0-100 综合分
        val grade: String,              // 优秀/良好/中等/待提升
        val level: Int,                 // 0/1/2 颜色提示
        val headline: String,           // 一句话总览
        val dimensions: List<Dimension>,
        val summary: String,            // 总结段落
        val advice: List<String>,       // 练习建议
        val windowText: String          // 分段稳定度文本
    )

    fun evennessGrade(cv: Double): Pair<String, Int> = when {
        cv <= 0.12 -> "优" to 0
        cv <= 0.20 -> "良" to 0
        cv <= 0.30 -> "中" to 1
        else -> "待提升" to 2
    }

    /** 均匀度 CV -> 0..100 分（0.05以下满分，0.5以上0分，中间线性）。 */
    private fun evennessScore(cv: Double): Int {
        val s = (1.0 - (cv - 0.05) / (0.45)) * 100
        return s.coerceIn(0.0, 100.0).roundToInt()
    }

    fun build(
        strokesPerMin: Double,
        strokesPerSec: Double,
        cv: Double,
        modalCv: Double,
        fingerProfile: DoubleArray,
        totalStrokes: Int,
        durationSec: Double,
        perWindow: List<LunzhiAnalyzer.WindowStat> = emptyList()
    ): Report {
        val effCv = modalCv.takeIf { it > 0 } ?: cv
        val (evenGrade, evenLevel) = evennessGrade(effCv)
        val evenScore = evennessScore(effCv)

        // 速度维度
        val cyclesPerSec = strokesPerSec / 5
        val speedText = "${strokesPerMin.roundToInt()} 音/分"
        val speedSub = "约 ${"%.1f".format(strokesPerSec)} 音/秒 · ${"%.1f".format(cyclesPerSec)} 轮/秒"
        val (speedGrade, speedLevel) = speedAssessment(strokesPerSec)

        // 手指力度维度
        var fingerGrade = "—"; var fingerLevel = 0
        var fingerValue = "—"; var fingerSub = ""
        var weakestPos = -1; var fingerRatio = 1.0
        if (fingerProfile.size == 5) {
            val strongest = fingerProfile.indices.maxByOrNull { fingerProfile[it] } ?: 0
            val weakest = fingerProfile.indices.minByOrNull { fingerProfile[it] } ?: 0
            weakestPos = weakest
            fingerRatio = if (fingerProfile[weakest] > 0)
                fingerProfile[strongest] / fingerProfile[weakest] else 1.0
            val pct = fingerProfile.map { (it * 100).roundToInt() }
            fingerValue = "最弱 ${fingerName(weakest)} · ${pct[weakest]}%"
            fingerSub = fingerNames().indices.joinToString("  ") { "${fingerName(it)}${pct[it]}%" }
            when {
                fingerRatio < 1.2 -> { fingerGrade = "均衡"; fingerLevel = 0 }
                fingerRatio < 1.5 -> { fingerGrade = "略有偏弱"; fingerLevel = 1 }
                else -> { fingerGrade = "明显不均"; fingerLevel = 2 }
            }
        }

        // 综合分：均匀度权重最高，速度和力度均衡各占一部分
        val fingerScore = when (fingerLevel) { 0 -> 90; 1 -> 70; else -> 50 }
        val speedScoreVal = when (speedLevel) { 0 -> 90; 1 -> 75; else -> 60 }
        val score = (evenScore * 0.6 + fingerScore * 0.25 + speedScoreVal * 0.15).roundToInt()
        val (grade, level) = when {
            score >= 85 -> "优秀" to 0
            score >= 70 -> "良好" to 0
            score >= 55 -> "中等" to 1
            else -> "待提升" to 2
        }

        val headline = "综合 $score 分 · $grade   |   $speedText，均匀度 $evenGrade"

        val dimensions = listOf(
            Dimension("速度", speedText, speedSub, speedGrade, speedLevel),
            Dimension("均匀度", starBar(evenScore), "CV=${"%.3f".format(cv)}" +
                (if (modalCv > 0) " · 主带 ${"%.3f".format(modalCv)}" else ""),
                evenGrade, evenLevel),
            Dimension("手指力度", fingerValue, fingerSub, fingerGrade, fingerLevel)
        )

        // 总结段落
        val sb = StringBuilder()
        sb.append("本段共检测到 $totalStrokes 个音，时长 ${"%.1f".format(durationSec)} 秒。")
        sb.append(speedComment(strokesPerSec)).append(" ")
        sb.append(evennessComment(effCv)).append(" ")
        if (weakestPos >= 0) {
            if (fingerRatio < 1.2) {
                sb.append("各手指之间力度差异不大（最强/最弱约 ${"%.2f".format(fingerRatio)} 倍），" +
                    "说明力度不匀更多是随机的忽轻忽重，而非某根手指固定偏弱。")
            } else {
                sb.append("${fingerName(weakestPos)}的力度明显偏轻（最强/最弱约 ${"%.2f".format(fingerRatio)} 倍），" +
                    "多为名指、小指托底不足所致。")
            }
        }

        // 建议
        val advice = ArrayList<String>()
        if (effCv > 0.20) advice.add("放慢速度用节拍器练，把每一击尽量咬在同一时值上，均匀度比速度更重要。")
        if (fingerRatio >= 1.5) advice.add("单独强化${fingerName(weakestPos)}的发力，做慢速轮指让每指音量趋于一致。")
        if (strokesPerSec > 9 && effCv > 0.20) advice.add("当前速度偏快且不稳，建议降速到能保持均匀的档位再逐步提速。")
        if (perWindow.size >= 2) {
            val first = perWindow.first().cv; val last = perWindow.last().cv
            if (last > first * 1.3) advice.add("越到后段越乱（收尾 CV 明显升高），注意耐力与收尾段的稳定控制。")
        }
        if (advice.isEmpty()) advice.add("整体表现不错，继续保持，可尝试在更高速度下维持同样的均匀度。")

        // 分段文本
        val wt = StringBuilder()
        perWindow.forEach {
            wt.append("${it.startSec.toInt()}–${(it.startSec + 5).toInt()}s：" +
                "${it.strokes}音 · ${"%.1f".format(it.ratePerSec)}音/秒 · CV ${"%.3f".format(it.cv)}\n")
        }

        return Report(score, grade, level, headline, dimensions, sb.toString(),
            advice, wt.toString().trimEnd())
    }

    private fun fingerNames() = listOf("食指", "中指", "名指", "小指", "挑(大指)")
    private fun fingerName(i: Int) = fingerNames().getOrElse(i) { "第${i + 1}指" }

    private fun starBar(score: Int): String {
        val stars = (score / 20.0).roundToInt().coerceIn(0, 5)
        return "★".repeat(stars) + "☆".repeat(5 - stars)
    }

    private fun speedAssessment(cps: Double): Pair<String, Int> = when {
        cps < 4 -> "较慢" to 1
        cps <= 9 -> "适中" to 0
        else -> "偏快" to 1
    }

    private fun speedComment(cps: Double): String = when {
        cps < 4 -> "轮指速度偏慢，适合打基础、抠均匀。"
        cps <= 9 -> "速度处于常见练习区间。"
        else -> "速度偏快。"
    }

    private fun evennessComment(cv: Double): String = when {
        cv <= 0.12 -> "颗粒非常均匀，接近专业水准。"
        cv <= 0.20 -> "均匀度良好，时值再收紧一点会更顺。"
        cv <= 0.30 -> "均匀度中等，音与音之间的间隔有明显波动。"
        else -> "均匀度偏差较大，出现抢拍或拉空档，建议放慢专注等距。"
    }

    fun levelColor(level: Int): Int = when (level) {
        0 -> 0xFF2E9E5B.toInt()   // green
        1 -> 0xFFE0A32E.toInt()   // amber
        else -> 0xFFD64545.toInt() // red
    }
}
