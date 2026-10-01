package com.vampuck.pipa_trainer

import com.vampuck.pipa_trainer.dsp.StreamingAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Regression tests for the live noise gate.
 *
 * Background: the long-term noise floor may only creep *up* while the room is
 * quiet, so that sustained playing can never close the gate. That also means it
 * cannot follow a room that gets louder after the warm-up — which is the normal
 * case on a phone, where the mic gain ramps up during the first second. On the
 * OnePlus 8T test device the floor settled at 0.0104 while the room sat at
 * 0.0347, leaving the gate permanently open: ~5.5 false "strokes" per second in
 * a silent room.
 *
 * These tests model the device with one-pole low-passed noise at the measured
 * levels and pin both halves of the fix: the idle room must stay silent, and a
 * roll played into the same room must still be found.
 */
class StreamingAnalyzerNoiseTest {

    private val sr = 44100

    /** Noise with a given RMS, optionally one-pole low-passed ("room rumble"). */
    private fun noiseAtRms(seconds: Double, targetRms: Double, seed: Long, lp: Float): FloatArray {
        val rnd = java.util.Random(seed)
        val n = (seconds * sr).toInt()
        val out = FloatArray(n)
        var z = 0f
        for (i in 0 until n) {
            val w = (rnd.nextFloat() - 0.5f) * 2f
            z = if (lp > 0f) lp * z + (1f - lp) * w else w
            out[i] = z
        }
        var acc = 0.0
        for (v in out) acc += v.toDouble() * v
        val scale = (targetRms / sqrt(acc / out.size)).toFloat()
        for (i in out.indices) out[i] *= scale
        return out
    }

    private fun pushInBlocks(an: StreamingAnalyzer, s: FloatArray) {
        val block = sr / 10          // the live loop feeds 100 ms blocks
        var off = 0
        while (off < s.size) {
            val n = Math.min(block, s.size - off)
            an.push(s.copyOfRange(off, off + n), n)
            off += n
        }
    }

    /** 5-stroke roll of decaying plucks at [intervalSec], added on top of [base]. */
    private fun addRoll(base: FloatArray, intervalSec: Double, amp: Float): FloatArray {
        var t = 0.05
        val burst = (0.06 * sr).toInt()
        while (t < base.size.toDouble() / sr - 0.1) {
            val start = (t * sr).toInt()
            for (k in 0 until burst) {
                val i = start + k
                if (i >= base.size) break
                base[i] += (sin(2 * PI * 600.0 * k / sr) * exp(-k / (0.012 * sr))).toFloat() * amp
            }
            t += intervalSec
        }
        return base
    }

    @Test
    fun idleRoomProducesNoFalseStrokes() {
        val an = StreamingAnalyzer(sr)
        pushInBlocks(an, noiseAtRms(26.0, 0.0347, 7, 0.9f))
        assertEquals("idle room must stay silent", 0, an.onsetTimes.size)
    }

    @Test
    fun risingRoomLevelDoesNotFloodDetections() {
        // The device: floor learned in the first 1.2 s, then the room gets louder.
        val an = StreamingAnalyzer(sr)
        pushInBlocks(an, noiseAtRms(1.5, 0.0104, 11, 0.9f))
        pushInBlocks(an, noiseAtRms(25.0, 0.0347, 12, 0.9f))

        // The first ~0.6 s after the step is the background tracker catching up;
        // what matters is that an idle room settles to silence instead of firing
        // ~5.5 times a second forever.
        val steady = an.onsetTimes.count { it > 1.5 + 1.0 }
        assertTrue("steady-state false strokes=$steady, all=${an.onsetTimes.size}", steady == 0)
    }

    @Test
    fun heavyRumbleRoomDoesNotFloodDetections() {
        val an = StreamingAnalyzer(sr)
        pushInBlocks(an, noiseAtRms(1.5, 0.0104, 31, 0.97f))
        pushInBlocks(an, noiseAtRms(25.0, 0.0347, 32, 0.97f))
        val steady = an.onsetTimes.count { it > 1.5 + 1.0 }
        assertTrue("steady-state false strokes=$steady, all=${an.onsetTimes.size}", steady == 0)
    }

    @Test
    fun rollIsStillDetectedInANoisyRoom() {
        val an = StreamingAnalyzer(sr)
        pushInBlocks(an, noiseAtRms(1.5, 0.0104, 21, 0.9f))
        val sig = addRoll(noiseAtRms(10.0, 0.0347, 22, 0.9f), 0.11, 0.35f)
        pushInBlocks(an, sig)
        // 0.05 s to 9.9 s at 0.11 s spacing -> 90 strokes
        assertTrue("strokes=${an.onsetTimes.size}", an.onsetTimes.size >= 85)
    }

    @Test
    fun gateIsNotClosedBySustainedPlay() {
        val an = StreamingAnalyzer(sr)
        pushInBlocks(an, noiseAtRms(1.5, 0.0104, 41, 0.9f))
        val sig = addRoll(noiseAtRms(12.0, 0.0347, 42, 0.9f), 0.11, 0.35f)
        pushInBlocks(an, sig)
        // second half of the roll must detect just as well as the first half
        val first = an.onsetTimes.count { it in 1.5..7.5 }
        val second = an.onsetTimes.count { it > 7.5 }
        assertTrue("first=$first second=$second", second >= first - 4)
    }
}
