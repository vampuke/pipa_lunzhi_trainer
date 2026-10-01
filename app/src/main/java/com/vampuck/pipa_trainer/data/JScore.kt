package com.vampuck.pipa_trainer.data

/**
 * 简谱（数字谱）数据模型。
 *
 * degree: 1..7 音级，0 = 休止符。
 * octave: 高/低八度点，+1 上加点，-1 下加点（本练习只用到 0/+1）。
 * dur:    时值（以四分音符=1 拍为单位）。0.25=十六分，0.5=八分，1=四分，
 *         2=二分，4=全音符。
 * tremolo: 是否轮指（简谱中记为音符上方的 "彡" / 三斜线）。
 */
data class JNote(
    val degree: Int,
    val octave: Int = 0,
    val dur: Double = 1.0,
    val tremolo: Boolean = false
) {
    val isRest: Boolean get() = degree == 0
}

data class JSectionData(
    val name: String,
    val notes: List<JNote>
) {
    val beats: Double get() = notes.sumOf { it.dur }
}

/**
 * 一首简谱：调号、每小节拍数、分段。
 * [flatNotes] 把所有段落的音符顺序拼平，供滚动谱逐音符渲染。
 */
data class JScore(
    val key: String,
    val beatsPerBar: Int,
    val sections: List<JSectionData>
) {
    val flatNotes: List<JNote> get() = sections.flatMap { it.notes }
    val totalBeats: Double get() = sections.sumOf { it.beats }

    /** 每个段落首音符在 flatNotes 中的下标，用于画分段标签。 */
    fun sectionStartIndices(): List<Int> {
        val out = ArrayList<Int>()
        var acc = 0
        for (s in sections) { out.add(acc); acc += s.notes.size }
        return out
    }
}

/** 预设简谱库。目前含轮指练习曲（整谱）；名曲谱待录入。 */
object Scores {

    private fun n(d: Int, dur: Double, oct: Int = 0, tr: Boolean = true) =
        JNote(d, oct, dur, tr)

    /** 轮指基础练习：慢→中→快三段，1=D，4/4。全程轮指。 */
    val LUNZHI_ETUDE = JScore(
        key = "1=D  4/4",
        beatsPerBar = 4,
        sections = listOf(
            JSectionData(
                "慢速 · 求稳求匀",
                listOf(
                    n(3, 2.0), n(5, 2.0),
                    n(6, 2.0), n(5, 2.0),
                    n(3, 2.0), n(2, 2.0),
                    n(1, 4.0),
                )
            ),
            JSectionData(
                "中速 · 保持颗粒",
                listOf(
                    n(3, 1.0), n(5, 1.0), n(6, 1.0), n(1, 1.0, oct = 1),
                    n(6, 1.0), n(5, 1.0), n(3, 1.0), n(2, 1.0),
                    n(1, 1.0), n(2, 1.0), n(3, 1.0), n(5, 1.0),
                    n(6, 2.0), n(5, 2.0),
                )
            ),
            JSectionData(
                "提速 · 稳住匀速",
                listOf(
                    n(3, 0.5), n(5, 0.5), n(6, 0.5), n(1, 0.5, oct = 1),
                    n(6, 0.5), n(5, 0.5), n(3, 0.5), n(2, 0.5),
                    n(1, 0.5), n(2, 0.5), n(3, 0.5), n(5, 0.5),
                    n(6, 0.5), n(5, 0.5), n(3, 0.5), n(2, 0.5),
                    n(3, 0.5), n(5, 0.5), n(6, 0.5), n(1, 0.5, oct = 1),
                    n(2, 0.5, oct = 1), n(1, 0.5, oct = 1), n(6, 0.5), n(5, 0.5),
                    n(6, 0.5), n(5, 0.5), n(3, 0.5), n(2, 0.5),
                    n(1, 2.0),
                )
            ),
        )
    )
}
