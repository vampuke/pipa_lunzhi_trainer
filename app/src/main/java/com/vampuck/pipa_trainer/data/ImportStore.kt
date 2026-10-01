package com.vampuck.pipa_trainer.data

import android.content.Context

/**
 * 用户导入曲目的持久化（SharedPreferences 存原始 JSON 列表）。
 * 启动时读回并注册到 PracticePieces。
 */
object ImportStore {
    private const val PREF = "imported_scores"
    private const val KEY = "json_list"
    private const val SEP = "\n---PIECE---\n"

    fun loadAll(ctx: Context) {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        if (raw.isBlank()) return
        for (chunk in raw.split(SEP)) {
            if (chunk.isBlank()) continue
            try {
                PracticePieces.registerImported(JScoreJson.parse(chunk))
            } catch (_: Throwable) { /* 跳过损坏条目 */ }
        }
    }

    /** 保存一首（解析成功后调用）。按 id 去重覆盖。 */
    fun save(ctx: Context, ip: ImportedPiece) {
        val list = currentJsonList(ctx).toMutableList()
        list.removeAll { runCatching { JScoreJson.parse(it).piece.id }.getOrNull() == ip.piece.id }
        list.add(ip.rawJson)
        persist(ctx, list)
    }

    fun delete(ctx: Context, id: String) {
        val list = currentJsonList(ctx).filter {
            runCatching { JScoreJson.parse(it).piece.id }.getOrNull() != id
        }
        persist(ctx, list)
    }

    private fun currentJsonList(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        return if (raw.isBlank()) emptyList() else raw.split(SEP).filter { it.isNotBlank() }
    }

    private fun persist(ctx: Context, list: List<String>) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY, list.joinToString(SEP))
            .apply()
    }
}
