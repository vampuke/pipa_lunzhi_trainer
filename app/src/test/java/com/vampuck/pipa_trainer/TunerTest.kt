package com.vampuck.pipa_trainer

import com.vampuck.pipa_trainer.dsp.Tuner
import com.vampuck.pipa_trainer.dsp.Tuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 调音器要求「准」：音分误差超过几音分就没用了。
 * 这里用合成音验证全音域的精度、泛音强基频弱的情况、以及噪声不得给出读数。
 */
class TunerTest {

    private val sr = 44100

    /** 纯音。 */
    private fun tone(seconds: Double, hz: Double, amp: Double = 0.3, seed: Long = 1): FloatArray {
        val n = (seconds * sr).toInt()
        val rnd = java.util.Random(seed)
        return FloatArray(n) { i ->
            (sin(2 * PI * hz * i / sr) * amp + (rnd.nextDouble() - 0.5) * 0.001).toFloat()
        }
    }

    /** 拨弦：泛音按 1/n 衰减，可选削弱基频（琵琶低音弦基频往往很弱）。 */
    private fun pluck(seconds: Double, hz: Double, amp: Double = 0.4, fund: Double = 1.0): FloatArray {
        val n = (seconds * sr).toInt()
        val partials = doubleArrayOf(fund, 0.55, 0.35, 0.20, 0.12, 0.07)
        return FloatArray(n) { i ->
            val t = i.toDouble() / sr
            var v = 0.0
            for ((k, g) in partials.withIndex()) {
                val f = hz * (k + 1)
                if (f < sr / 2) v += g * sin(2 * PI * f * t) * exp(-t / (0.5 / (k + 1)))
            }
            (v * amp * 0.5).toFloat()
        }
    }

    private fun roomNoise(seconds: Double, rms: Double, seed: Long, lp: Float = 0.9f): FloatArray {
        val rnd = java.util.Random(seed)
        val n = (seconds * sr).toInt()
        val a = FloatArray(n)
        var z = 0f
        for (i in 0 until n) {
            val w = (rnd.nextFloat() - 0.5f) * 2f
            z = lp * z + (1f - lp) * w
            a[i] = z
        }
        var acc = 0.0
        for (v in a) acc += v.toDouble() * v
        val sc = (rms / sqrt(acc / a.size)).toFloat()
        for (i in a.indices) a[i] *= sc
        return a
    }

    /** 像 App 一样按 100ms 分块喂进去，返回最后几次里的一次读数。 */
    private fun feed(t: Tuner, sig: FloatArray): Tuner.Reading? {
        val block = sr / 10
        var off = 0
        var last: Tuner.Reading? = null
        while (off < sig.size) {
            val n = Math.min(block, sig.size - off)
            last = t.push(sig.copyOfRange(off, off + n), n) ?: last
            off += n
        }
        return last
    }

    private fun centsOff(detected: Double, truth: Double) = abs(1200.0 * kotlin.math.ln(detected / truth) / kotlin.math.ln(2.0))

    // ---------------- 精度 ----------------

    @Test
    fun detectsTheFourOpenStringsWithinAFewCents() {
        for (s in Tuning.PIPA_STANDARD) {
            val t = Tuner(sr)
            val r = feed(t, tone(1.0, s.hz))
            assertNotNull("no reading for ${s.label} ${s.note}", r)
            val off = centsOff(r!!.hz, s.hz)
            assertTrue("${s.label} ${s.note}: ${r.hz} Hz, off by ${"%.1f".format(off)} cents", off < 5.0)
            assertEquals(s.note, r.note.label)
        }
    }

    @Test
    fun staysAccurateAcrossThePlayableRange() {
        var worst = 0.0
        var worstHz = 0.0
        var hz = 65.0
        while (hz < 1200.0) {
            val r = feed(Tuner(sr), tone(1.0, hz))
            assertNotNull("no reading at $hz Hz", r)
            val off = centsOff(r!!.hz, hz)
            if (off > worst) { worst = off; worstHz = hz }
            hz *= 1.06
        }
        assertTrue("worst error ${"%.1f".format(worst)} cents at $worstHz Hz", worst < 8.0)
    }

    @Test
    fun findsTheFundamentalEvenWhenItIsWeak() {
        // 低音弦基频弱、泛音强：只看 FFT 峰值容易跑到泛音上（高八度错）
        val r = feed(Tuner(sr), pluck(1.0, 110.0, fund = 0.12))
        assertNotNull(r)
        val off = centsOff(r!!.hz, 110.0)
        assertTrue("detected ${r.hz} Hz (${"%.1f".format(off)} cents off)", off < 10.0)
    }

