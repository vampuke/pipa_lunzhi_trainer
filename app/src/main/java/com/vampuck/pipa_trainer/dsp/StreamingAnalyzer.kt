package com.vampuck.pipa_trainer.dsp

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Streaming lunzhi analyzer for live microphone mode.
 * Feed raw mono float samples via [push]; it maintains a running spectral-flux
 * onset detector and reports rolling metrics (recent strokes/sec, CV) plus the
 * instantaneous stroke loudness. Keeps ALL onset times so [finalize] can run
 * the same evaluation as file mode over the whole session.
 */
class StreamingAnalyzer(private val sampleRate: Int) {

    private val win = LunzhiAnalyzer.WIN
    private val hop = LunzhiAnalyzer.HOP
    private val fps = sampleRate.toDouble() / hop
    private val window = DoubleArray(win) { 0.5 - 0.5 * cos(2.0 * Math.PI * it / (win - 1)) }
    private var prevMag = DoubleArray(win / 2 + 1)

    private val ring = FloatArray(win)
    private var ringFill = 0
    private var hopCounter = 0

    private var frameIndex = 0L
    private var lastPeakFrame = -1000L
    private val minGapFrames = (0.073 * fps).toInt().coerceAtLeast(1)

    // recent flux for adaptive threshold (approx uniform filter over 0.12s)
    private val fluxHistory = ArrayDeque<Double>()
    private val fluxWindowLen = (0.12 * fps).toInt().coerceAtLeast(1)
    private var fluxSum = 0.0
    private var prevFlux = 0.0
    private var prevPrevFlux = 0.0

    val onsetTimes = ArrayList<Double>()
    val strokeAmp = ArrayList<Double>()

    // for instantaneous loudness we track recent peak sample amplitude
    @Volatile var lastStrokeAmp: Double = 0.0
        private set
    @Volatile var totalSamples: Long = 0
        private set

    data class Live(
        val strokesPerSec: Double,
        val strokesPerMin: Double,
        val cv: Double,
        val totalStrokes: Int,
        val lastAmp: Double
    )

    /** Push a block of mono float samples. Returns updated live metrics. */
    fun push(block: FloatArray, len: Int = block.size): Live {
        var i = 0
        while (i < len) {
            ring[ringFill] = block[i]
            ringFill++
            hopCounter++
            totalSamples++
            if (ringFill == win) {
                // full frame available; compute flux, then slide by hop
                processFrame()
                // shift left by hop
                System.arraycopy(ring, hop, ring, 0, win - hop)
                ringFill = win - hop
                hopCounter = 0
            }
            i++
        }
        return live()
    }

    private val buf = DoubleArray(win)
    private val re = DoubleArray(win)
    private val im = DoubleArray(win)

    private fun processFrame() {
        for (k in 0 until win) { re[k] = ring[k] * window[k]; im[k] = 0.0 }
        fft(re, im)
        var flux = 0.0
        val half = win / 2
        for (k in 0..half) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k])
            val d = mag - prevMag[k]
            if (d > 0) flux += d
            prevMag[k] = mag
        }
        // normalize loosely by a running max to keep threshold ~ same scale as file mode
        runningMax = maxOf(runningMax * 0.9995, flux)
        val nf = if (runningMax > 1e-9) flux / runningMax else 0.0

        // adaptive threshold via moving average
        fluxHistory.addLast(nf); fluxSum += nf
        if (fluxHistory.size > fluxWindowLen) fluxSum -= fluxHistory.removeFirst()
        val med = fluxSum / fluxHistory.size
        val thr = med + 0.05

        // peak test on prevFlux (center of 3)
        if (prevFlux > thr && prevFlux >= prevPrevFlux && prevFlux > nf &&
            (frameIndex - 1) - lastPeakFrame >= minGapFrames) {
            val t = (frameIndex - 1) * hop.toDouble() / sampleRate
            onsetTimes.add(t)
            lastPeakFrame = frameIndex - 1
            lastStrokeAmp = prevFlux
            strokeAmp.add(prevFlux)
        }
        prevPrevFlux = prevFlux
        prevFlux = nf
        frameIndex++
    }

    private var runningMax = 1e-9

    private fun live(): Live {
        // rolling window: last 4 seconds of onsets
        val now = totalSamples.toDouble() / sampleRate
        val recent = onsetTimes.filter { it >= now - 4.0 }
        val ioi = ArrayList<Double>()
        for (j in 1 until recent.size) {
            val d = recent[j] - recent[j - 1]
            if (d in LunzhiAnalyzer.MIN_IOI..LunzhiAnalyzer.MAX_IOI) ioi.add(d)
        }
        val mean = if (ioi.isNotEmpty()) ioi.average() else 0.0
        val cps = if (mean > 0) 1.0 / mean else 0.0
        var sd = 0.0
        if (ioi.isNotEmpty()) { for (d in ioi) sd += (d - mean) * (d - mean); sd = sqrt(sd / ioi.size) }
        val cv = if (mean > 0) sd / mean else 0.0
        return Live(cps, cps * 60, cv, onsetTimes.size, lastStrokeAmp)
    }

    /** Full-session summary over all collected onsets/amps. */
    fun finalize(): Summary {
        val times = onsetTimes.toDoubleArray()
        val ioiAll = ArrayList<Double>()
        for (j in 1 until times.size) ioiAll.add(times[j] - times[j - 1])
        val ioi = ioiAll.filter { it in LunzhiAnalyzer.MIN_IOI..LunzhiAnalyzer.MAX_IOI }
        val mean = if (ioi.isNotEmpty()) ioi.average() else 0.0
        val cps = if (mean > 0) 1.0 / mean else 0.0
        val med = median(ioi)
        var sd = 0.0
        if (ioi.isNotEmpty()) { for (d in ioi) sd += (d - mean) * (d - mean); sd = sqrt(sd / ioi.size) }
        val cv = if (mean > 0) sd / mean else 0.0
        val band = ioi.filter { it > med * 0.6 && it < med * 1.6 }
        val bMean = if (band.isNotEmpty()) band.average() else 0.0
        var bsd = 0.0
        if (band.isNotEmpty()) { for (d in band) bsd += (d - bMean) * (d - bMean); bsd = sqrt(bsd / band.size) }
        val modalCv = if (bMean > 0) bsd / bMean else 0.0
        val (phase, profile) = LunzhiAnalyzer.fiveFold(strokeAmp.toDoubleArray())
        val dur = totalSamples.toDouble() / sampleRate
        return Summary(dur, times.size, cps, cps * 60, mean * 1000, med * 1000,
            sd * 1000, cv, modalCv, phase, profile, times)
    }

    data class Summary(
        val durationSec: Double,
        val totalStrokes: Int,
        val strokesPerSec: Double,
        val strokesPerMin: Double,
        val meanIoiMs: Double,
        val medianIoiMs: Double,
        val stdIoiMs: Double,
        val cv: Double,
        val modalCv: Double,
        val bestPhase: Int,
        val fingerProfile: DoubleArray,
        val onsetTimes: DoubleArray
    )

    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted(); val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    // shared FFT (same as LunzhiAnalyzer)
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
}
