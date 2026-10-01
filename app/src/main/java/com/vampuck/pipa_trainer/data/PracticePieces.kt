package com.vampuck.pipa_trainer.data

/**
 * 预设名曲「跟练」数据。
 *
 * 说明（诚实边界）：这里存的是**曲目的段落结构 + 参考速度**，不是逐音符的曲谱。
 * - 段落名称取自各曲传统/通行的分段（如《春江花月夜》十段标题、《十面埋伏》各阵段），
 *   属于有据可查的公认划分。
 * - 每段的 [beats]（拍数）是面向「练习陪练」的相对长度估值，用来驱动实时进度条，
 *   用户可整体调速；它不等于权威曲谱的小节数。
 *
 * 进度逻辑：总拍数 = Σ段落 beats；在速度 bpm 下，总时长 = 总拍数 × 60 / bpm。
 * 按「开始」后按 bpm 推进，实时显示所处段落与整体进度。
 */
data class PieceSection(
    val name: String,
    val beats: Int
)

data class PracticePiece(
    val id: String,
    val title: String,
    val composerOrStyle: String,  // 流派/作者/类型
    val refBpm: Int,              // 预设参考速度
    val difficulty: String,       // 入门/进阶/高级
    val blurb: String,            // 一句话简介
    val sections: List<PieceSection>
) {
    val totalBeats: Int get() = sections.sumOf { it.beats }
    fun totalSeconds(bpm: Int): Int = (totalBeats * 60.0 / bpm).toInt()
}

object PracticePieces {

    /** 曲目 id -> 简谱。先查内置，再查用户导入的。 */
    fun scoreFor(id: String): JScore? = when (id) {
        "lunzhi_etude" -> Scores.LUNZHI_ETUDE
        "two_tigers" -> Scores.TWO_TIGERS
        "little_star" -> Scores.LITTLE_STAR
        "jasmine" -> Scores.JASMINE
        "fengyang" -> Scores.FENGYANG
        "xiaobaicai" -> Scores.XIAOBAICAI
        "yimeng" -> Scores.YIMENG
        "yangguan" -> Scores.YANGGUAN
        else -> Scores.IMPORTED[id]
    }

    /** 运行期注册一首用户导入的曲目（JSON）。 */
    fun registerImported(ip: ImportedPiece) {
        Scores.IMPORTED[ip.piece.id] = ip.score
        imported.removeAll { it.id == ip.piece.id }
        imported.add(ip.piece)
    }

    /** 删除一首用户导入的曲目（内存态；持久层由 ImportStore 处理）。 */
    fun removeImported(id: String) {
        Scores.IMPORTED.remove(id)
        imported.removeAll { it.id == id }
    }

    /** 用户导入的曲目（内存态，由 ImportStore 持久化到 SharedPreferences）。 */
    val imported = ArrayList<PracticePiece>()

