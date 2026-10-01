package com.vampuck.pipa_trainer.dsp

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 单音基频检测，用于调音器。
 *
 * 用 **YIN 的累积均值归一化差值函数**，而不是直接找 FFT 峰值：win=1024 在
 * 44.1kHz 下是 43Hz 一格，A2(110Hz) 处一格就有 600 多音分，根本不够调音用；
 * 而 YIN 的差值函数在时域上比较波形自身，再对极小点做抛物线插值，可以做到
 * 几音分以内。
 *
 * 累积均值归一化（`d'(lag) = d(lag) / mean(d(1..lag))`）是 YIN 的关键一步：
 * 它把「差值本身很小」这件事除掉，只留下「明显比周围更像周期」的滞后，
 * 因此对弦振动的衰减、以及基频弱、泛音强（琵琶低音弦就是这样）都不敏感。
 *
 * 噪声不会给出读数：随机信号没有真正的周期性极小点，清晰度上不去，
 * 由 [Reading.clarity] 门限挡掉。
 */
class Tuner(private val sampleRate: Int) {

    data class Reading(
        val hz: Double,
        val clarity: Double,
        val level: Double,
        val note: Tuning.NoteReading,
        val string: Tuning.StringMatch
    )

    /**
     * 分析窗长。2048 在 44.1kHz 下约 46ms，110Hz 也含 5 个周期。
     * 注意：窗长决定精度，**刷新率由调用方每次 push 的长度决定**——按 512 采样
     * 推送时约 86 次/秒、相邻两窗重叠 75%，指针才稳；按 100ms 推送时相邻两窗
     * 完全不重叠，等于每次都在量一段全新的音频，指针必然跳。
     */
    private val n = 2048

    /** 音域范围；超出这个范围的信号不当作乐音。 */
    private val minHz = 60.0
    private val maxHz = 1300.0

    /**
     * 周期选择：先在归一化差值函数上取「第一个低于绝对阈值的局部极小」，
     * 再对**勉强合格**的谷做低八度修正（详见 analyze()）。
     */

    private val buf = FloatArray(n)
    private var count = 0

    /** 近几次估计，用于抑制指针抖动。 */
    private val recent = ArrayDeque<Double>()
    private var outlierRun = 0

    /** 低于这个 RMS 视作没有拨弦。 */
    var minLevel = 0.004

    /** 清晰度门限：YIN 归一化差值要低到这个程度才认（各参考实现取 0.80~0.90）。 */
    var minClarity = 0.72

    fun push(block: FloatArray, len: Int = block.size): Reading? {
        if (len <= 0) return null
        if (len >= n) {
            System.arraycopy(block, len - n, buf, 0, n)
            count = n
        } else {
            if (count + len > n) {
                val drop = count + len - n
                System.arraycopy(buf, drop, buf, 0, count - drop)
                count -= drop
            }
            System.arraycopy(block, 0, buf, count, len)
            count += len
        }
        if (count < n) return null
        return analyze()
    }

    fun reset() {
        count = 0
        recent.clear()
        outlierRun = 0
    }

