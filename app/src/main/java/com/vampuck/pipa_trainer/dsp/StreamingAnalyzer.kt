package com.vampuck.pipa_trainer.dsp

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Streaming lunzhi analyzer for live microphone mode.
 * Feed raw mono float samples via [push]; it maintains a running spectral-flux
 * onset detector and reports rolling metrics (recent strokes/sec, CV) plus the
 * instantaneous stroke loudness. Keeps ALL onset times so [metrics] can run
 * the same evaluation as file mode over the whole session.
 *
 * Noise gate design (rewritten — the old one was far too aggressive):
 *  - the floor is measured during the first [FLOOR_INIT_SEC] of the session and
 *    afterwards can only track DOWNWARD quickly; it may rise only while the
 *    input is quiet (so a louder room adapts) and NEVER while you are playing.
 *  - the gate is `max(gateAbsMin, floor * gateRatio)` with a low absolute
 *    minimum, and all three are user-tunable via [sensitivity].
 *  - the gate tests the RMS at the peak frame, not the current frame.
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
    private val minGapFrames = (0.040 * fps).toInt().coerceAtLeast(1)

    // recent flux for adaptive threshold (approx uniform filter over 0.12s)
    private val fluxHistory = ArrayDeque<Double>()
    private val fluxWindowLen = (0.12 * fps).toInt().coerceAtLeast(1)
    private var fluxSum = 0.0
    private var prevFlux = 0.0
    private var prevPrevFlux = 0.0
    private var runningMax = 1e-9

    // ---------------- noise floor ----------------
    private val floorInitFrames = (1.2 * fps).toInt().coerceAtLeast(1)
    private var floorInitSum = 0.0
    private var floorInitCount = 0
    private var floorReady = false

    /** Estimated background level (0..1). */
    @Volatile var noiseFloor = 0.004
        private set
    /** Current gate threshold (0..1) — exposed for the UI. */
    @Volatile var currentGate = 0.006
        private set
    /** Most recent frame RMS — exposed for the UI. */
    @Volatile var lastRms = 0.0
        private set

    /** When false the RMS gate is bypassed entirely. */
    var noiseGateEnabled = true

    // sensitivity presets: (floorRatio, absoluteMin)
    private var gateRatio = 3.0
    private var gateAbsMin = 0.004

    /**
     * 0 = 严格 (loud playing / noisy room), 1 = 标准, 2 = 灵敏 (quiet playing).
     */
    fun setSensitivity(level: Int) {
        when (level) {
            0 -> { gateRatio = 6.0; gateAbsMin = 0.015 }
            2 -> { gateRatio = 1.8; gateAbsMin = 0.0015 }
            else -> { gateRatio = 3.0; gateAbsMin = 0.004 }
        }
    }

    val onsetTimes = ArrayList<Double>()
    val strokeAmp = ArrayList<Double>()

    // ---------------- metronome masking ----------------
    private val clickTimes = ArrayList<Double>()

    /** Asymmetric mask: real clicks arrive late via output+acoustic latency. */
    var clickMaskBefore = 0.025
    var clickMaskAfter = 0.070

    /** Append a metronome click at session-relative time [tSec]. */
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
        val cv: Double,
        val totalStrokes: Int,
        val lastAmp: Double,
        val noiseFloor: Double,
        val gate: Double,
        val rms: Double,
        val gateOpen: Boolean
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
                processFrame()
                System.arraycopy(ring, hop, ring, 0, win - hop)
                ringFill = win - hop
                hopCounter = 0
            }
            i++
        }
        return live()
    }

    private val re = DoubleArray(win)
    private val im = DoubleArray(win)
    private var prevRms = 0.0

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

        // per-frame RMS (background-energy proxy)
        var rms = 0.0
        for (k in 0 until win) rms += ring[k] * ring[k]
        rms = sqrt(rms / win)
        lastRms = rms

        updateFloor(rms)

        // normalize by a slowly-decaying running max, then a relative threshold
        runningMax = maxOf(runningMax * 0.9995, flux)
        val nf = if (runningMax > 1e-9) flux / runningMax else 0.0

        fluxHistory.addLast(nf); fluxSum += nf
        if (fluxHistory.size > fluxWindowLen) fluxSum -= fluxHistory.removeFirst()
        val med = fluxSum / fluxHistory.size
        val thr = med * 1.2 + 0.02

        // gate on the RMS at the peak frame (prevRms), not the current frame
        val gate = maxOf(gateAbsMin, noiseFloor * gateRatio)
        currentGate = gate
        val peakRms = maxOf(prevRms, rms)
        val gateOk = !noiseGateEnabled || peakRms > gate

        if (gateOk && prevFlux > thr && prevFlux >= prevPrevFlux && prevFlux > nf &&
            (frameIndex - 1) - lastPeakFrame >= minGapFrames) {
            val t = (frameIndex - 1) * hop.toDouble() / sampleRate
            if (!isMetronomeMasked(t)) {
                onsetTimes.add(t)
                lastPeakFrame = frameIndex - 1
                lastStrokeAmp = prevFlux
                strokeAmp.add(prevFlux)
            }
        }
        prevPrevFlux = prevFlux
        prevFlux = nf
        prevRms = rms
        frameIndex++
    }

    /**
     * Minimum-statistics style floor: learn the room during the first second,
     * then track downward quickly and upward only while the input is quiet.
     * Crucially it can NEVER be dragged up by sustained playing.
     */
    private fun updateFloor(rms: Double) {
        if (!floorReady) {
            floorInitSum += rms
            floorInitCount++
            if (floorInitCount >= floorInitFrames) {
                noiseFloor = (floorInitSum / floorInitCount).coerceIn(1e-4, 0.2)
                floorReady = true
            }
            return
        }
        if (rms < noiseFloor) {
            // quieter than before -> track down fast
            noiseFloor = noiseFloor * 0.7 + rms * 0.3
        } else if (rms < noiseFloor * 1.5) {
            // roughly at the floor -> allow a very slow upward adaptation
            noiseFloor *= 1.0005
        }
        // else: this frame is signal (playing) -> leave the floor untouched
        if (noiseFloor < 1e-4) noiseFloor = 1e-4
    }

    private fun live(): Live {
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
        val gate = maxOf(gateAbsMin, noiseFloor * gateRatio)
        return Live(cps, cps * 60, cv, onsetTimes.size, lastStrokeAmp,
            noiseFloor, gate, lastRms, gateOpen = lastRms > gate)
    }

    /** Full-session metrics over all collected onsets/amps (shared with file mode). */
    fun metrics(): LunzhiAnalyzer.Metrics =
        LunzhiAnalyzer.metrics(onsetTimes.toDoubleArray(), strokeAmp.toDoubleArray())

    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted(); val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
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