    val builtin: List<PracticePiece> = listOf(

        PracticePiece(
            id = "two_tigers",
            title = "两只老虎",
            composerOrStyle = "传统童谣 · 公有领域",
            refBpm = 80,
            difficulty = "入门",
            blurb = "最简单的入门曲，级进为主、音域窄，用来熟悉弹挑交替和看谱跟练。",
            sections = listOf(
                PieceSection("第一句", 4),
                PieceSection("第二句", 4),
                PieceSection("第三句", 4),
                PieceSection("第四句", 4),
            )
        ),

        PracticePiece(
            id = "little_star",
            title = "小星星",
            composerOrStyle = "传统童谣 · 公有领域",
            refBpm = 76,
            difficulty = "入门",
            blurb = "人人会唱的童谣，节奏规整，适合第一次看简谱跟练、练稳定弹挑。",
            sections = listOf(
                PieceSection("第一句", 4),
                PieceSection("第二句", 4),
                PieceSection("第三句", 4),
                PieceSection("第四句", 4),
            )
        ),

        PracticePiece(
            id = "chunjiang",
            title = "春江花月夜",
            composerOrStyle = "传统文曲 · 原名《夕阳箫鼓》",
            refBpm = 52,
            difficulty = "进阶",
            blurb = "琵琶文曲代表作，意境悠远，十段标题写景抒情。慢而匀是关键。",
            sections = listOf(
                PieceSection("江楼钟鼓", 40),
                PieceSection("月上东山", 48),
                PieceSection("风回曲水", 40),
                PieceSection("花影层叠", 32),
                PieceSection("水深云际", 40),
                PieceSection("渔歌唱晚", 48),
                PieceSection("洄澜拍岸", 32),
                PieceSection("桡鸣远濑", 40),
                PieceSection("欸乃归舟", 56),
                PieceSection("尾声", 24),
            )
        ),

        PracticePiece(
            id = "shimian",
            title = "十面埋伏",
            composerOrStyle = "传统武曲 · 垓下之战",
            refBpm = 100,
            difficulty = "高级",
            blurb = "琵琶武曲巅峰，描写楚汉决战。弹挑扫拂密集，段落推进要有气势。",
            sections = listOf(
                PieceSection("列营", 32),
                PieceSection("吹打", 48),
                PieceSection("点将", 32),
                PieceSection("排阵", 24),
                PieceSection("走队", 24),
                PieceSection("埋伏", 40),
                PieceSection("鸡鸣山小战", 32),
                PieceSection("九里山大战", 56),
                PieceSection("项王败阵", 40),
                PieceSection("乌江自刎", 32),
                PieceSection("众军归营", 24),
            )
        ),

        PracticePiece(
            id = "dalang",
            title = "大浪淘沙",
            composerOrStyle = "华彦钧（阿炳）",
            refBpm = 60,
            difficulty = "进阶",
            blurb = "阿炳传世名作，由沉郁慢板渐入激越快板，情感跌宕。",
            sections = listOf(
                PieceSection("第一段 · 慢板（感慨）", 96),
                PieceSection("第二段 · 渐快（转折）", 64),
                PieceSection("第三段 · 快板（抗争）", 48),
            )
        ),

        PracticePiece(
            id = "yangchun",
            title = "阳春白雪",
            composerOrStyle = "传统大曲 · 文武兼备",
            refBpm = 88,
            difficulty = "高级",
            blurb = "通行「大七板」结构，综合文曲武曲手法，明快清新。",
            sections = listOf(
                PieceSection("独占鳌头", 32),
                PieceSection("风摆荷花", 32),
                PieceSection("一轮明月", 32),
                PieceSection("玉版参禅", 32),
                PieceSection("铁策板声", 24),
                PieceSection("道院琴声", 32),
                PieceSection("东皋鹤鸣", 32),
            )
        ),

        PracticePiece(
            id = "yizu",
            title = "彝族舞曲",
            composerOrStyle = "王惠然 · 现代名曲",
            refBpm = 76,
            difficulty = "进阶",
            blurb = "由柔美主题转热烈快板，轮指与扫弦并重，是常见考级/演出曲目。",
            sections = listOf(
                PieceSection("引子", 16),
                PieceSection("柔美主题（轮指）", 48),
                PieceSection("热烈快板", 64),
                PieceSection("抒情中段", 48),
                PieceSection("主题再现", 32),
                PieceSection("尾声", 16),
            )
        ),

        PracticePiece(
            id = "jasmine",
            title = "茉莉花",
            composerOrStyle = "江苏民歌 · 公有领域",
            refBpm = 72,
            difficulty = "入门",
            blurb = "最广为流传的江南小调，源自清乾隆《鲜花调》。旋律婉转，适合练弹挑与连音。",
            sections = listOf(
                PieceSection("第一句", 4),
                PieceSection("第二句", 4),
                PieceSection("第三句", 4),
                PieceSection("第四句", 4),
            )
        ),

        PracticePiece(
            id = "fengyang",
            title = "凤阳花鼓",
            composerOrStyle = "安徽民歌 · 公有领域",
            refBpm = 92,
            difficulty = "入门",
            blurb = "明清传唱的花鼓调，欢快跳跃，带锣鼓衬句，练习节奏颗粒。",
            sections = listOf(
                PieceSection("主题", 4),
                PieceSection("下句", 4),
                PieceSection("锣鼓衬句", 2),
            )
        ),

        PracticePiece(
            id = "xiaobaicai",
            title = "小白菜",
            composerOrStyle = "河北民歌 · 公有领域",
            refBpm = 60,
            difficulty = "入门",
            blurb = "佚名民间小调，徵调式级进下行，哀婉质朴，适合慢速练音准与情绪。",
            sections = listOf(
                PieceSection("第一句", 4),
                PieceSection("第二句", 4),
            )
        ),

        PracticePiece(
            id = "lunzhi_etude",
            title = "轮指基础练习",
            composerOrStyle = "技术练习 · 匀速陪练",
            refBpm = 60,
            difficulty = "入门",
            blurb = "三段递进的匀速陪练：先稳后匀再提速。配合实时分析检查你的均匀度。",
            sections = listOf(
                PieceSection("慢速 · 求稳求匀", 60),
                PieceSection("中速 · 保持颗粒", 60),
                PieceSection("提速 · 稳住匀速", 60),
            )
        ),

        PracticePiece(
            id = "yimeng",
            title = "沂蒙山小调",
            composerOrStyle = "山东民歌 · 公有领域",
            refBpm = 66,
            difficulty = "进阶",
            blurb = "3/4 拍山东小调，旋律起伏较大，带连音与长轮，练乐句呼吸与轮指。",
            sections = listOf(
                PieceSection("上句", 9),
                PieceSection("下句", 9),
            )
        ),

        PracticePiece(
            id = "yangguan",
            title = "阳关三叠",
            composerOrStyle = "唐·王维词 · 古琴曲（公有领域）",
            refBpm = 56,
            difficulty = "高级",
            blurb = "千年古曲主题，节奏多变、含扫拂与泛音记号，考验指法切换与情绪表达。",
            sections = listOf(
                PieceSection("起", 8),
                PieceSection("承", 8),
                PieceSection("转合", 12),
            )
        ),
    )

    /** 全部曲目 = 内置 + 用户导入。 */
    val ALL: List<PracticePiece> get() = builtin + imported

    fun byId(id: String): PracticePiece? = ALL.firstOrNull { it.id == id }
}
