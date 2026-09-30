package com.vampuck.pipa_trainer.dsp

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Core lunzhi (tremolo) analysis. Ports the Python reference pipeline:
 *  - spectral-flux onset envelope (win=1024, hop=128)
 *  - adaptive peak-picking -> stroke onset times
 *  - inter-onset intervals -> strokes/sec, strokes/min, CV (evenness)
 *  - stroke loudness (RMS window after each onset)
 *  - 5-stroke fold -> per-finger-position loudness profile
 *
 * All input is mono Float samples in [-1, 1] at [sampleRate].
 */
object LunzhiAnalyzer {

    const val WIN = 1024
    const val HOP = 128
    const val MIN_IOI = 0.03   // 30 ms
    const val MAX_IOI = 0.60   // 600 ms

    data class Result(
        val sampleRate: Int,
        val durationSec: Double,
        val onsetTimes: DoubleArray,      // seconds
        val fluxEnvelope: FloatArray,     // normalized 0..1
        val fluxTimes: DoubleArray,       // seconds per flux frame
        val strokesPerSec: Double,
        val strokesPerMin: Double,
        val meanIoiMs: Double,
        val medianIoiMs: Double,
        val stdIoiMs: Double,
        val cv: Double,                   // coefficient of variation of IOI
        val modalCv: Double,              // CV restricted to modal band
        val strokeAmp: DoubleArray,       // per-stroke RMS (linear)
        val fingerProfile: DoubleArray,   // 5 values, normalized to max=1
        val bestPhase: Int,
        val perWindow: List<WindowStat>   // 5s windows
    )

    data class WindowStat(
        val startSec: Double,
        val strokes: Int,
        val ratePerSec: Double,
        val cv: Double
    )

