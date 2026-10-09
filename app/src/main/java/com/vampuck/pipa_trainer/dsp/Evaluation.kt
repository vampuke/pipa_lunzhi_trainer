package com.vampuck.pipa_trainer.dsp

import com.vampuck.pipa_trainer.R

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 把指标转成直观的中文评估。
 *
 * 评语不能只落在几个固定档里，否则每次练习看到的都是同一句话。这里先把指标
 * 归纳成一小组**结构化诊断**（[Diagnosis]），再按诊断组织文字——诊断空间足够
 * 细，同一段话就不会反复出现，而且每条结论都能对回具体指标。
 *
 * 准确性上的几条硬规矩（都是踩过的坑）：
 *  - 均匀度**好**的时候绝不说「忽轻忽重」。旧版在 97 分、抖动 2% 的样本上仍然
 *    输出「主要问题是击与击之间的随机忽轻忽重」，因为那句话只由力度差值触发。
 *  - 均匀度用 [LunzhiAnalyzer.Metrics.cvRoll] 判，不用 modalCv。modalCv 只看主带，
 *    漏掉 10% 击数时会给出 0.006 的「满分」；实测该场景 cvRoll=0.25、主带 0.006，
 *    两者比值 43 倍，正是「局部型不匀」的特征。
 *  - 走势不拿首尾两点比。同为匀速的样本里，窗口 CV 可以是 [0.24,0.30,0.22,0.28,0.01]，
 *    首尾比 0.04 会误判成「越弹越稳」；必须要求方向单调才下结论。
 *  - 手指标注只说「第 n 击」。麦克风不可能知道哪一击是哪个手指。
 *
 * 速度**不参与评分**：轮指快慢都是正常练习状态，只作参考显示。
 * 综合分只由「均匀度（72%）」和「轮内各位力度均衡（28%）」决定。
 */
object Evaluation {

    // ---------------- 结构化诊断 ----------------

    /** 不匀的**性质**：同样大小的 CV，含义完全不同。 */
    enum class EvennessKind {
        UNIFORM,    // 颗粒均匀
        SUSTAINED,  // 贯穿全程的忽快忽慢
        DROPS,      // 主体整齐，但有若干击缺失（约两倍间隔）
        BREAKS,     // 轮指整齐，但有明显停顿 / 抢拍
        MIXED,      // 主带之外的个别间隔明显偏离
        UNKNOWN     // 样本不足
    }

    enum class Trend { FLAT, WORSE, BETTER }
    enum class TempoDrift { FLAT, FASTER, SLOWER }
    enum class TempoBand { VERY_SLOW, SLOW, MEDIUM, FAST, VERY_FAST }
    enum class PositionShape { UNKNOWN, EVEN, SINGLE_WEAK, FIRST_WEAK, DECLINE, UNEVEN }

