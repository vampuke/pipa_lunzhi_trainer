package com.vampuck.pipa_trainer.data

/**
 * 琵琶指法标记。
 *
 * 简谱里琵琶指法用符号标注（不用汉字）。各出版社符号略有出入，这里采用
 * 大陆通行（中央音乐学院/人音社教材）常见的一组右手为主的记号，并由
 * JianpuView 用几何图形直接绘制（不依赖字体，保证各机型一致）。
 *
 * code 是存储用的英文键；symbol 是展示用的符号；name 是中文名（编辑器里给用户看）。
 * 绘制逻辑在 JianpuView.drawFinger(code)。
 */
data class Fingering(val code: String, val symbol: String, val name: String)

object Fingerings {
    const val NONE = ""

    // 右手主要指法
    val TAN     = Fingering("tan", "丶", "弹")       // 食指向外弹
    val TIAO    = Fingering("tiao", "㇀", "挑")      // 拇指向里挑
    val LUN     = Fingering("lun", "彡", "轮")       // 五指轮（三斜线）
    val BANLUN  = Fingering("banlun", "彳", "半轮")  // 半轮（两斜线）
    val SAO     = Fingering("sao", "∨", "扫")        // 四弦一起向下
    val FU      = Fingering("fu", "∧", "拂")         // 四弦一起向上
    val GOU     = Fingering("gou", "𠃌", "勾")       // 拇指勾弦
    val MO      = Fingering("mo", "一", "抹")        // 食指向里抹
    val FAN     = Fingering("fan", "○", "泛")        // 泛音

    /** 调色板顺序（编辑器用）。首项为「无」。 */
    val PALETTE: List<Fingering> = listOf(
        Fingering(NONE, "—", "无"),
        TAN, TIAO, LUN, BANLUN, SAO, FU, GOU, MO, FAN
    )

    fun byCode(code: String): Fingering? =
        PALETTE.firstOrNull { it.code == code }

    /** 该指法是否应连带画轮指斜线（轮/半轮本身即由 drawFinger 画斜线）。 */
    fun isTremolo(code: String): Boolean = code == LUN.code || code == BANLUN.code
}
