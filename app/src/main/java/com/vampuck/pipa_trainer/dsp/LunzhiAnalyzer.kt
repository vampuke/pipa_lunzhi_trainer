package com.vampuck.pipa_trainer.dsp

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Core lunzhi (tremolo) analysis.
 *
 * Pipeline: spectral-flux onset detection (win=1024, hop=128) -> peak picking
 * (relative adaptive threshold + prominence + 40ms min distance) -> sub-frame
 * parabolic refinement -> inter-onset intervals -> metrics.
 *
 * Validated against synthetic rolls: a perfectly even train yields CV ~0.003,
 * 5% timing jitter -> ~0.07, 10% -> ~0.14, 15% -> ~0.21, 20% -> ~0.27.
 * The min onset distance (40ms) allows rolls up to ~25 strokes/sec.
 */
object LunzhiAnalyzer {

    const val WIN = 1024
    const val HOP = 128
    const val MIN_IOI = 0.030
    const val MAX_IOI = 0.60
    private const val MIN_ONSET_GAP = 0.040   // 40ms -> up to 25 strokes/s
    private const val PROMINENCE = 0.04
    /** jitter% ≈ modalCv * 74 (from synthetic calibration). */
    private const val JITTER_SCALE = 74.0

    data class WindowStat(val startSec: Double, val strokes: Int, val ratePerSec: Double, val cv: Double)

    data class Metrics(
        val strokes: Int,
        val strokesPerSec: Double,
        val strokesPerMin: Double,
        val meanIoiMs: Double,
        val medianIoiMs: Double,
        val stdIoiMs: Double,
        val cv: Double,            // classic CV over all valid IOIs
        val modalCv: Double,       // CV restricted to the modal band
        val robustCv: Double,      // MAD-based robust CV
        val jitterPct: Double,     // perceptual estimate, % of interval
        val outlierCount: Int,     // IOIs outside the modal band
        val fingerProfile: DoubleArray,
        val bestPhase: Int,
        val perWindow: List<WindowStat>
    )

    data class Result(
        val sampleRate: Int,
        val durationSec: Double,
        val onsetTimes: DoubleArray,
        val strokeAmp: DoubleArray,
        val fluxEnvelope: FloatArray,
        val fluxTimes: DoubleArray,
        val metrics: Metrics
    )

