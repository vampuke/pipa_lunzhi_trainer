package com.vampuck.pipa_trainer.dsp

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 音名 / 音分换算，以及琵琶的定弦表。
 *
 * 定弦用最常见的**标准 D 调（正调）**：从一弦（最细）到四弦（最粗）为
 * A3 – E3 – D3 – A2，即 A-d-e-a。别的调（C 调、G 调等）会把某几根弦整体
 * 移高/移低，所以调音器同时给出**十二平均律音名 + 音分**，不依赖这张表也能用。
 */
object Tuning {

    /** 标准 D 调定弦，一弦在最前（最细、最高）。 */
    val PIPA_STANDARD: List<InstrumentString> = listOf(
        InstrumentString(1, "一弦", "A3", 220.00),
        InstrumentString(2, "二弦", "E3", 164.81),
        InstrumentString(3, "三弦", "D3", 146.83),
        InstrumentString(4, "四弦", "A2", 110.00)
    )

    data class InstrumentString(
        val number: Int,
        val label: String,     // 一弦 / 二弦 / 三弦 / 四弦
        val note: String,      // A3 / E3 / D3 / A2
        val hz: Double
    )

    data class NoteReading(val name: String, val octave: Int, val cents: Double, val midi: Int) {
        val label: String get() = "$name$octave"
    }

    data class StringMatch(val string: InstrumentString, val cents: Double) {
        val inTune: Boolean get() = abs(cents) <= IN_TUNE_CENTS
        val direction: String get() = when {
            cents > 0 -> "偏高"
            cents < 0 -> "偏低"
            else -> "准"
        }
    }

    /** 偏差在这个范围内算「准」。 */
    const val IN_TUNE_CENTS = 5.0

    private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** MIDI 音高号（A4 = 69 = 440Hz），取对数后是连续值，小数部分即偏差。 */
    fun midiOf(hz: Double): Double = 69.0 + 12.0 * (ln(hz / 440.0) / ln(2.0))

    fun hzOf(midi: Double): Double = 440.0 * 2.0.pow((midi - 69.0) / 12.0)

    fun nearestNote(hz: Double): NoteReading {
        val m = midiOf(hz)
        val nearest = m.roundToInt()
        val cents = (m - nearest) * 100.0
        val name = NAMES[((nearest % 12) + 12) % 12]
        val octave = nearest / 12 - 1
        return NoteReading(name, octave, cents, nearest)
    }

    /** 与某个目标频率的音分差（正值=偏高）。 */
    fun centsBetween(hz: Double, target: Double): Double =
        if (hz <= 0.0 || target <= 0.0) 0.0 else 1200.0 * (ln(hz / target) / ln(2.0))

    /** 最接近的琵琶弦（按音分距离，跨弦也不会错）。 */
    fun nearestString(hz: Double): StringMatch {
        var best = PIPA_STANDARD.first()
        var bestAbs = Double.MAX_VALUE
        for (s in PIPA_STANDARD) {
            val c = abs(centsBetween(hz, s.hz))
            if (c < bestAbs) { bestAbs = c; best = s }
        }
        return StringMatch(best, centsBetween(hz, best.hz))
    }
}
