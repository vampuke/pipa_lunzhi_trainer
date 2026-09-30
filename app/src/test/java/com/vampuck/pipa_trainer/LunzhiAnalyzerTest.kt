package com.vampuck.pipa_trainer

import com.vampuck.pipa_trainer.dsp.LunzhiAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Validates the DSP on synthetic click trains with known timing.
 * Locks in the calibration: a perfectly even roll must score a very low CV,
 * and CV must grow monotonically with injected timing jitter.
 */
class LunzhiAnalyzerTest {

    private val sr = 22050

    private fun clicksAt(times: DoubleArray, freq: Double = 800.0): FloatArray {
        val total = times.last() + 0.3
        val out = FloatArray((total * sr).toInt())
        for (t in times) {
            val start = (t * sr).toInt()
            val dur = (0.06 * sr).toInt()
            for (k in 0 until dur) {
                val idx = start + k
                if (idx < out.size) {
                    val env = exp(-k / (0.012 * sr))
                    out[idx] += (sin(2 * PI * freq * k / sr) * env).toFloat()
                }
            }
        }
        return out
    }

    private fun clickTrain(intervalsSec: DoubleArray, freq: Double = 800.0): FloatArray {
        val n = intervalsSec.size
        val t = DoubleArray(n)
        var acc = 0.15
        for (i in 0 until n) { t[i] = acc; acc += intervalsSec[i] }
        return clicksAt(t, freq)
    }

    @Test
    fun evenTrainHasLowCv() {
        val n = 40
        val intervals = DoubleArray(n) { 0.130 }
        val res = LunzhiAnalyzer.analyze(clickTrain(intervals), sr)
        assertTrue("detected ${res.onsetTimes.size}", res.onsetTimes.size >= n - 4)
        assertEquals(7.69, res.metrics.strokesPerSec, 1.2)
        assertTrue("cv=${res.metrics.cv}", res.metrics.cv < 0.15)
    }

    @Test
    fun jitteredTrainHasHigherCv() {
        val rnd = java.util.Random(42)
        val intervals = DoubleArray(40) { 0.130 + (rnd.nextDouble() - 0.5) * 0.08 }
        val res = LunzhiAnalyzer.analyze(clickTrain(intervals), sr)
        assertTrue("cv=${res.metrics.cv}", res.metrics.cv > 0.10)
    }

    @Test
    fun jitterCalibrationIsMonotonic() {
        fun cvFor(jitter: Double, seed: Long): Double {
            val rnd = java.util.Random(seed)
            val n = 200
            val t = DoubleArray(n)
            var acc = 0.15
            for (i in 0 until n) {
                t[i] = acc
                acc += 0.11 * (1.0 + (rnd.nextDouble() - 0.5) * 2 * jitter)
            }
            return LunzhiAnalyzer.analyze(clicksAt(t), sr).metrics.cv
        }
        val perfect = cvFor(0.0, 7)
        val moderate = cvFor(0.10, 7)
        val heavy = cvFor(0.20, 7)
        assertTrue("perfect cv=$perfect", perfect < 0.05)
        assertTrue("moderate cv=$moderate", moderate in 0.06..0.22)
        assertTrue("heavy>moderate ($heavy vs $moderate)", heavy > moderate)
        assertTrue("heavy cv=$heavy", heavy > 0.15)
    }

    @Test
    fun fiveFoldPicksWeakPosition() {
        // amps where position 5 (index 4) is consistently weak
        val amps = DoubleArray(50) { i -> if (i % 5 == 4) 0.5 else 1.0 }
        val (_, profile) = LunzhiAnalyzer.fiveFold(amps)
        val weakest = profile.indices.minByOrNull { profile[it] }
        assertEquals(4, weakest)
        assertTrue(profile[4] < 0.7)
    }
}