    // ---------- FFT ----------
    private val hannCache = HashMap<Int, DoubleArray>()
    private fun hann(n: Int): DoubleArray = hannCache.getOrPut(n) {
        DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * Math.PI * it / (n - 1)) }
    }

    private fun rfftMag(re0: DoubleArray): DoubleArray {
        val n = re0.size
        val re = re0.copyOf(); val im = DoubleArray(n)
        fft(re, im)
        val half = n / 2
        val mag = DoubleArray(half + 1)
        for (k in 0..half) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k])
        return mag
    }

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

    private fun movingAverage(x: DoubleArray, size: Int): DoubleArray {
        if (size <= 1) return x.copyOf()
        val out = DoubleArray(x.size)
        val pre = DoubleArray(x.size + 1)
        for (i in x.indices) pre[i + 1] = pre[i] + x[i]
        val half = size / 2
        for (i in x.indices) {
            val lo = max(0, i - half); val hi = min(x.size - 1, i + half)
            out[i] = (pre[hi + 1] - pre[lo]) / (hi - lo + 1)
        }
        return out
    }

    /** Spectral-flux onset detection function, normalized to 0..1. */
    fun fluxOf(samples: FloatArray, sampleRate: Int): Pair<DoubleArray, Double> {
        var peak = 1e-9f
        for (v in samples) { val a = abs(v); if (a > peak) peak = a }
        val invPeak = 1.0 / peak.toDouble()
        val n = samples.size
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
        for (i in flux.indices) flux[i] = flux[i] / fmax
        return Pair(flux, sampleRate.toDouble() / HOP)
    }

    /** Peak picking: relative adaptive threshold + min distance + prominence. */
    fun pickPeaks(flux: DoubleArray, fps: Double): IntArray {
        if (flux.isEmpty()) return IntArray(0)
        val med = movingAverage(flux, (0.12 * fps).toInt().coerceAtLeast(1))
        val minGap = (MIN_ONSET_GAP * fps).toInt().coerceAtLeast(1)
        val w = (0.12 * fps).toInt().coerceAtLeast(1)
        val cand = ArrayList<Int>()
        var last = -minGap
        for (i in 1 until flux.size - 1) {
            val thr = med[i] * 1.2 + 0.02
            if (flux[i] > thr && flux[i] >= flux[i - 1] && flux[i] > flux[i + 1] && i - last >= minGap) {
                cand.add(i); last = i
            }
        }
        // prominence filter
        val keep = ArrayList<Int>(cand.size)
        for (p in cand) {
            val lo = max(0, p - w); val hi = min(flux.size - 1, p + w)
            var lm = flux[p]
            var i = p - 1
            while (i >= lo && flux[i] <= flux[p]) { lm = min(lm, flux[i]); i-- }
            var rm = flux[p]
            var jx = p + 1
            while (jx <= hi && flux[jx] <= flux[p]) { rm = min(rm, flux[jx]); jx++ }
            if (flux[p] - max(lm, rm) >= PROMINENCE) keep.add(p)
        }
        return keep.toIntArray()
    }

    /** Sub-frame parabolic refinement of peak positions. */
    fun refine(flux: DoubleArray, peaks: IntArray, fps: Double): DoubleArray {
        val t = DoubleArray(peaks.size)
        for (idx in peaks.indices) {
            val p = peaks[idx]
            var off = 0.0
            if (p > 0 && p < flux.size - 1) {
                val a = flux[p - 1]; val b = flux[p]; val c = flux[p + 1]
                val den = a - 2 * b + c
                if (abs(den) > 1e-12) off = (0.5 * (a - c) / den).coerceIn(-0.5, 0.5)
            }
            t[idx] = (p + off) / fps
        }
        return t
    }

    fun analyze(samples: FloatArray, sampleRate: Int): Result {
        val (flux, fps) = fluxOf(samples, sampleRate)
        val peaks = pickPeaks(flux, fps)
        val onsetTimes = refine(flux, peaks, fps)
        val dur = samples.size.toDouble() / sampleRate
        val fluxTimes = DoubleArray(flux.size) { it * HOP.toDouble() / sampleRate }

        // stroke loudness: RMS in a 45ms window after each onset
        var peak = 1e-9f
        for (v in samples) { val a = abs(v); if (a > peak) peak = a }
        val invPeak = 1.0 / peak.toDouble()
        val n = samples.size
        val halfWin = (0.045 * sampleRate).toInt()
        val amp = DoubleArray(onsetTimes.size)
        for (i in onsetTimes.indices) {
            val start = (onsetTimes[i] * sampleRate).toInt()
            var acc = 0.0; var cnt = 0
            var k = start
            while (k < min(n, start + halfWin)) { val v = samples[k] * invPeak; acc += v * v; cnt++; k++ }
            amp[i] = if (cnt > 0) sqrt(acc / cnt) else 0.0
        }

        val m = metrics(onsetTimes, amp, 0.0, Double.MAX_VALUE)
        return Result(sampleRate, dur, onsetTimes, amp,
            FloatArray(flux.size) { flux[it].toFloat() }, fluxTimes, m)
    }

    /** Recompute metrics for onsets within [from, to] (seconds). */
    fun metrics(onsetTimes: DoubleArray, amp: DoubleArray,
                from: Double = 0.0, to: Double = Double.MAX_VALUE): Metrics {
        val idx = ArrayList<Int>()
        for (i in onsetTimes.indices) if (onsetTimes[i] >= from && onsetTimes[i] <= to) idx.add(i)
        val t = DoubleArray(idx.size) { onsetTimes[idx[it]] }
        val a = DoubleArray(idx.size) { amp[idx[it]] }

        val ioiAll = ArrayList<Double>()
        for (i in 1 until t.size) ioiAll.add(t[i] - t[i - 1])
        val ioi = ioiAll.filter { it in MIN_IOI..MAX_IOI }

        val mean = if (ioi.isNotEmpty()) ioi.average() else 0.0
        val med = median(ioi)
        val sd = std(ioi, mean)
        val cv = if (mean > 0) sd / mean else 0.0
        val cps = if (mean > 0) 1.0 / mean else 0.0

        val band = ioi.filter { it > med * 0.6 && it < med * 1.6 }
        val bandMean = if (band.isNotEmpty()) band.average() else 0.0
        val modalCv = if (bandMean > 0) std(band, bandMean) / bandMean else 0.0
        val outliers = ioi.count { it <= med * 0.6 || it >= med * 1.6 }
        val mad = median(ioi.map { abs(it - med) })
        val robustCv = if (med > 0) 1.4826 * mad / med else 0.0
        val jitterPct = (modalCv.takeIf { it > 0 } ?: cv) * JITTER_SCALE

        val (phase, profile) = fiveFold(a)

        val windows = ArrayList<WindowStat>()
        if (t.isNotEmpty()) {
            val t0 = t.first(); val t1 = t.last()
            var s = t0
            while (s <= t1) {
                val e = s + 5.0
                val seg = ArrayList<Double>()
                for (x in t) if (x >= s && x < e) seg.add(x)
                if (seg.size > 2) {
                    val w = ArrayList<Double>()
                    for (i in 1 until seg.size) { val d = seg[i] - seg[i - 1]; if (d in MIN_IOI..MAX_IOI) w.add(d) }
                    if (w.isNotEmpty()) {
                        val wm = w.average()
                        windows.add(WindowStat(s - from, seg.size, 1.0 / wm, std(w, wm) / wm))
                    }
                }
                s += 5.0
            }
        }

        return Metrics(t.size, cps, cps * 60, mean * 1000, med * 1000, sd * 1000,
            cv, modalCv, robustCv, jitterPct, outliers, profile, phase, windows)
    }

    /** IOI list (seconds) for a range — used for distribution display. */
    fun iois(onsetTimes: DoubleArray, from: Double, to: Double): List<Double> {
        val s = onsetTimes.filter { it in from..to }
        val out = ArrayList<Double>()
        for (i in 1 until s.size) {
            val d = s[i] - s[i - 1]
            if (d in MIN_IOI..MAX_IOI) out.add(d)
        }
        return out
    }

    /** Fold amps onto a 5-stroke cycle, pick phase with largest position spread. */
    fun fiveFold(amp: DoubleArray): Pair<Int, DoubleArray> {
        var best = -1.0; var bestPhase = 0
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
        val s = v.sorted(); val m = s.size / 2
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
