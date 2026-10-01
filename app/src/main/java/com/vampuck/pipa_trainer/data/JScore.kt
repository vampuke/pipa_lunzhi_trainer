package com.vampuck.pipa_trainer.data

/**
 * 简谱（数字谱）数据模型。
 *
 * degree: 1..7 音级，0 = 休止符。
 * octave: 高/低八度点，+1 上加点，-1 下加点（本练习只用到 0/+1）。
 * dur:    时值（以四分音符=1 拍为单位）。0.25=十六分，0.5=八分，1=四分，
 *         2=二分，4=全音符。
 * tremolo: 是否轮指（简谱中记为音符上方的 "彡" / 三斜线）。
 * finger:  琵琶指法标记（显示在音符上方的小字），如 弹/挑/抹/勾/扫/拂/带/泛 等；
 *          空串=无。轮指优先用 tremolo 画斜线，finger 仍可叠加其他记号。
 */
data class JNote(
    val degree: Int,
    val octave: Int = 0,
    val dur: Double = 1.0,
    val tremolo: Boolean = false,
    val finger: String = ""
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

    private fun n(d: Int, dur: Double, oct: Int = 0, tr: Boolean = true, fg: String = "") =
        JNote(d, oct, dur, tr, fg)

    /** 普通音（非轮指），用于民歌旋律。 */
    private fun m(d: Int, dur: Double, oct: Int = 0, fg: String = "") =
        JNote(d, oct, dur, false, fg)

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

    // ===== 公有领域传统民歌（旋律属公有领域，作者已故数百年或为佚名民间小调） =====

    /** 茉莉花（江苏民歌，清乾隆年间《鲜花调》，公有领域）。1=D 4/4。普通弹挑。 */
    val JASMINE = JScore(
        key = "1=D  4/4",
        beatsPerBar = 4,
        sections = listOf(
            JSectionData(
                "第一句",
                listOf(
                    m(3, 1.0, fg = "弹"), m(3, 1.0, fg = "挑"), m(5, 1.0, fg = "弹"), m(6, 1.0, fg = "挑"),
                    m(1, 2.0, oct = 1, fg = "弹"), m(1, 1.0, oct = 1, fg = "挑"), m(6, 1.0, fg = "弹"),
                    m(5, 1.0, fg = "挑"), m(5, 1.0, fg = "弹"), m(6, 1.0, fg = "挑"), m(5, 1.0, fg = "弹"),
                    m(3, 4.0, fg = "轮"),
                )
            ),
            JSectionData(
                "第二句",
                listOf(
                    m(3, 1.0, fg = "弹"), m(3, 1.0, fg = "挑"), m(5, 1.0, fg = "弹"), m(6, 1.0, fg = "挑"),
                    m(1, 2.0, oct = 1, fg = "弹"), m(1, 1.0, oct = 1, fg = "挑"), m(6, 1.0, fg = "弹"),
                    m(5, 1.0, fg = "挑"), m(5, 1.0, fg = "弹"), m(6, 1.0, fg = "挑"), m(5, 1.0, fg = "弹"),
                    m(3, 4.0, fg = "轮"),
                )
            ),
            JSectionData(
                "第三句",
                listOf(
                    m(5, 1.0), m(5, 1.0), m(5, 1.0), m(3, 1.0),
                    m(2, 2.0), m(3, 1.0), m(5, 1.0),
                    m(6, 2.0), m(5, 1.0), m(3, 1.0),
                    m(2, 2.0), m(1, 2.0),
                )
            ),
            JSectionData(
                "第四句",
                listOf(
                    m(3, 1.0), m(2, 1.0), m(1, 1.0), m(6, 1.0, oct = -1),
                    m(5, 2.0, oct = -1), m(5, 1.0), m(6, 1.0),
                    m(1, 2.0), m(2, 2.0),
                    m(1, 4.0),
                )
            ),
        )
    )

    /** 凤阳花鼓（安徽民歌，明清传唱，公有领域）主题句。1=D 4/4。 */
    val FENGYANG = JScore(
        key = "1=D  4/4",
        beatsPerBar = 4,
        sections = listOf(
            JSectionData(
                "主题",
                listOf(
                    m(5, 1.0), m(6, 1.0), m(5, 1.0), m(3, 1.0),
                    m(2, 2.0), m(3, 2.0),
                    m(5, 1.0), m(3, 1.0), m(2, 1.0), m(1, 1.0),
                    m(6, 2.0, oct = -1), m(5, 2.0, oct = -1),
                )
            ),
            JSectionData(
                "下句",
                listOf(
                    m(1, 1.0), m(2, 1.0), m(3, 1.0), m(5, 1.0),
                    m(6, 2.0), m(5, 2.0),
                    m(3, 1.0), m(2, 1.0), m(1, 1.0), m(2, 1.0),
                    m(1, 4.0),
                )
            ),
            JSectionData(
                "锣鼓衬句",
                listOf(
                    m(5, 0.5), m(5, 0.5), m(3, 0.5), m(5, 0.5),
                    m(6, 1.0), m(5, 1.0),
                    m(3, 0.5), m(3, 0.5), m(2, 0.5), m(3, 0.5),
                    m(1, 2.0),
                )
            ),
        )
    )

    /** 小白菜（河北民歌，佚名民间小调，公有领域）。1=G 4/4，徵调式级进下行。 */
    val XIAOBAICAI = JScore(
        key = "1=G  4/4",
        beatsPerBar = 4,
        sections = listOf(
            JSectionData(
                "第一句",
                listOf(
                    m(6, 1.0), m(6, 1.0), m(5, 1.0), m(5, 1.0),
                    m(3, 2.0), m(3, 2.0),
                    m(2, 1.0), m(2, 1.0), m(1, 1.0), m(1, 1.0),
                    m(6, 2.0, oct = -1), m(6, 2.0, oct = -1),
                )
            ),
            JSectionData(
                "第二句",
                listOf(
                    m(5, 1.0), m(5, 1.0), m(6, 1.0), m(5, 1.0),
                    m(3, 2.0), m(2, 2.0),
                    m(1, 1.0), m(2, 1.0), m(1, 1.0), m(6, 1.0, oct = -1),
                    m(5, 4.0, oct = -1),
                )
            ),
        )
    )

    /** 导入的曲目（运行期由用户 JSON 录入时填充）。 */
    val IMPORTED = HashMap<String, JScore>()
}
