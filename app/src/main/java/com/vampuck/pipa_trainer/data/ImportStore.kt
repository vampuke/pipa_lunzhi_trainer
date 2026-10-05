package com.vampuck.pipa_trainer.data

import android.content.Context
import org.json.JSONArray

/**
 * 用户导入曲目的持久化（SharedPreferences 里存一个 JSON 数组）。
 *
 * 原来是把每首曲子的原始 JSON 用带内分隔符 `\n---PIECE---\n` 拼成一个大字符串：
 * 只要曲名/简介里出现这个字面量，列表就会被切碎，而且下一次写入会把损坏的结果
 * 存回去（不可逆）。改成 JSON 数组，转义交给 JSON 自己处理。旧的带内分隔符格式
 * 仍然能读，写入时自动升级。
 */
object ImportStore {
    private const val PREF = "imported_scores"
    private const val KEY = "json_list"
    private const val LEGACY_SEP = "\n---PIECE---\n"

    fun loadAll(ctx: Context) {
        for (json in readAll(ctx)) {
            try {
                PracticePieces.registerImported(JScoreJson.parse(json))
            } catch (_: Throwable) { /* 跳过损坏条目 */ }
        }
    }

    /** 保存一首（解析成功后调用）。按 id 去重覆盖。 */
    fun save(ctx: Context, ip: ImportedPiece) {
        val list = readAll(ctx).toMutableList()
        list.removeAll { runCatching { JScoreJson.parse(it).piece.id }.getOrNull() == ip.piece.id }
        list.add(ip.rawJson)
        persist(ctx, list)
    }

    fun delete(ctx: Context, id: String) {
        val list = readAll(ctx).filter {
            runCatching { JScoreJson.parse(it).piece.id }.getOrNull() != id
        }
        persist(ctx, list)
    }

    /** 读回全部条目。新格式是 JSON 数组；旧格式回退到分隔符切分。 */
    private fun readAll(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(KEY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
        } catch (_: Throwable) {
            raw.split(LEGACY_SEP).filter { it.isNotBlank() }
        }
    }

    private fun persist(ctx: Context, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