    data class Diagnosis(
        val evenness: EvennessKind,
        val trend: Trend,
        val tempoDrift: TempoDrift,
        val tempoBand: TempoBand,
        val positionShape: PositionShape,
        val weakestPosition: Int,      // -1 = 无
        val positionSpread: Double,    // 最强 / 最弱
        val weakPct: Int,
        val dropCount: Int,
        val stallCount: Int,
        val rushCount: Int,
        val reliable: Boolean,
        // 前/后段的抖动与速度（窗口足够时才有意义，否则为 0）
        val headJitterPct: Double,
        val tailJitterPct: Double,
        val headBpm: Double,
        val tailBpm: Double
    )

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
        val advice: List<String>,
        val diagnosis: Diagnosis
    )

    // ---------------- 阈值 ----------------
    // 均由 app/src/test 的合成样本实测标定，见 EvaluationTest。

    private const val JITTER_GOOD = 4.0        // % 以下：颗粒均匀
    private const val JITTER_FAIR = 8.0
    private const val JITTER_POOR = 13.0
    private const val DROP_PCT_SIGNIFICANT = 3.0
    private const val CV_RATIO_LOCAL = 2.5     // cvRoll / modalCv：局部型
    private const val POSITION_SPREAD_EVEN = 1.2   // 与「均衡」评级同界，避免卡片与总结口径不一
    private const val TREND_STEP = 1.6         // 后段/前段 抖动倍数
    private const val TREND_RHO = 0.7          // Spearman 秩相关阈值
    private const val DRIFT_STEP = 1.25        // 后段/前段 速度倍数
    private const val MIN_STROKES_RELIABLE = 30

    fun build(m: LunzhiAnalyzer.Metrics): Report {
        val d = diagnose(m)
        val jitter = m.rollJitterPct
        val (evenGrade, evenLevel) = evennessGrade(jitter)
        val evenScore = evennessScore(m)
        val cps = m.strokesPerSec

        val speedText = "${m.strokesPerMin.roundToInt()} 音/分"
        val speedSub = "约 ${"%.1f".format(cps)} 音/秒 · ${"%.1f".format(cps / 5)} 轮/秒 · ${tempoBandName(d.tempoBand)}"

        // ---- 轮内各位力度 ----
        var fingerGrade = "—"; var fingerLevel = 3
        var fingerValue = "数据不足"; var fingerSub = ""
        if (d.positionShape != PositionShape.UNKNOWN) {
            val pct = m.positionProfile.map { (it * 100).roundToInt() }
            fingerValue = "最弱 ${positionName(d.weakestPosition)} ${d.weakPct}%"
            fingerSub = "${shapeName(d.positionShape)} · 以本段第1击为第1位 · " +
                positionNames().indices.joinToString("  ") { "${positionName(it)}${pct[it]}%" }
            val p = d.positionSpread
            when {
                p < 1.2 -> { fingerGrade = "均衡"; fingerLevel = 0 }
                p < 1.5 -> { fingerGrade = "略偏"; fingerLevel = 1 }
                else -> { fingerGrade = "不均"; fingerLevel = 2 }
            }
        }

        val fingerScore = when (fingerLevel) { 0 -> 90; 1 -> 70; 2 -> 50; else -> null }
        val score = if (fingerScore != null)
            (evenScore * 0.72 + fingerScore * 0.28).roundToInt() else evenScore
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
                evennessSub(m), evenGrade, evenLevel),
            Dimension("轮内各位力度", fingerValue, fingerSub, fingerGrade, fingerLevel)
        )

        return Report(score, grade, level, headline, dimensions,
            summary(m, d), advice(m, d), d)
    }

    // ---------------- 诊断 ----------------

    private fun diagnose(m: LunzhiAnalyzer.Metrics): Diagnosis {
        val ioiCount = (m.strokes - 1).coerceAtLeast(0)
        val dropPct = if (ioiCount > 0) 100.0 * m.dropCount / ioiCount else 0.0
        val cvRatio = if (m.modalCv > 0.004) m.cvRoll / m.modalCv else 1.0
        val breaks = m.stallCount + m.rushCount

        val evenness = when {
            m.strokes < 10 || ioiCount < 8 -> EvennessKind.UNKNOWN
            dropPct >= DROP_PCT_SIGNIFICANT -> EvennessKind.DROPS
            m.rollJitterPct <= JITTER_GOOD && breaks >= 2 -> EvennessKind.BREAKS
            cvRatio >= CV_RATIO_LOCAL -> EvennessKind.MIXED
            m.rollJitterPct <= JITTER_GOOD -> EvennessKind.UNIFORM
            else -> EvennessKind.SUSTAINED
        }

        val w = m.perWindow
        val trend = when (direction(w.map { it.cv }, TREND_STEP)) {
            1 -> Trend.WORSE
            -1 -> Trend.BETTER
            else -> Trend.FLAT
        }
        val drift = when (direction(w.map { it.ratePerSec }, DRIFT_STEP)) {
            1 -> TempoDrift.FASTER
            -1 -> TempoDrift.SLOWER
            else -> TempoDrift.FLAT
        }
        // 前后段数值：措辞要能和走势对上（「贯穿全程」和「前段1%后段9%」不能同时说）
        var headJit = 0.0; var tailJit = 0.0; var headBpm = 0.0; var tailBpm = 0.0
        if (w.size >= 4) {
            val third = (w.size / 3).coerceAtLeast(1)
            headJit = w.take(third).map { it.cv }.average() * LunzhiAnalyzer.JITTER_SCALE
            tailJit = w.takeLast(third).map { it.cv }.average() * LunzhiAnalyzer.JITTER_SCALE
            headBpm = w.take(third).map { it.ratePerSec }.average() * 60
            tailBpm = w.takeLast(third).map { it.ratePerSec }.average() * 60
        }

        val band = when {
            m.strokesPerMin < 240 -> TempoBand.VERY_SLOW
            m.strokesPerMin < 360 -> TempoBand.SLOW
            m.strokesPerMin < 540 -> TempoBand.MEDIUM
            m.strokesPerMin < 720 -> TempoBand.FAST
            else -> TempoBand.VERY_FAST
        }

        var shape = PositionShape.UNKNOWN
        var weakest = -1
        var spread = 1.0
        var weakPct = 0
        if (m.positionProfile.size == 5 && m.strokes >= 10) {
            val p = m.positionProfile
            val mx = p.max(); val mn = p.min()
            spread = if (mn > 1e-9) mx / mn else 1.0
            weakest = p.indices.minByOrNull { p[it] } ?: -1
            weakPct = (p.getOrElse(weakest) { 0.0 } * 100).roundToInt()
            if (spread < POSITION_SPREAD_EVEN) {
                shape = PositionShape.EVEN
            } else {
                val sorted = p.sorted()
                // 最弱那一位是否明显低于其余各位
                val clear = sorted.size >= 2 && sorted[1] - sorted[0] > 0.10 * sorted[1]
                var steps = 0
                for (i in 0 until 4) if (p[i] - p[i + 1] > 0.02) steps++
                shape = when {
                    steps >= 3 && p[0] - p[4] > 0.12 -> PositionShape.DECLINE
                    weakest == 0 && clear -> PositionShape.FIRST_WEAK
                    clear -> PositionShape.SINGLE_WEAK
                    else -> PositionShape.UNEVEN
                }
            }
        }

        return Diagnosis(evenness, trend, drift, band, shape, weakest, spread, weakPct,
            m.dropCount, m.stallCount, m.rushCount,
            reliable = m.strokes >= MIN_STROKES_RELIABLE && w.size >= 2,
            headJitterPct = headJit, tailJitterPct = tailJit,
            headBpm = headBpm, tailBpm = tailBpm)
    }

    /**
     * 方向判定：用 Spearman 秩相关，并要求前后段差距够大。
     *
     * 不能只看首尾两点——匀速但有 4 处停顿的样本里窗口 CV 是
     * [0.24,0.30,0.22,0.28,0.01]，首尾比 0.04 会误判成「越弹越稳」。
     * 也不能要求「步进单调」——真实的耐力下滑末尾会连续两个窗口都很大，
     * 出现平台期，步进单调率会掉到 0.5 而被漏掉。秩相关对平台和并列都稳。
     */
    private fun direction(values: List<Double>, step: Double): Int {
        if (values.size < 4) return 0
        val rho = spearman(values)
        if (abs(rho) < TREND_RHO) return 0
        val third = (values.size / 3).coerceAtLeast(1)
        val head = values.take(third).average()
        val tail = values.takeLast(third).average()
        return when {
            rho > 0 && tail > head * step -> 1
            rho < 0 && tail < head / step -> -1
            else -> 0
        }
    }

    /** Spearman 秩相关（并列取平均秩）。 */
    private fun spearman(values: List<Double>): Double {
        val n = values.size
        if (n < 4) return 0.0
        val order = values.indices.sortedBy { values[it] }
        val rank = DoubleArray(n)
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && abs(values[order[j + 1]] - values[order[i]]) < 1e-12) j++
            val avg = (i + j) / 2.0 + 1.0
            for (k in i..j) rank[order[k]] = avg
            i = j + 1
        }
        var d2 = 0.0
        for (k in 0 until n) {
            val d = rank[k] - (k + 1)
            d2 += d * d
        }
        return 1.0 - 6.0 * d2 / (n * (n * n - 1).toDouble())
    }

    /** 评分只用 cvRoll 派生的抖动：既抓住漏击，也不会把乐句间的停顿算成不匀。
     * 停顿(stall)按断点扣分；抢拍(rush) 不再单独扣分——真实录像里它主要是 onset 双触发
     * 的假象，已在 [LunzhiAnalyzer] 的起音合并里处理，残余量再扣分等于对同一现象双重惩罚。 */
    private fun evennessScore(m: LunzhiAnalyzer.Metrics): Int {
        val base = 110.0 - m.rollJitterPct * 3.5
        val breakPenalty = min(12.0, m.stallCount * 2.0)
        return (base - breakPenalty).coerceIn(0.0, 100.0).roundToInt()
    }

    /** 均匀度评级：基于 cvRoll 派生的抖动百分比。 */
    fun evennessGrade(jitterPct: Double): Pair<String, Int> = when {
        jitterPct <= JITTER_GOOD -> "优" to 0
        jitterPct <= JITTER_FAIR -> "良" to 0
        jitterPct <= JITTER_POOR -> "中" to 1
        else -> "待提升" to 2
    }

    // ---------------- 文字 ----------------

    private fun evennessSub(m: LunzhiAnalyzer.Metrics): String {
        // cvRoll 是评分依据（含漏击）；主带 CV 是排除漏击后的手感，两者都列出来
        val sb = StringBuilder("±${m.stdIoiMs.roundToInt()}ms · CV ${"%.3f".format(m.cvRoll)}")
        if (abs(m.cvRoll - m.modalCv) > 0.01) sb.append("（主带 ${"%.3f".format(m.modalCv)}）")
        if (m.dropCount > 0) sb.append(" · 漏击 ${m.dropCount}")
        if (m.stallCount > 0) sb.append(" · 停顿 ${m.stallCount}")
        if (m.rushCount > 0) sb.append(" · 抢拍 ${m.rushCount}")
        return sb.toString()
    }

    private fun summary(m: LunzhiAnalyzer.Metrics, d: Diagnosis): String {
        val sb = StringBuilder()
        sb.append("检测到 ${m.strokes} 击，平均间隔 ${m.meanIoiMs.roundToInt()}ms（中位 ${m.medianIoiMs.roundToInt()}ms）。")

        val j = m.rollJitterPct
        when (d.evenness) {
            EvennessKind.UNKNOWN ->
                sb.append("有效间隔只有 ${(m.strokes - 1).coerceAtLeast(0)} 个，样本太少，结论仅供参考。")
            EvennessKind.UNIFORM ->
                sb.append("时值抖动约 ${j.roundToInt()}%，${uniformPraise(j)}")
            EvennessKind.SUSTAINED -> when (d.trend) {
                // 前后段差别大时不能说「贯穿全程」，否则和后面的走势数字自相矛盾
                Trend.WORSE ->
                    sb.append("时值抖动约 ${j.roundToInt()}%，但它是**越到后段越散**：" +
                        "前段约 ${d.headJitterPct.roundToInt()}%，后段升到 ${d.tailJitterPct.roundToInt()}%。" +
                        "说明轮子本身能稳住，问题出在耐力和注意力，不是每一击都不稳。")
                Trend.BETTER ->
                    sb.append("时值抖动约 ${j.roundToInt()}%，主要是开头还没热开：" +
                        "前段约 ${d.headJitterPct.roundToInt()}%，后段收到 ${d.tailJitterPct.roundToInt()}%。")
                Trend.FLAT ->
                    sb.append("时值抖动约 ${j.roundToInt()}%，而且是**贯穿全程**的忽快忽慢，" +
                        "不是个别失误；说明每一击的落点都还没稳住，而非某几处崩掉。")
            }
            EvennessKind.DROPS -> {
                val pct = if (m.strokes > 1) 100.0 * d.dropCount / (m.strokes - 1) else 0.0
                sb.append("有 ${d.dropCount} 处间隔接近两倍（占 ${pct.roundToInt()}%），相当于漏掉了约 " +
                    "${d.dropCount} 击。")
                sb.append("这些断档把整体抖动推到 ${j.roundToInt()}%；只看没有断档的那些间隔，" +
                    "抖动只有约 ${m.jitterPct.roundToInt()}%。")
                sb.append("所以主要问题不是手抖，而是**断**。")
            }
            EvennessKind.BREAKS ->
                sb.append("轮指本身很整齐（抖动约 ${j.roundToInt()}%），但有 ${d.stallCount + d.rushCount} 处明显偏离：" +
                    "${breakText(d)}。这多半是换弦/换音停顿、抢拍，或检测漏了一击，不是整体不匀。")
            EvennessKind.MIXED ->
                sb.append("整体抖动约 ${j.roundToInt()}%，另有个别间隔明显偏离主带（异常 ${m.outlierCount} 处），" +
                    "属于「大体均匀但有零星塌陷」。")
        }

        // 走势：只补速度漂移；状态走势已经并进上面的均匀度描述里了
        when (d.tempoDrift) {
            TempoDrift.FASTER -> sb.append(" 速度从前段 ${d.headBpm.roundToInt()} 音/分漂到后段 " +
                "${d.tailBpm.roundToInt()} 音/分，自己越弹越快。")
            TempoDrift.SLOWER -> sb.append(" 速度从前段 ${d.headBpm.roundToInt()} 音/分漂到后段 " +
                "${d.tailBpm.roundToInt()} 音/分，越弹越拖。")
            TempoDrift.FLAT -> {}
        }

        // 力度形状
        when (d.positionShape) {
            PositionShape.EVEN ->
                sb.append(" 轮内五位力度接近（最强/最弱约 ${"%.2f".format(d.positionSpread)} 倍）。")
            PositionShape.SINGLE_WEAK ->
                sb.append(" 第${d.weakestPosition + 1}击明显偏轻（${d.weakPct}%，最强/最弱约 " +
                    "${"%.2f".format(d.positionSpread)} 倍），是固定某一位弱，不是随机忽轻忽重。")
            PositionShape.FIRST_WEAK ->
                sb.append(" 每一轮的**第1击**偏轻（${d.weakPct}%）：若你按拇指起轮，就是起轮那一下没咬住。")
            PositionShape.DECLINE ->
                sb.append(" 轮内力度从第1击到第5击逐位递减（最强/最弱约 ${"%.2f".format(d.positionSpread)} 倍），" +
                    "越往后越虚。")
            PositionShape.UNEVEN ->
                sb.append(" 轮内五位力度较分散（最强/最弱约 ${"%.2f".format(d.positionSpread)} 倍），" +
                    "但没有单一突出的弱位。")
            PositionShape.UNKNOWN -> {}
        }

        if (!d.reliable) sb.append(" 样本偏少（${m.strokes} 击 / ${m.perWindow.size} 个分段），结论仅供参考。")
        return sb.toString()
    }

    private fun uniformPraise(jitter: Double): String = when {
        jitter <= 2.0 -> "颗粒非常均匀，接近专业水准。"
        jitter <= JITTER_GOOD -> "颗粒均匀，人耳基本听不出起伏。"
        else -> "整体整齐，只有在快轮时可能听出极轻微起伏。"
    }

    private fun breakText(d: Diagnosis): String {
        val parts = ArrayList<String>()
        if (d.stallCount > 0) parts.add("停顿 ${d.stallCount} 处")
        if (d.rushCount > 0) parts.add("抢拍 ${d.rushCount} 处")
        if (d.dropCount > 0) parts.add("疑似漏击 ${d.dropCount} 处")
        return parts.joinToString("、")
    }

    private fun advice(m: LunzhiAnalyzer.Metrics, d: Diagnosis): List<String> {
        val out = ArrayList<String>()

        // 目标速度：比当前低一档，并给出具体数字
        val targetBpm = ((m.strokesPerMin * 0.75 / 20.0).roundToInt() * 20).coerceIn(120, 600)
        val targetMs = if (targetBpm > 0) (60000.0 / targetBpm).roundToInt() else 0

        when (d.evenness) {
            EvennessKind.SUSTAINED -> when (d.trend) {
                // 后段才散的，问题不是速度，是耐力：别劝他降速压抖动
                Trend.WORSE -> out.add("后段才是问题：按 20 秒一组练 3 组，每组都保持前段那种 " +
                    "${d.headJitterPct.roundToInt()}% 的手感，比一口气弹一分钟更有效。")
                Trend.BETTER -> out.add("前段偏散是没热开：先用 10 秒慢速热手，再进入正式练习。")
                Trend.FLAT -> out.add("先把速度降到 $targetBpm 音/分（约 ${targetMs}ms/击），用节拍器把抖动压到 " +
                    "${JITTER_FAIR.roundToInt()}% 以内，再逐档加速。")
            }
            EvennessKind.DROPS ->
                out.add("有 ${d.dropCount} 处漏击：在最慢档一格一格确认每一击都出声，宁可慢也不要跳音。")
            EvennessKind.BREAKS ->
                out.add("${d.stallCount + d.rushCount} 处停顿/抢拍：先分辨是换弦换音（正常）还是手上断了；" +
                    "若是后者，放慢到 ${targetBpm} 音/分 把轮子连起来。")
            EvennessKind.MIXED ->
                out.add("多数击点没问题，重点是把零星塌陷的那几处单独挑出来重弹，录一小段慢速对照。")
            EvennessKind.UNIFORM -> {}
            EvennessKind.UNKNOWN -> {}
        }

        // 走势的通用建议；均匀度那一支已经给了更具体的，就别重复
        if (d.evenness != EvennessKind.SUSTAINED) {
            when (d.trend) {
                Trend.WORSE -> out.add("后段明显变散：把练习拆成 3 组、每组 20 秒，组间放松手腕，避免尾巴垮掉。")
                Trend.BETTER -> out.add("越弹越稳，说明前段还在找状态；可以先用 10 秒慢速热身再进入正式练习。")
                Trend.FLAT -> {}
            }
        }
        when (d.tempoDrift) {
            TempoDrift.FASTER -> out.add("速度会自己往上飘：跟着节拍器锁速，整段保持同一个 音/分 再谈提速。")
            TempoDrift.SLOWER -> out.add("速度会自己往下掉：检查是不是越弹越用力，试着保持手腕放松。")
            TempoDrift.FLAT -> {}
        }

        when (d.positionShape) {
            PositionShape.SINGLE_WEAK ->
                out.add("单独强化第${d.weakestPosition + 1}击的发力，慢速轮指让轮内五位音量趋同。")
            PositionShape.FIRST_WEAK ->
                out.add("把每轮第1击（起轮）拆出来单练：慢速起轮时先咬住再走，别让它虚掉。")
            PositionShape.DECLINE ->
                out.add("按第1→第5击的顺序单指慢练，重点补最后两位的托底力量。")
            PositionShape.UNEVEN ->
                out.add("五位力度较散：用节拍器慢速轮，刻意把每一击弹到同样响。")
            else -> {}
        }

        // 速度档位建议：与均匀度无关，只给方向
        if (d.evenness == EvennessKind.UNIFORM || d.trend == Trend.BETTER) {
            when (d.tempoBand) {
                TempoBand.VERY_SLOW, TempoBand.SLOW ->
                    out.add("在当前均匀度下可以试着每档 +20 音/分，先到 ${(m.strokesPerMin + 40).roundToInt()} 音/分 看是否还稳。")
                TempoBand.MEDIUM ->
                    out.add("中速已稳，可以按 +20 音/分 逐档往上试，直到抖动开始变大为止。")
                TempoBand.FAST, TempoBand.VERY_FAST ->
                    out.add("当前速度已经不低，先保证 60 秒不散再加档，比冲峰值速度更有用。")
            }
        }
        if (out.isEmpty()) out.add("整体不错，可尝试在保持均匀的前提下每次 +20 音/分。")
        return out
    }

    // ---------------- 小工具 ----------------

    private fun positionNames() = listOf("第1击", "第2击", "第3击", "第4击", "第5击")
    private fun positionName(i: Int) = positionNames().getOrElse(i) { "第${i + 1}击" }

    fun shapeName(s: PositionShape) = when (s) {
        PositionShape.EVEN -> "五位接近"
        PositionShape.SINGLE_WEAK -> "单一弱位"
        PositionShape.FIRST_WEAK -> "起轮偏弱"
        PositionShape.DECLINE -> "逐位递减"
        PositionShape.UNEVEN -> "分散不均"
        PositionShape.UNKNOWN -> "—"
    }

    fun tempoBandName(b: TempoBand) = when (b) {
        TempoBand.VERY_SLOW -> "很慢（分解练习档）"
        TempoBand.SLOW -> "慢速"
        TempoBand.MEDIUM -> "中速"
        TempoBand.FAST -> "快速"
        TempoBand.VERY_FAST -> "极快"
    }

    /**
     * 等级徽标底色。这里只返回**资源 id**，由调用方在 Activity 里 getColor 取实例值——
     * 否则写死的浅色在深色模式下要么太暗、要么和深底糊在一起（values-night 有对应一份）。
     */
    fun levelColorRes(level: Int): Int = when (level) {
        0 -> R.color.good
        1 -> R.color.warn
        2 -> R.color.bad
        else -> R.color.text_secondary   // 仅参考
    }
}
