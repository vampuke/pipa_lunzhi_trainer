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

    /** 分析窗长。2048 在 44.1kHz 下约 46ms，110Hz 也含 5 个周期。 */
    private val n = 2048

    /** 音域范围；超出这个范围的信号不当作乐音。 */
    private val minHz = 60.0
    private val maxHz = 1300.0

    private val buf = FloatArray(n)
    private var count = 0

    /** 上一次输出的频率，用来抑制指针抖动。 */
    private var smoothHz = 0.0

    /** 低于这个 RMS 视作没有拨弦。 */
    var minLevel = 0.004

    /** 清晰度门限：YIN 归一化差值要低到这个程度才认。 */
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
        smoothHz = 0.0
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

        // 第一个低于阈值的局部极小（避免把 2 倍周期当基频 -> 低八度错）
        val threshold = 1.0 - minClarity
        var bestLag = -1
        var lag = minLag
        while (lag <= maxLag) {
            if (cm[lag] < threshold) {
                while (lag + 1 <= maxLag && cm[lag + 1] < cm[lag]) lag++
                bestLag = lag
                break
            }
            lag++
        }
        if (bestLag < 0) {
            // 没有低于阈值：取全局最小，但仍要求足够清晰（否则就是噪声）
            var bi = minLag
            for (l in minLag..maxLag) if (cm[l] < cm[bi]) bi = l
            if (cm[bi] > threshold) return null
            bestLag = bi
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
        var hz = sampleRate / refined
        if (hz < minHz || hz > maxHz) return null

        // 指针平滑：同一根弦内做轻度低通，换弦（差得远）时立刻跟随
        smoothHz = if (smoothHz > 0.0 && abs(hz - smoothHz) < smoothHz * 0.06) {
            smoothHz * 0.65 + hz * 0.35
        } else {
            hz
        }
        hz = smoothHz

        return Reading(hz, clarity, level, Tuning.nearestNote(hz), Tuning.nearestString(hz))
    }
}
