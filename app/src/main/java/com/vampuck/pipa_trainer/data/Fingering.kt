package com.vampuck.pipa_trainer.data

/**
 * 琵琶右手指法标记。符号形态以用户提供的《右手基本指法标记》图为准：
 *  弹 \  挑 /  双弹 \\  双挑 //  摭 )  分 /  扫 \+羽  拂 /+羽
 *  轮指 放射星芒  滚 ///  另保留 泛○ 勾 抹 等。
 *
 * code 为存储键；name 为中文名；实际绘制在 view/FingerSymbols.draw(code)，
 * 谱面与编辑器调色板共用，保证所见即所得。
 */
data class Fingering(val code: String, val name: String)

object Fingerings {
    const val NONE = ""

    val TAN        = Fingering("tan", "弹")          // \
    val TIAO       = Fingering("tiao", "挑")         // /
    val SHUANGTAN  = Fingering("shuangtan", "双弹")  // \\
    val SHUANGTIAO = Fingering("shuangtiao", "双挑") // //
    val ZHE        = Fingering("zhe", "摭")          // )
    val FEN        = Fingering("fen", "分")          // /
    val SAO        = Fingering("sao", "扫")          // \ + 羽状穿线
    val FU         = Fingering("fu", "拂")           // / + 羽状穿线
    val LUN        = Fingering("lun", "轮")          // 放射星芒
    val GUN        = Fingering("gun", "滚")          // ///
    val CHANGLUN   = Fingering("changlun", "长轮")   // 星芒 + 点
    val BANLUN     = Fingering("banlun", "半轮")     // 半星芒
    val GOU        = Fingering("gou", "勾")          // 勹
    val MO         = Fingering("mo", "抹")           // 横
    val FAN        = Fingering("fan", "泛")          // ○

    /** 调色板顺序（编辑器用）。首项为「无」，其余按图顺序 + 常用补充。 */
    val PALETTE: List<Fingering> = listOf(
        Fingering(NONE, "无"),
        TAN, TIAO, SHUANGTAN, SHUANGTIAO, ZHE, FEN, SAO, FU,
        LUN, GUN, CHANGLUN, BANLUN, GOU, MO, FAN
    )

    fun byCode(code: String): Fingering? = PALETTE.firstOrNull { it.code == code }

    /** 是否属轮/滚类（长音技法）。 */
    fun isTremolo(code: String): Boolean =
        code == LUN.code || code == CHANGLUN.code || code == BANLUN.code || code == GUN.code
}
