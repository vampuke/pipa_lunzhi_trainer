package com.vampuck.pipa_trainer

import com.vampuck.pipa_trainer.dsp.LunzhiAnalyzer
import com.vampuck.pipa_trainer.dsp.StreamingAnalyzer
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The level gate cannot tell a loud room from an instrument, so onsets also have
 * to *sound* like a plucked string: [com.vampuck.pipa_trainer.dsp.Timbre] scores
 * how much of the 90-5000 Hz energy sits on a harmonic series with a fundamental
 * in the pipa's range.
 *
 * Measured at detector-selected onsets:
 *   pipa roll (loud / soft)  >= 0.60
 *   idle noisy room          <= 0.61, median 0.54
 *   metronome click only     <= 0.70, median 0.52
 * [StreamingAnalyzer.minTone] and [LunzhiAnalyzer] use 0.58.
 *
 * The metronome click is 3200 + 6400 Hz with nothing below ~2.5 kHz, so the
 * pitch-range search finds nothing and the score collapses to ~0.5. That is a
 * second line of defence behind the timestamp mask and it survives the mask
 * being a few milliseconds off.
 */
class TimbreClassificationTest {

    private val sr = 44100

    // ---------- signal generators ----------

    private fun noise(seconds: Double, rms: Double, seed: Long, lp: Float): FloatArray {
        val rnd = java.util.Random(seed)
        val n = (seconds * sr).toInt()
        val a = FloatArray(n)
        var z = 0f
        for (i in 0 until n) {
            val w = (rnd.nextFloat() - 0.5f) * 2f
            z = if (lp > 0f) lp * z + (1f - lp) * w else w
            a[i] = z
        }
        var acc = 0.0
        for (v in a) acc += v.toDouble() * v
        val sc = (rms / sqrt(acc / a.size)).toFloat()
        for (i in a.indices) a[i] *= sc
        return a
    }

    /** Intermittent room: quiet base plus random louder bursts. */
    private fun intermittentRoom(seconds: Double, base: Double, burst: Double, seed: Long): FloatArray {
        val a = noise(seconds, base, seed, 0.9f)
        val b = noise(seconds, burst, seed + 991, 0.9f)
        val rnd = java.util.Random(seed + 7)
        var t = 0.4
        while (t < seconds - 1.0) {
            val len = 0.2 + rnd.nextDouble() * 0.8
            val s = (t * sr).toInt()
            val e = Math.min(a.size, ((t + len) * sr).toInt())
            for (i in s until e) a[i] += b[i]
            t += len + 0.3 + rnd.nextDouble() * 1.2
        }
        return a
    }

    /** Harmonic pluck, partials decaying at different rates like a real string. */
    private fun addPluck(a: FloatArray, t0: Double, gain: Float, f0: Double) {
        val start = (t0 * sr).toInt()
        if (start >= a.size) return
        val m = Math.min((0.6 * sr).toInt(), a.size - start)
        for (k in 0 until m) {
            val t = k.toDouble() / sr
            var v = 0.0
            v += 1.00 * sin(2 * PI * f0 * t) * exp(-t / 0.150)
            v += 0.55 * sin(2 * PI * f0 * 2.01 * t) * exp(-t / 0.110)
            v += 0.35 * sin(2 * PI * f0 * 3.02 * t) * exp(-t / 0.085)
            v += 0.20 * sin(2 * PI * f0 * 4.05 * t) * exp(-t / 0.065)
            v += 0.12 * sin(2 * PI * f0 * 5.10 * t) * exp(-t / 0.050)
            a[start + k] += (v * gain * 0.22).toFloat()
        }
    }

    private fun rollInto(a: FloatArray, from: Double, to: Double, interval: Double, gain: Float, seed: Long) {
        val rnd = java.util.Random(seed)
        var t = from
        while (t < to) {
            val f0 = 220.0 * Math.pow(4.0, rnd.nextDouble())   // 220-880 Hz
            addPluck(a, t, gain * (0.85f + rnd.nextFloat() * 0.3f), f0)
            t += interval
        }
    }

    private fun rollCount(from: Double, to: Double, interval: Double): Int {
        var n = 0; var t = from
        while (t < to) { n++; t += interval }
        return n
    }

    /** Byte-for-byte the click [com.vampuck.pipa_trainer.audio.Metronome] emits. */
    private fun metronomeClicks(a: FloatArray, from: Double, to: Double, period: Double, gain: Float) {
        val n = (sr * 0.014).toInt()
        var t = from
        while (t < to) {
            val start = (t * sr).toInt()
            for (i in 0 until n) {
                val idx = start + i
                if (idx >= a.size) break
                val tt = i.toDouble() / sr
                val env = exp(-tt * 220.0)
                val s = (sin(2 * PI * 3200.0 * tt) * 0.75 + sin(2 * PI * 6400.0 * tt) * 0.25) * env
                a[idx] += (s * 0.73 * gain).toFloat()
            }
            t += period
        }
    }

    private fun pushInBlocks(an: StreamingAnalyzer, s: FloatArray) {
        val block = sr / 10
        var off = 0
        while (off < s.size) {
            val n = Math.min(block, s.size - off)
            an.push(s.copyOfRange(off, off + n), n)
            off += n
        }
    }

