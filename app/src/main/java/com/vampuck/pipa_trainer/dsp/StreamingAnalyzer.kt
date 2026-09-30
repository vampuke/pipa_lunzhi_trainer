package com.vampuck.pipa_trainer.dsp

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Streaming lunzhi analyzer for live microphone mode.
 *
 * Now shares the file-mode detection rules:
 *  - spectral flux (win=1024, hop=128) normalized by a slowly-decaying running max
 *  - relative adaptive threshold (movavg*1.2 + 0.02)
 *  - local maximum + PROMINENCE (>= 0.04) + 40ms minimum distance
 *  Prominence needs look-ahead, so a peak is confirmed [W] frames after it
 *  occurs (W = 0.12s). This is what the file pipeline does in one pass, and its
 *  absence was why live mode reported many more (irregular) onsets than file mode.
 *
 *  - noise floor: learned in the first ~1.2s, then only tracks down / rises only
 *    while quiet, so sustained playing can never close the gate
 *  - metronome clicks: masked by time, AND masked frames are excluded from the
 *    flux statistics so a loud click cannot desensitize the detector
 *  - the displayed evenness uses the SAME definition as the final report
 *    (modal CV -> jitter%), so live and file numbers are comparable
 */
class StreamingAnalyzer(private val sampleRate: Int) {

    private val win = LunzhiAnalyzer.WIN
    private val hop = LunzhiAnalyzer.HOP
    private val fps = sampleRate.toDouble() / hop
    private val window = DoubleArray(win) { 0.5 - 0.5 * cos(2.0 * Math.PI * it / (win - 1)) }
    private var prevMag = DoubleArray(win / 2 + 1)

    private val ring = FloatArray(win)
    private var ringFill = 0

    private var frameIndex = 0L
    private var lastPeakFrame = -100000L
    private val minGapFrames = (0.040 * fps).toInt().coerceAtLeast(1)

    private val fluxWindowLen = (0.12 * fps).toInt().coerceAtLeast(1)
    private val fluxHistory = ArrayDeque<Double>()
    private var fluxSum = 0.0
    private var runningMax = 1e-9

    // ---- look-ahead ring so prominence can be evaluated like file mode ----
    private val PROMINENCE = 0.04
    private val W = (0.12 * fps).toInt().coerceAtLeast(3)
    private val ringLen = 2 * W + 3
    private val nfRing = DoubleArray(ringLen)
    private val medRing = DoubleArray(ringLen)
    private val rmsRing = DoubleArray(ringLen)
    private fun slot(f: Long): Int = (((f % ringLen) + ringLen) % ringLen).toInt()

    // ---- noise floor ----
    private val floorInitFrames = (1.2 * fps).toInt().coerceAtLeast(1)
    private var floorInitSum = 0.0
    private var floorInitCount = 0
    private var floorReady = false

    @Volatile var noiseFloor = 0.004
        private set
    @Volatile var currentGate = 0.006
        private set
    @Volatile var lastRms = 0.0
        private set

    var noiseGateEnabled = true

    private var gateRatio = 3.0
    private var gateAbsMin = 0.004

    fun setSensitivity(level: Int) {
        when (level) {
            0 -> { gateRatio = 6.0; gateAbsMin = 0.015 }
            2 -> { gateRatio = 1.8; gateAbsMin = 0.0015 }
            else -> { gateRatio = 3.0; gateAbsMin = 0.004 }
        }
    }

    val onsetTimes = ArrayList<Double>()
    val strokeAmp = ArrayList<Double>()

    // ---- metronome masking ----
    private val clickTimes = ArrayList<Double>()
    var clickMaskBefore = 0.012
    var clickMaskAfter = 0.038

    /** Scale the mask with the beat so it can never eat most of a fast beat. */
    fun setMetronomeBeat(periodSec: Double) {
        clickMaskBefore = (0.10 * periodSec).coerceIn(0.006, 0.015)
        clickMaskAfter = (0.16 * periodSec).coerceIn(0.015, 0.045)
    }

    fun addMetronomeClick(tSec: Double) {
        clickTimes.add(tSec)
        if (clickTimes.size > 4000) clickTimes.subList(0, clickTimes.size - 4000).clear()
    }

    fun clearMetronomeClicks() { clickTimes.clear() }

    private fun isMetronomeMasked(t: Double): Boolean {
        if (clickTimes.isEmpty()) return false
        var i = clickTimes.size - 1
        while (i >= 0 && clickTimes[i] > t - 2.0) {
            val d = t - clickTimes[i]
            if (d > -clickMaskBefore && d < clickMaskAfter) return true
            i--
        }
        return false
    }

    @Volatile var lastStrokeAmp: Double = 0.0
        private set
    @Volatile var totalSamples: Long = 0
        private set

    data class Live(
        val strokesPerSec: Double,
        val strokesPerMin: Double,
        val cv: Double,          // classic CV over the rolling window
        val modalCv: Double,     // modal CV (same definition as the final report)
        val jitterPct: Double,   // modalCv * 74
        val totalStrokes: Int,
        val lastAmp: Double,
        val noiseFloor: Double,
        val gate: Double,
        val rms: Double,
        val gateOpen: Boolean
    )

