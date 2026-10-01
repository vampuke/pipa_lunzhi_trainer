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
        assertEquals("idle room must stay silent", 0, an.onsetTimes.size)    }

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

    // ---------------- 开头的房间测量窗 ----------------
    //
    // 上面所有用例都先喂 1.5s 安静房间，所以一直没暴露这条路径：**房间测量窗里
    // 混进演奏声**。用户按下「开始」后立刻弹，均值型底噪会把演奏电平当成房间，
    // 门限被抬到 3x 演奏电平后永久关闭。实测（修复前，同一份合成轮指只改起始
    // 时间）：长余韵轮指从 0.3s 起弹检出 0/92，挪到 2.0s 后 91/92。

    /** 与上面 addRoll 不同：余韵很长（tau=150ms），相邻击会重叠。 */
    private fun addLongRingRoll(base: FloatArray, fromSec: Double, intervalSec: Double): Pair<FloatArray, Int> {
        var t = fromSec
        var n = 0
        val tau = 0.150
        while (t < base.size.toDouble() / sr - 0.3) {
            val start = (t * sr).toInt()
            val len = Math.min((1.0 * sr).toInt(), base.size - start)
            for (k in 0 until len) {
                val tt = k.toDouble() / sr
                var v = 0.0
                for (h in 1..6) v += (1.0 / h) * sin(2 * PI * 220.0 * h * tt)
                val atk = if (tt < 0.002) tt / 0.002 else 1.0
                base[start + k] += (v * atk * exp(-tt / tau) * 0.18).toFloat()
            }
            t += intervalSec
            n++
        }
        return base to n
    }

    @Test
    fun rollStartingInsideTheCalibrationWindowIsStillDetected() {
        // 0.3s 房间 + 立刻开始的长余韵轮指：这是真实使用最典型的时序
        val an = StreamingAnalyzer(sr)
        val sig = noiseAtRms(12.0, 0.004, 51, 0.9f)
        val (withRoll, truth) = addLongRingRoll(sig, 0.3, 0.125)
        pushInBlocks(an, withRoll)
        assertTrue("truth=$truth detected=${an.onsetTimes.size}",
            an.onsetTimes.size >= (truth * 0.85).toInt())
    }

    @Test
    fun longRingRollAfterTheCalibrationWindowIsDetected() {
        val an = StreamingAnalyzer(sr)
        val sig = noiseAtRms(12.0, 0.004, 52, 0.9f)
        val (withRoll, truth) = addLongRingRoll(sig, 2.0, 0.100)
        pushInBlocks(an, withRoll)
        assertTrue("truth=$truth detected=${an.onsetTimes.size}",
            an.onsetTimes.size >= (truth * 0.9).toInt())
    }

    @Test
    fun fastRollIsNotTruncatedByTheMinimumOnsetGap() {
        // 25ms 门限：20 击/秒(50ms) 必须全数检出。原来的 40ms 门正好卡在物理上限上，
        // 会吞掉快轮起音并截断 IOI 分布（同时扭曲速度与均匀度）。
        val an = StreamingAnalyzer(sr)
        val sig = noiseAtRms(12.0, 0.004, 53, 0.9f)
        val (withRoll, truth) = addLongRingRoll(sig, 1.5, 0.050)
        pushInBlocks(an, withRoll)
        assertTrue("20/s: truth=$truth detected=${an.onsetTimes.size}",
            an.onsetTimes.size >= (truth * 0.85).toInt())
    }
}