    // ---------- file mode ----------

    @Test
    fun fileModeTimbreRemovesMostRoomNoiseOnsets() {
        val room = intermittentRoom(20.0, 0.012, 0.035, 5)
        val fn = LunzhiAnalyzer.onsetFunction(room, sr)
        val without = LunzhiAnalyzer.pickPeaks(fn.flux, null, fn.fps, 0.0).size
        val with = LunzhiAnalyzer.pickPeaks(fn.flux, fn.tone, fn.fps, LunzhiAnalyzer.TONE_MIN).size
        // The timbre test must carry its weight on top of the flux/prominence
        // detector, and the residue must stay small for a 20 s file.
        assertTrue("timbre removed only $without -> $with", with <= without / 2)
        assertTrue("noise still produced $with onsets (was $without)", with <= 12)
    }

    @Test
    fun fileModeRejectsMetronomeClicks() {
        val a = noise(20.0, 0.006, 4, 0.9f)
        metronomeClicks(a, 1.0, 19.5, 0.75, 0.5f)          // 80 bpm, 25 clicks
        val res = LunzhiAnalyzer.analyze(a, sr)
        assertTrue("clicks produced ${res.onsetTimes.size} onsets", res.onsetTimes.size <= 4)
    }

    @Test
    fun fileModeStillFindsRollInANoisyRoom() {
        val a = intermittentRoom(16.0, 0.010, 0.030, 9)
        val expected = rollCount(2.0, 15.5, 0.125)
        rollInto(a, 2.0, 15.5, 0.125, 0.9f, 21)
        val res = LunzhiAnalyzer.analyze(a, sr)
        assertTrue("strokes=${res.onsetTimes.size} expected~$expected",
            res.onsetTimes.size >= (expected * 0.9).toInt())
    }

    // ---------- live mode ----------

    @Test
    fun liveModeRejectsIdleRoomAtTheDefaultPreset() {
        val an = StreamingAnalyzer(sr)
        an.setSensitivity(1)
        pushInBlocks(an, intermittentRoom(30.0, 0.012, 0.035, 5))
        assertTrue("idle room produced ${an.onsetTimes.size} onsets in 30s", an.onsetTimes.size <= 25)
    }

    @Test
    fun liveModeSensitivePresetStillSilentInAQuietRoom() {
        // 灵敏 is for "it won't detect me" in a quiet room, not for a noisy one.
        val an = StreamingAnalyzer(sr)
        an.setSensitivity(2)
        pushInBlocks(an, noise(30.0, 0.006, 17, 0.9f))
        assertTrue("quiet room produced ${an.onsetTimes.size} onsets", an.onsetTimes.size <= 5)
    }

    @Test
    fun liveModeRejectsMetronomeClicksWithoutTheTimestampMask() {
        // No addMetronomeClick() calls here on purpose: this measures the timbre
        // test alone, i.e. what happens when the mask is a few ms off.
        val an = StreamingAnalyzer(sr)
        an.setSensitivity(1)
        val a = noise(24.0, 0.006, 6, 0.9f)
        metronomeClicks(a, 1.0, 23.5, 0.75, 0.5f)           // 30 clicks
        pushInBlocks(an, a)
        assertTrue("clicks produced ${an.onsetTimes.size} onsets", an.onsetTimes.size <= 8)
    }

    @Test
    fun liveModeKeepsEveryStrokeWhenTheMetronomeIsOn() {
        val expected = rollCount(2.0, 15.5, 0.125)
        val an = StreamingAnalyzer(sr)
        an.setSensitivity(1)
        val a = noise(16.0, 0.006, 8, 0.9f)
        rollInto(a, 2.0, 15.5, 0.125, 0.9f, 33)
        // real click timestamps, as LiveActivity would feed them
        an.setMetronomeBeat(0.75)
        var click = 1.0
        while (click < 15.8) { an.addMetronomeClick(click); click += 0.75 }
        metronomeClicks(a, 1.0, 15.5, 0.75, 0.5f)
        pushInBlocks(an, a)
        assertTrue("strokes=${an.onsetTimes.size} expected~$expected, with 20 clicks mixed in",
            an.onsetTimes.size in (expected * 9 / 10)..(expected * 12 / 10))
    }

    @Test
    fun timbreDoesNotRejectRealStrokes() {
        // Every detected onset of a clean roll must sit above the threshold,
        // i.e. the filter has real margin and is not shaving strokes off.
        val an = StreamingAnalyzer(sr)
        val a = noise(16.0, 0.004, 30, 0.9f)
        rollInto(a, 2.0, 15.5, 0.125, 0.9f, 31)
        pushInBlocks(an, a)
        val expected = rollCount(2.0, 15.5, 0.125)
        assertTrue("strokes=${an.onsetTimes.size} expected~$expected",
            an.onsetTimes.size >= (expected * 9 / 10))
        val worst = an.onsetTone.minOrNull() ?: 0.0
        assertTrue("weakest surviving onset scored $worst", worst >= StreamingAnalyzer(sr).minTone)
    }
}