    private val hannCache = HashMap<Int, DoubleArray>()
    private fun hann(n: Int): DoubleArray = hannCache.getOrPut(n) {
        DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * Math.PI * it / (n - 1)) }
    }

    /** Real FFT magnitude via a simple radix helper (n must be power of two). */
    private fun rfftMag(re0: DoubleArray): DoubleArray {
        val n = re0.size
        val re = re0.copyOf()
        val im = DoubleArray(n)
        fft(re, im)
        val half = n / 2
        val mag = DoubleArray(half + 1)
        for (k in 0..half) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k])
        return mag
    }

    /** In-place iterative Cooley-Tukey FFT, n power of two. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = cos(ang); val wi = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var curR = 1.0; var curI = 0.0
                for (k in 0 until len / 2) {
                    val uR = re[i + k]; val uI = im[i + k]
                    val vR = re[i + k + len / 2] * curR - im[i + k + len / 2] * curI
                    val vI = re[i + k + len / 2] * curI + im[i + k + len / 2] * curR
                    re[i + k] = uR + vR; im[i + k] = uI + vI
                    re[i + k + len / 2] = uR - vR; im[i + k + len / 2] = uI - vI
                    val ncr = curR * wr - curI * wi
                    curI = curR * wi + curI * wr; curR = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun uniformFilter1d(x: DoubleArray, size: Int): DoubleArray {
        if (size <= 1) return x.copyOf()
        val out = DoubleArray(x.size)
        val half = size / 2
        var sum = 0.0
        // prefix-sum approach
        val pre = DoubleArray(x.size + 1)
        for (i in x.indices) pre[i + 1] = pre[i] + x[i]
        for (i in x.indices) {
            val lo = maxOf(0, i - half)
            val hi = minOf(x.size - 1, i + half)
            out[i] = (pre[hi + 1] - pre[lo]) / (hi - lo + 1)
        }
        return out
    }

    /** Full-signal analysis (file mode). */
    fun analyze(samples: FloatArray, sampleRate: Int): Result {
        // normalize (no full-length copy — read straight from the FloatArray)
        var peak = 1e-9f
        for (v in samples) { val a = abs(v); if (a > peak) peak = a }
        val invPeak = 1.0 / peak.toDouble()
        val n = samples.size
        val dur = n.toDouble() / sampleRate

        val nFrames = if (n >= WIN) 1 + (n - WIN) / HOP else 0
        val window = hann(WIN)
        var prev = DoubleArray(WIN / 2 + 1)
        val flux = DoubleArray(nFrames)
        val buf = DoubleArray(WIN)
        for (i in 0 until nFrames) {
            val off = i * HOP
            for (k in 0 until WIN) buf[k] = samples[off + k] * invPeak * window[k]
            val mag = rfftMag(buf)
            var s = 0.0
            for (k in mag.indices) { val d = mag[k] - prev[k]; if (d > 0) s += d }
            flux[i] = s
            prev = mag
        }
        var fmax = 1e-9
        for (v in flux) if (v > fmax) fmax = v
        for (i in flux.indices) flux[i] /= fmax
        val fps = sampleRate.toDouble() / HOP
        val fluxTimes = DoubleArray(nFrames) { it * HOP.toDouble() / sampleRate }

        // peak picking
        val onsets = pickPeaks(flux, fps)
        val onsetTimes = DoubleArray(onsets.size) { onsets[it] * HOP.toDouble() / sampleRate }

        // IOIs
        val allIoi = ArrayList<Double>()
        for (i in 1 until onsetTimes.size) allIoi.add(onsetTimes[i] - onsetTimes[i - 1])
        val ioi = allIoi.filter { it in MIN_IOI..MAX_IOI }

        val meanIoi = if (ioi.isNotEmpty()) ioi.average() else 0.0
        val medIoi = median(ioi)
        val stdIoi = std(ioi, meanIoi)
        val cv = if (meanIoi > 0) stdIoi / meanIoi else 0.0
        val cps = if (meanIoi > 0) 1.0 / meanIoi else 0.0

        // modal band CV (0.6x..1.6x of median)
        val band = ioi.filter { it > medIoi * 0.6 && it < medIoi * 1.6 }
        val bandMean = if (band.isNotEmpty()) band.average() else 0.0
        val modalCv = if (bandMean > 0) std(band, bandMean) / bandMean else 0.0

        // stroke loudness
        val amp = DoubleArray(onsets.size)
        val halfWin = (0.045 * sampleRate).toInt()
        for (i in onsets.indices) {
            val start = onsets[i] * HOP
            var acc = 0.0; var cnt = 0
            var k = start
            while (k < minOf(n, start + halfWin)) {
                val v = samples[k] * invPeak
                acc += v * v; cnt++; k++
            }
            amp[i] = if (cnt > 0) sqrt(acc / cnt) else 0.0
        }

        // 5-fold finger profile, best phase = max spread
        val (bestPhase, profile) = fiveFold(amp)

        // per 5s window
        val windows = ArrayList<WindowStat>()
        var start = 0.0
        while (start < dur) {
            val seg = ArrayList<Double>()
            for (t in onsetTimes) if (t >= start && t < start + 5) seg.add(t)
            if (seg.size > 2) {
                val wioi = ArrayList<Double>()
                for (i in 1 until seg.size) {
                    val d = seg[i] - seg[i - 1]
                    if (d in MIN_IOI..MAX_IOI) wioi.add(d)
                }
                if (wioi.isNotEmpty()) {
                    val m = wioi.average()
                    windows.add(WindowStat(start, seg.size, 1.0 / m, std(wioi, m) / m))
                }
            }
            start += 5
        }

        return Result(
            sampleRate, dur, onsetTimes, FloatArray(flux.size) { flux[it].toFloat() },
            fluxTimes, cps, cps * 60, meanIoi * 1000, medIoi * 1000, stdIoi * 1000,
            cv, modalCv, amp, profile, bestPhase, windows
        )
    }

    fun pickPeaks(flux: DoubleArray, fps: Double): IntArray {
        if (flux.isEmpty()) return IntArray(0)
        val med = uniformFilter1d(flux, (0.12 * fps).toInt().coerceAtLeast(1))
        val minGap = (0.073 * fps).toInt().coerceAtLeast(1)
        val peaks = ArrayList<Int>()
        var last = -minGap
        for (i in 1 until flux.size - 1) {
            val thr = med[i] + 0.05
            if (flux[i] > thr && flux[i] >= flux[i - 1] && flux[i] > flux[i + 1] && i - last >= minGap) {
                peaks.add(i); last = i
            }
        }
        return peaks.toIntArray()
    }

    /** Fold amps onto 5-stroke cycle, pick phase with largest position spread. */
    fun fiveFold(amp: DoubleArray): Pair<Int, DoubleArray> {
        var best = -1.0
        var bestPhase = 0
        var bestProfile = DoubleArray(5) { 1.0 }
        for (phase in 0 until 5) {
            val n = amp.size - phase
            val cycles = n / 5
            if (cycles < 2) continue
            val posMean = DoubleArray(5)
            for (c in 0 until cycles) for (p in 0 until 5) posMean[p] += amp[phase + c * 5 + p]
            for (p in 0 until 5) posMean[p] = posMean[p] / cycles
            val spread = (posMean.maxOrNull() ?: 0.0) - (posMean.minOrNull() ?: 0.0)
            if (spread > best) { best = spread; bestPhase = phase; bestProfile = posMean }
        }
        val mx = bestProfile.maxOrNull() ?: 1.0
        val norm = if (mx > 0) DoubleArray(5) { bestProfile[it] / mx } else bestProfile
        return Pair(bestPhase, norm)
    }

    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    private fun std(v: List<Double>, mean: Double): Double {
        if (v.isEmpty()) return 0.0
        var acc = 0.0
        for (x in v) acc += (x - mean) * (x - mean)
        return sqrt(acc / v.size)
    }

    fun toDb(ratio: Double): Double = if (ratio <= 0) -99.0 else 20 * log10(ratio)
}
