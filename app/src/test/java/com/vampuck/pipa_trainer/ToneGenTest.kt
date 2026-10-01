package com.vampuck.pipa_trainer

import com.vampuck.pipa_trainer.audio.ToneGen
import com.vampuck.pipa_trainer.dsp.Tuner
import com.vampuck.pipa_trainer.dsp.Tuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 参考音的频段要落在手机扬声器发得出的范围里。
 *
 * 实测踩到的坑：四弦 A2 的参考音从 110Hz 基频开始叠泛音，而手机喇叭在 110Hz
 * 基本没输出，绝大部分能量压在一个发不出来的频率上，结果麦克风收到的电平只
 * 勉强过门限、调音器自己都判不出音高（设备上显示「听不清音高」）。
 */
class ToneGenTest {

    private val sr = 44100

    /** 用相关法测某个频率在波形里的能量（相当于单点 Goertzel）。 */
    private fun energyAt(pcm: ShortArray, hz: Double): Double {
        var re = 0.0
        var im = 0.0
        val n = pcm.size
        for (i in 0 until n) {
            val x = pcm[i] / 32768.0
            val a = 2 * PI * hz * i / sr
            re += x * sin(a)
            im += x * kotlin.math.cos(a)
        }
        return kotlin.math.sqrt(re * re + im * im) / n
    }

    @Test
    fun lowStringSkipsTheInaudibleFundamental() {
        assertEquals(2, ToneGen.firstPartial(110.00))   // 四弦 A2 -> 从 220Hz 起
        assertEquals(2, ToneGen.firstPartial(146.83))   // 三弦 D3 -> 293.66
        assertEquals(2, ToneGen.firstPartial(164.81))   // 二弦 E3 -> 165 也在喇叭盲区，从 330 起
        assertEquals(1, ToneGen.firstPartial(220.00))   // 一弦 A3 -> 220 已可发声
    }

    @Test
    fun generatedLowTonePutsItsEnergyAboveTheSpeakerFloor() {
        val pcm = ToneGen.pcm(110.0, 1.0, sr)
        val at110 = energyAt(pcm, 110.0)
        val at220 = energyAt(pcm, 220.0)
        val at330 = energyAt(pcm, 330.0)
        assertTrue("110Hz should be absent, got $at110 vs 220Hz $at220", at220 > at110 * 20)
        assertTrue("harmonics should be present", at220 > 0.05 && at330 > 0.02)
    }

    @Test
    fun highStringKeepsItsFundamental() {
        val pcm = ToneGen.pcm(220.0, 1.0, sr)
        assertTrue("A3 fundamental should dominate", energyAt(pcm, 220.0) > energyAt(pcm, 440.0))
        assertTrue(energyAt(pcm, 220.0) > 0.1)
    }

    @Test
    fun referenceToneIsLoudEnoughToBeHeard() {
        // 归一化后峰值应接近满量程，否则用户听不清
        for (s in Tuning.PIPA_STANDARD) {
            val pcm = ToneGen.pcm(s.hz, 1.0, sr)
            val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) } / 32768.0
            assertTrue("${s.label} peak=$peak", peak > 0.6)
        }
    }

    @Test
    fun referenceToneStillReadsAsTheIntendedPitch() {
        // 跳过基频后波形周期不变，调音器听自己的参考音仍应得到正确的音
        for (s in Tuning.PIPA_STANDARD) {
            val pcm = ToneGen.pcm(s.hz, 1.0, sr)
            val f = FloatArray(pcm.size) { pcm[it] / 32768f }
            val t = Tuner(sr)
            val block = sr / 10
            var off = 0
            var last: Tuner.Reading? = null
            while (off < f.size) {
                val n = Math.min(block, f.size - off)
                last = t.push(f.copyOfRange(off, off + n), n) ?: last
                off += n
            }
            assertTrue("no reading for ${s.label}", last != null)
            val cents = kotlin.math.abs(Tuning.centsBetween(last!!.hz, s.hz))
            assertTrue("${s.label}: ${last.hz} Hz (${"%.1f".format(cents)} cents)", cents < 10.0)
        }
    }
}