    fun push(block: FloatArray, len: Int = block.size): Live {
        var i = 0
        while (i < len) {
            ring[ringFill] = block[i]
            ringFill++
            totalSamples++
            if (ringFill == win) {
                processFrame()
                System.arraycopy(ring, hop, ring, 0, win - hop)
                ringFill = win - hop
            }
            i++
        }
        return live()
    }

    private val re = DoubleArray(win)
    private val im = DoubleArray(win)

    private fun frameTime(f: Long) = f * hop.toDouble() / sampleRate

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
        var rms = 0.0
        for (k in 0 until win) rms += ring[k] * ring[k]
        rms = sqrt(rms / win)
        lastRms = rms

        updateFloor(rms)

        val f = frameIndex
        val masked = isMetronomeMasked(frameTime(f))

        // Never let a (loud) metronome click skew the normalisation or the
        // moving average — that would desensitise the detector for ~1s after
        // every click and swallow real strokes.
        if (!masked) {
            runningMax = maxOf(runningMax * 0.9995, flux)
            val nf0 = if (runningMax > 1e-9) flux / runningMax else 0.0
            fluxHistory.addLast(nf0); fluxSum += nf0
            if (fluxHistory.size > fluxWindowLen) fluxSum -= fluxHistory.removeFirst()
        }
        val nf = if (runningMax > 1e-9) flux / runningMax else 0.0
        val med = if (fluxHistory.isEmpty()) 0.0 else fluxSum / fluxHistory.size

        nfRing[slot(f)] = nf
        medRing[slot(f)] = med
        rmsRing[slot(f)] = rms

        // confirm the frame W back (we now have the look-ahead it needs)
        val c = f - W
        if (c >= 1) confirmPeak(c, f)

        frameIndex++
    }

    /** File-mode-equivalent peak test for frame [c], with [latest] = newest frame. */
    private fun confirmPeak(c: Long, latest: Long) {
        val cv = nfRing[slot(c)]
        if (cv <= 0.0) return
        // local maximum
        if (!(cv > nfRing[slot(c - 1)] && cv >= nfRing[slot(c + 1)])) return
        // relative adaptive threshold
        if (cv <= medRing[slot(c)] * 1.2 + 0.02) return
        // prominence within +/- W
        val lo = max(0L, c - W); val hi = min(c + W, latest)
        var lm = cv; var i = c - 1
        while (i >= lo && nfRing[slot(i)] <= cv) { lm = min(lm, nfRing[slot(i)]); i-- }
        var rm = cv; var j = c + 1
        while (j <= hi && nfRing[slot(j)] <= cv) { rm = min(rm, nfRing[slot(j)]); j++ }
        if (cv - max(lm, rm) < PROMINENCE) return
        // min distance
        if (c - lastPeakFrame < minGapFrames) return
        // energy gate at the peak (use the louder of this frame and the previous)
        val g = maxOf(gateAbsMin, noiseFloor * gateRatio)
        currentGate = g
        if (noiseGateEnabled && max(rmsRing[slot(c)], rmsRing[slot(c - 1)]) <= g) return
        // metronome mask
        if (isMetronomeMasked(frameTime(c))) return

        onsetTimes.add(frameTime(c))
        strokeAmp.add(cv)
        lastStrokeAmp = cv
        lastPeakFrame = c
    }

    private fun updateFloor(rms: Double) {
        if (!floorReady) {
            floorInitSum += rms; floorInitCount++
            if (floorInitCount >= floorInitFrames) {
                noiseFloor = (floorInitSum / floorInitCount).coerceIn(1e-4, 0.2)
                floorReady = true
            }
            return
        }
        if (rms < noiseFloor) {
            noiseFloor = noiseFloor * 0.7 + rms * 0.3
        } else if (rms < noiseFloor * 1.5) {
            noiseFloor *= 1.0005
        }
        if (noiseFloor < 1e-4) noiseFloor = 1e-4
    }

    private fun live(): Live {
        val now = totalSamples.toDouble() / sampleRate
        val recent = onsetTimes.filter { it >= now - 6.0 }
        val ioi = ArrayList<Double>()
        for (j in 1 until recent.size) {
            val d = recent[j] - recent[j - 1]
            if (d in LunzhiAnalyzer.MIN_IOI..LunzhiAnalyzer.MAX_IOI) ioi.add(d)
        }
        val mean = if (ioi.isNotEmpty()) ioi.average() else 0.0
        val cps = if (mean > 0) 1.0 / mean else 0.0
        val cv = if (mean > 0) std(ioi, mean) / mean else 0.0
        val med = median(ioi)
        val band = ioi.filter { it > med * 0.6 && it < med * 1.6 }
        val bandMean = if (band.isNotEmpty()) band.average() else 0.0
        val modalCv = if (bandMean > 0) std(band, bandMean) / bandMean else 0.0
        val gate = maxOf(gateAbsMin, noiseFloor * gateRatio)
        return Live(cps, cps * 60, cv, modalCv, modalCv * 74.0, onsetTimes.size,
            lastStrokeAmp, noiseFloor, gate, lastRms, gateOpen = lastRms > gate)
    }

    fun metrics(): LunzhiAnalyzer.Metrics =
        LunzhiAnalyzer.metrics(onsetTimes.toDoubleArray(), strokeAmp.toDoubleArray())

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
