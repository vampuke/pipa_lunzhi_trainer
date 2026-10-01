package com.vampuck.pipa_trainer.audio

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * 参考音的波形生成（纯函数，方便离线验证）。
 *
 * 关键点：手机小喇叭在 ~200Hz 以下几乎不出声。如果无条件从基频开始叠泛音，
 * 低音弦（尤其四弦 A2=110Hz）的绝大部分能量会压在一个**发不出来**的基频上，
 * 结果参考音又闷又轻——实测四弦参考音在麦克风里只勉强超过门限，连调音器自己
 * 都判不出音高。
 *
 * 所以从「扬声器能发的第一个泛音」开始叠：110Hz 就从 2 次泛音(220Hz)起。
 * 人耳靠缺失基频效应听到的仍然是 110Hz，而能量全部落在能发声的频段。
 */
object ToneGen {

    /** 低于这个频率的泛音不参与（发了也听不到，只会浪费动态余量）。 */
    const val AUDIBLE_FLOOR_HZ = 180.0

    private val PARTIAL_GAINS = doubleArrayOf(1.0, 0.6, 0.35, 0.22, 0.12)

    /** 实际使用的起始泛音序号（1 = 基频）。 */
    fun firstPartial(hz: Double): Int {
        if (hz <= 0.0) return 1
        var h = 1
        while (hz * h < AUDIBLE_FLOOR_HZ && h < PARTIAL_GAINS.size) h++
        return h
    }

    fun pcm(hz: Double, seconds: Double, sr: Int): ShortArray {
        val n = (sr * seconds).toInt().coerceAtLeast(1)
        val start = firstPartial(hz)
        val gains = PARTIAL_GAINS.copyOfRange(start - 1, PARTIAL_GAINS.size)
        val norm = 1.0 / gains.sum()
        val atk = (0.02 * sr).toInt().coerceAtLeast(1)
        val rel = (0.25 * sr).toInt().coerceAtLeast(1)
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            var v = 0.0
            for (k in gains.indices) {
                val f = hz * (start + k)
                if (f < sr / 2) v += gains[k] * sin(2 * PI * f * t)
            }
            var env = 1.0
            if (i < atk) env = i.toDouble() / atk
            val left = n - i
            if (left < rel) env = min(env, left.toDouble() / rel)
            val s = v * norm * 0.9 * env
            out[i] = (s * 32767.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
        return out
    }
}
