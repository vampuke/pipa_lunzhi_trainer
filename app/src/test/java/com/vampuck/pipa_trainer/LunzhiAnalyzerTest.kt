package com.vampuck.pipa_trainer

import com.vampuck.pipa_trainer.dsp.LunzhiAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Validates the DSP on a synthetic click train with known spacing.
 * A perfectly even train at 130ms should recover ~7.7 strokes/s and a
 * very low CV; a jittered train should recover a higher CV. This locks the
 * pipeline behavior so the ported analyzer stays faithful to the reference.
 */
class LunzhiAnalyzerTest {

    private val sr = 22050

    private fun clickTrain(intervalsSec: DoubleArray, freq: Double = 800.0): FloatArray {
        val total = intervalsSec.sum() + 0.3
        val out = FloatArray((total * sr).toInt())
        var t = 0.15
        for (iv in intervalsSec) {
            val start = (t * sr).toInt()
            // short decaying tone burst = plucked-string-like transient
            val dur = (0.06 * sr).toInt()
            for (k in 0 until dur) {
                val idx = start + k
                if (idx < out.size) {
                    val env = exp(-k / (0.012 * sr))
                    out[idx] += (sin(2 * PI * freq * k / sr) * env).toFloat()
                }
            }
            t += iv
        }
        return out
    }

    @Test
    fun evenTrainHasLowCv() {
        val n = 40
        val intervals = DoubleArray(n) { 0.130 }
        val res = LunzhiAnalyzer.analyze(clickTrain(intervals), sr)
        // should detect close to n strokes
        assertTrue("detected ${res.onsetTimes.size}", res.onsetTimes.size >= n - 4)
        // rate near 1/0.130 = 7.69/s
        assertEquals(7.69, res.strokesPerSec, 1.2)
        // very even -> low CV
        assertTrue("cv=${res.cv}", res.cv < 0.15)
    }

    @Test
    fun jitteredTrainHasHigherCv() {
        val rnd = java.util.Random(42)
        val intervals = DoubleArray(40) { 0.130 + (rnd.nextDouble() - 0.5) * 0.08 }
        val res = LunzhiAnalyzer.analyze(clickTrain(intervals), sr)
        assertTrue("cv=${res.cv}", res.cv > 0.10)
    }

    @Test
    fun fiveFoldPicksWeakPosition() {
        // build amps where position 5 (index 4) is consistently weak
        val amps = DoubleArray(50) { i ->
            when (i % 5) { 4 -> 0.5; else -> 1.0 }
        }
        val (_, profile) = LunzhiAnalyzer.fiveFold(amps)
        val weakest = profile.indices.minByOrNull { profile[it] }
        assertEquals(4, weakest)
        assertTrue(profile[4] < 0.7)
    }
}