    private fun analyze(): Reading? {
        // 电平：先去掉直流再算 RMS
        var mean = 0.0
        for (i in 0 until n) mean += buf[i]
        mean /= n
        var acc = 0.0
        for (i in 0 until n) {
            val v = buf[i] - mean
            acc += v * v
        }
        val level = sqrt(acc / n)
        if (level < minLevel) return null

        val minLag = (sampleRate / maxHz).toInt().coerceAtLeast(2)
        val maxLag = (sampleRate / minHz).toInt().coerceAtMost(n / 2)
        if (maxLag <= minLag + 2) return null

        // YIN 差值函数（直流已被 x[i]-x[i+lag] 抵消）
        val d = DoubleArray(maxLag + 1)
        for (lag in 1..maxLag) {
            var s = 0.0
            val lim = n - lag
            var i = 0
            while (i < lim) {
                val diff = buf[i] - buf[i + lag]
                s += diff * diff
                i++
            }
            d[lag] = s
        }

        // 累积均值归一化
        val cm = DoubleArray(maxLag + 1)
        cm[0] = 1.0
        var run = 0.0
        for (lag in 1..maxLag) {
            run += d[lag]
            cm[lag] = if (run > 0.0) d[lag] * lag / run else 1.0
        }

        // 第一步：噪声门限——最深的谷都不够深，就不是乐音。
        val threshold = 1.0 - minClarity
        var deepest = minLag
        for (l in minLag..maxLag) if (cm[l] < cm[deepest]) deepest = l
        if (cm[deepest] > threshold) return null

        // 第二步：基频周期 = 谷值与最深谷**几乎一样深**的最短滞后。
        //
        // 为什么是这个判据（两种相反的错误都在实测里出现过）：
        //  - 取「第一个低于绝对阈值的谷」会挑到 2 倍频：110Hz 且 2 次谐波主导时，
        //    T/2 处会有一个浅谷（奇次谐波带来的失配不大），实测读成 220.7Hz。
        //  - 取「最深的谷」则会掉八度：A3(220Hz) 的 T 与 2T 都是完美周期
        //    （都是整数倍谐波），谁更深只由数值噪声决定，实测 A3/D3/E3 全掉一个八度。
        // 「和最深谷一样深」同时排除这两类：伪谷（T/2）自带真实失配、谷明显不够深；
        // 而 2T 虽然和 T 一样深，但 T 更短，取短的即得到真正的基频。
        val cutoff = cm[deepest] + DEPTH_MARGIN
        var bestLag = deepest
        var lag = minLag
        while (lag <= maxLag) {
            if (cm[lag] <= cutoff) {
                while (lag + 1 <= maxLag && cm[lag + 1] < cm[lag]) lag++
                if (cm[lag] <= cutoff) { bestLag = lag; break }
            }
            lag++
        }

        val clarity = (1.0 - cm[bestLag]).coerceIn(0.0, 1.0)

        // 抛物线插值，拿到亚采样精度
        var refined = bestLag.toDouble()
        if (bestLag in 1 until maxLag) {
            val a = cm[bestLag - 1]
            val b = cm[bestLag]
            val c = cm[bestLag + 1]
            val den = a - 2 * b + c
            if (abs(den) > 1e-12) refined += (0.5 * (a - c) / den).coerceIn(-0.5, 0.5)
        }
        if (refined <= 0.0) return null
        val raw = sampleRate / refined
        if (raw < minHz || raw > maxHz) return null
        val hz = smooth(raw)

        return Reading(hz, clarity, level, Tuning.nearestNote(hz), Tuning.nearestString(hz))
    }

    /**
     * 近 [SMOOTH_N] 次估计的滑动平均，带离群点抑制。
     *
     * 单帧坏值（拨弦瞬间、突发噪声）不应该让指针跳一下；但真正的换弦/换音
     * 必须立刻跟上——连续 [MAX_OUTLIERS] 帧都偏离就清空历史重新开始。
     */
    private fun smooth(hz: Double): Double {
        if (recent.isEmpty()) {
            recent.addLast(hz)
            return hz
        }
        val mean = recent.average()
        if (abs(hz - mean) > mean * OUTLIER_REL) {
            outlierRun++
            if (outlierRun < MAX_OUTLIERS) return mean
            recent.clear()
            outlierRun = 0
            recent.addLast(hz)
            return hz
        }
        outlierRun = 0
        recent.addLast(hz)
        if (recent.size > SMOOTH_N) recent.removeFirst()
        return recent.average()
    }

    private companion object {
        const val SMOOTH_N = 5
        const val OUTLIER_REL = 0.08
        const val MAX_OUTLIERS = 3

        /** 谷值与最深谷相差不超过这个绝对量时，认为「一样深」。 */
        const val DEPTH_MARGIN = 0.02
    }
}