package com.vampuck.pipa_trainer.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 简谱 JSON 导入/导出。
 *
 * JSON 格式（可被 App「导入简谱」粘贴框识别）：
 * {
 *   "id": "my_piece",            // 唯一标识（英文/数字）
 *   "title": "曲名",
 *   "composerOrStyle": "作者/流派",
 *   "refBpm": 60,                 // 参考速度
 *   "difficulty": "进阶",
 *   "blurb": "一句话简介",
 *   "key": "1=G  4/4",
 *   "beatsPerBar": 4,
 *   "sections": [
 *     { "name": "第一段", "notes": [
 *         {"d":6, "oct":0, "dur":1,   "tr":false},   // d=音级1..7, 0=休止符
 *         {"d":3, "oct":0, "dur":3},                 // oct 省略=0, tr 省略=false
 *         {"d":2, "oct":1, "dur":0.5, "tr":true}     // oct:+1高八度 -1低八度; dur:拍数(1=四分,0.5=八分,0.25=十六分,2=二分,3=附点二分,4=全音符); tr:是否轮指
 *     ] }
 *   ]
 * }
 */
object JScoreJson {

    /** 输入框提示文案。 */
    const val TEMPLATE_HINT =
        "在此粘贴简谱 JSON（点下方「填入模板」看格式）。\n" +
        "字段：d=音级1~7(0休止) oct=八度(+1高/-1低) dur=拍数(1四分/0.5八分/0.25十六/2二分/3附点二分/4全) tr=是否轮指 fg=指法标记(弹/挑/抹/勾/扫/拂/轮等,可省)"

    /** 一键填入的示例（公有领域《茉莉花》第一句），用户照此改成自己的谱子。 */
    val TEMPLATE_EXAMPLE: String = """
{
  "id": "my_piece",
  "title": "我的曲子",
  "composerOrStyle": "自录",
  "refBpm": 72,
  "difficulty": "自定义",
  "blurb": "示例：茉莉花第一句，照着改成你的谱子",
  "key": "1=D  4/4",
  "beatsPerBar": 4,
  "sections": [
  { "name": "第一句", "notes": [
    {"d":3,"dur":1,"fg":"弹"}, {"d":3,"dur":1,"fg":"挑"}, {"d":5,"dur":1}, {"d":6,"dur":1},
    {"d":1,"oct":1,"dur":2}, {"d":1,"oct":1,"dur":1}, {"d":6,"dur":1},
    {"d":5,"dur":1}, {"d":5,"dur":1}, {"d":6,"dur":1}, {"d":5,"dur":1},
    {"d":3,"dur":4,"tr":true}
  ] }
  ]
}
""".trim()

    /** 解析一首曲目（含元信息 + 简谱）。失败抛异常，调用方捕获并提示。 */
    fun parse(json: String): ImportedPiece {
        val o = JSONObject(json)
        val id = o.getString("id").trim()
        require(id.isNotEmpty()) { "id 不能为空" }

        val sectionsArr = o.getJSONArray("sections")
        val sections = ArrayList<JSectionData>()
        for (i in 0 until sectionsArr.length()) {
            val s = sectionsArr.getJSONObject(i)
            val name = s.optString("name", "第${i + 1}段")
            val notesArr = s.getJSONArray("notes")
            val notes = ArrayList<JNote>()
            for (j in 0 until notesArr.length()) {
                val n = notesArr.getJSONObject(j)
                notes.add(
                    JNote(
                        degree = n.getInt("d"),
                        octave = n.optInt("oct", 0),
                        dur = n.optDouble("dur", 1.0),
                        tremolo = n.optBoolean("tr", false),
                        finger = n.optString("fg", "")
                    )
                )
            }
            sections.add(JSectionData(name, notes))
        }
        require(sections.isNotEmpty()) { "sections 不能为空" }

        val score = JScore(
            key = o.optString("key", ""),
            beatsPerBar = o.optInt("beatsPerBar", 4),
            sections = sections
        )
        val piece = PracticePiece(
            id = id,
            title = o.optString("title", id),
            composerOrStyle = o.optString("composerOrStyle", "导入曲目"),
            refBpm = o.optInt("refBpm", 60),
            difficulty = o.optString("difficulty", "自定义"),
            blurb = o.optString("blurb", ""),
            sections = sections.map { PieceSection(it.name, it.beats.toInt().coerceAtLeast(1)) }
        )
        return ImportedPiece(piece, score, json)
    }

    /** 把一首曲目导出成 JSON 字符串（用于分享/备份）。 */
    fun export(piece: PracticePiece, score: JScore): String {
        val o = JSONObject()
        o.put("id", piece.id)
        o.put("title", piece.title)
        o.put("composerOrStyle", piece.composerOrStyle)
        o.put("refBpm", piece.refBpm)
        o.put("difficulty", piece.difficulty)
        o.put("blurb", piece.blurb)
        o.put("key", score.key)
        o.put("beatsPerBar", score.beatsPerBar)
        val secArr = JSONArray()
        for (s in score.sections) {
            val so = JSONObject()
            so.put("name", s.name)
            val na = JSONArray()
            for (n in s.notes) {
                val no = JSONObject()
                no.put("d", n.degree)
                if (n.octave != 0) no.put("oct", n.octave)
                no.put("dur", n.dur)
                if (n.tremolo) no.put("tr", true)
                if (n.finger.isNotEmpty()) no.put("fg", n.finger)
                na.put(no)
            }
            so.put("notes", na)
            secArr.put(so)
        }
        o.put("sections", secArr)
        return o.toString(2)
    }
}

/** 一首导入的曲目：元信息 + 简谱 + 原始 JSON（便于再导出/编辑）。 */
data class ImportedPiece(
    val piece: PracticePiece,
    val score: JScore,
    val rawJson: String
)
