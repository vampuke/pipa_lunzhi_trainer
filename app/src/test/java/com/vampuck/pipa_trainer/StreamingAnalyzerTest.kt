package com.vampuck.pipa_trainer

import com.vampuck.pipa_trainer.dsp.StreamingAnalyzer
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Locks in the rewritten live noise gate:
 *  - a roll played after a quiet warm-up must be detected, and
 *  - the noise floor must NOT creep up to the playing level (the old bug that
 *    silently closed the gate after ~0.1s of playing).
 */
class StreamingAnalyzerTest {

    private val sr = 22050

    private fun roomTone(seconds: Double, amp: Float, seed: Long): FloatArray {
        val rnd = java.util.Random(seed)
        return FloatArray((seconds * sr).toInt()) { ((rnd.nextFloat() - 0.5f) * 2f) * amp }
    }

    /** A steady roll: decaying tone bursts every [intervalSec]. */
    private fun roll(seconds: Double, intervalSec: Double, amp: Float = 0.25f): FloatArray {
        val out = FloatArray((seconds * sr).toInt())
        var t = 0.05
        val burst = (0.06 * sr).toInt()
        while (t < seconds - 0.1) {
            val start = (t * sr).toInt()
            for (k in 0 until burst) {
                val idx = start + k
                if (idx >= out.size) break
                val env = exp(-k / (0.012 * sr))
                out[idx] += (sin(2 * PI * 600.0 * k / sr) * env).toFloat() * amp
            }
            t += intervalSec
        }
        return out
    }

    @Test
    fun detectsRollAfterQuietWarmup() {
        val an = StreamingAnalyzer(sr)
        val quiet = roomTone(1.5, 0.002f, 1)
        an.push(quiet, quiet.size)
        val r = roll(4.0, 0.11)
        an.push(r, r.size)
        assertTrue("strokes=${an.onsetTimes.size}", an.onsetTimes.size >= 25)
    }

    @Test
    fun detectsQuietRollToo() {
        val an = StreamingAnalyzer(sr)
        val quiet = roomTone(1.5, 0.001f, 2)
        an.push(quiet, quiet.size)
        // a much quieter roll (-30 dB vs the loud case)
        val r = roll(4.0, 0.11, amp = 0.02f)
        an.push(r, r.size)
        assertTrue("strokes=${an.onsetTimes.size}", an.onsetTimes.size >= 20)
    }

    @Test
    fun evenRollGivesLowJitter() {
        val an = StreamingAnalyzer(sr)
        an.push(roomTone(1.5, 0.001f, 5), (1.5 * sr).toInt())
        val r = roll(6.0, 0.11)
        an.push(r, r.size)
        // tail so the look-ahead has frames to confirm the last peaks
        val live = an.push(FloatArray(sr / 2), sr / 2)
        assertTrue("strokes=${live.totalStrokes}", live.totalStrokes >= 40)
        assertTrue("jitter=${live.jitterPct}", live.jitterPct < 8.0)
    }

    @Test
    fun floorDoesNotCreepUpDuringSustainedPlay() {
        val an = StreamingAnalyzer(sr)
        val quiet = roomTone(1.5, 0.001f, 3)
        an.push(quiet, quiet.size)
        val before = an.noiseFloor
        val r = roll(8.0, 0.11, amp = 0.3f)
        an.push(r, r.size)
        // floor must stay near the room level, never near the signal level
        assertTrue("floor $before -> ${an.noiseFloor}", an.noiseFloor <= before * 3.0 + 0.002)
    }
}