    /** 指定各次谐波幅度的合成音（index 0 = 基频）。 */
    private fun harmonics(seconds: Double, hz: Double, gains: DoubleArray): FloatArray {
        val n = (seconds * sr).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / sr
            var v = 0.0
            for (k in gains.indices) {
                val f = hz * (k + 1)
                if (f < sr / 2) v += gains[k] * sin(2 * PI * f * t)
            }
            (v * 0.25).toFloat()
        }
    }

    /**
     * 八度错的两个方向都要挡住：
     *  - 2 次谐波主导时，归一化差值函数在 T/2 有个浅谷，取「第一个低于阈值的谷」
     *    会读成 220Hz（+1206 音分）；
     *  - 反过来取「最深的谷」会掉八度，因为 A3 的 T 与 2T 都是完美周期，
     *    谁更深只由数值噪声决定（实测 A3/D3/E3 会整体低一个八度）。
     * 现在的判据是「谷值与最深谷一样深的最短滞后」，两个方向都要能过。
     */
    @Test
    fun doesNotPickTheSecondHarmonicAsTheFundamental() {
        val r = feed(Tuner(sr), harmonics(1.0, 110.0, doubleArrayOf(0.1, 1.0, 0.3, 0.2, 0.1, 0.05)))
        assertNotNull(r)
        val off = centsOff(r!!.hz, 110.0)
        assertTrue("detected ${r.hz} Hz (${"%.1f".format(off)} cents off) — an octave up", off < 20.0)
    }

    @Test
    fun doesNotDropAnOctaveWhenTheFundamentalIsWeak() {
        // A3/D3/E3 基频 -18dB：曾经被读成 A2/D2/E2
        for (hz in doubleArrayOf(220.0, 146.83, 164.81)) {
            val r = feed(Tuner(sr), harmonics(1.0, hz, doubleArrayOf(0.12, 1.0, 1.0, 1.0)))
            assertNotNull(r)
            val off = centsOff(r!!.hz, hz)
            assertTrue("$hz Hz detected as ${r.hz} Hz (${"%.1f".format(off)} cents)", off < 20.0)
        }
    }

    @Test
    fun handlesAFundamentalThatIsCompletelyAbsent() {
        // 2..6 次谐波都在、基频为 0：周期仍是 1/f0，应当读出 f0
        val r = feed(Tuner(sr), harmonics(1.0, 110.0, doubleArrayOf(0.0, 1.0, 1.0, 1.0, 1.0, 1.0)))
        assertNotNull(r)
        assertTrue("detected ${r!!.hz} Hz", centsOff(r.hz, 110.0) < 20.0)
    }

    @Test
    fun needleIsStableOnASteadyToneWithNoise() {
        // 指针抖动：稳态音上的读数标准差应在 1 音分以内（512 hop 时窗重叠 75%）
        val rnd = java.util.Random(3)
        val n = (3.0 * sr).toInt()
        val a = FloatArray(n) { i ->
            val t = i.toDouble() / sr
            (sin(2 * PI * 220.0 * t) * 0.25 + (rnd.nextDouble() - 0.5) * 0.02).toFloat()
        }
        val t = Tuner(sr)
        val vals = ArrayList<Double>()
        var off = 0
        while (off + 512 <= a.size) {
            val r = t.push(a.copyOfRange(off, off + 512), 512)
            if (r != null) vals.add(r.hz)
            off += 512
        }
        val tail = vals.drop(vals.size / 3)
        assertTrue("too few readings: ${vals.size}", tail.size > 50)
        val mean = tail.average()
        val sd = sqrt(tail.map { (it - mean) * (it - mean) }.average())
        val sdCents = abs(1200.0 * kotlin.math.ln((mean + sd) / mean) / kotlin.math.ln(2.0))
        assertTrue("needle sd = ${"%.2f".format(sdCents)} cents", sdCents < 1.0)
    }

    @Test
    fun pluckReadsAsTheRightString() {
        for (s in Tuning.PIPA_STANDARD) {
            val r = feed(Tuner(sr), pluck(1.0, s.hz))
            assertNotNull(r)
            assertEquals("${s.label}", s.number, r!!.string.string.number)
        }
    }

    // ---------------- 门限 ----------------

    @Test
    fun silenceGivesNoReading() {
        assertNull(feed(Tuner(sr), FloatArray(sr)))
    }

    @Test
    fun roomNoiseGivesNoReading() {
        // 设备实测的室内噪声量级：RMS 0.035
        assertNull(feed(Tuner(sr), roomNoise(2.0, 0.035, 7)))
        assertNull(feed(Tuner(sr), roomNoise(2.0, 0.06, 8, lp = 0.0f)))
    }

    @Test
    fun quietRoomNoiseAlsoGivesNoReading() {
        assertNull(feed(Tuner(sr), roomNoise(2.0, 0.01, 9)))
    }

    // ---------------- 音分与弦的换算 ----------------

    @Test
    fun centsAreReportedWithTheRightSign() {
        val sharp = feed(Tuner(sr), tone(1.0, Tuning.hzOf(Tuning.midiOf(440.0) + 0.20)))
        assertNotNull(sharp)
        assertEquals("A4", sharp!!.note.label)
        assertTrue("cents=${sharp.note.cents}", sharp.note.cents > 12 && sharp.note.cents < 28)

        val flat = feed(Tuner(sr), tone(1.0, Tuning.hzOf(Tuning.midiOf(440.0) - 0.20)))
        assertNotNull(flat)
        assertTrue("cents=${flat!!.note.cents}", flat.note.cents < -12 && flat.note.cents > -28)
    }

    @Test
    fun nearestStringUsesCentsNotHertz() {
        // 218Hz 距一弦 220 只有 16 音分，距二弦 164.8 有 480 音分
        val m = Tuning.nearestString(218.0)
        assertEquals(1, m.string.number)
        assertTrue("cents=${m.cents}", m.cents < -10 && m.cents > -22)
        assertEquals("偏低", m.direction)
    }

    @Test
    fun stringTableMatchesStandardDTuning() {
        assertEquals(4, Tuning.PIPA_STANDARD.size)
        assertEquals(220.00, Tuning.PIPA_STANDARD[0].hz, 0.01)   // 一弦 A3
        assertEquals(164.81, Tuning.PIPA_STANDARD[1].hz, 0.01)   // 二弦 E3
        assertEquals(146.83, Tuning.PIPA_STANDARD[2].hz, 0.01)   // 三弦 D3
        assertEquals(110.00, Tuning.PIPA_STANDARD[3].hz, 0.01)   // 四弦 A2
    }

    @Test
    fun detunedStringStillMapsToTheRightPeg() {
        // 二弦低了近 40 音分（约 161Hz），仍应指向二弦
        val m = Tuning.nearestString(161.0)
        assertEquals(2, m.string.number)
        assertTrue("cents=${m.cents}", m.cents < -30)
    }
}
