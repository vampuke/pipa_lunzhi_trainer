package com.vampuck.pipa_trainer.practice

import android.content.Context

/** Small local-only store; no account, network or storage permission. */
class PracticeStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("practice_activity", Context.MODE_PRIVATE)

    @Synchronized
    fun save(sessionId: String, source: PracticeSource, day: String, elapsedMs: Long) {
        if (!PracticeLog.isValidDay(day) || sessionId.isBlank() || elapsedMs < 0) return
        prefs.edit().putString(sessionId, "${source.key}|$day|$elapsedMs").apply()
    }

    @Synchronized
    fun load(): PracticeLog {
        val rows = ArrayList<Pair<String, PracticeLog.Entry>>()
        for ((id, raw) in prefs.all) {
            val parts = (raw as? String)?.split('|') ?: continue
            if (id.startsWith("_") || parts.size != 3) continue
            val source = PracticeSource.values().firstOrNull { it.key == parts[0] } ?: continue
            val ms = parts[2].toLongOrNull() ?: continue
            rows.add(id to PracticeLog.Entry(source, parts[1], ms))
        }
        return PracticeLog.decode(rows)
    }
}

object PracticeFormat {
    fun duration(ms: Long): String {
        val totalMinutes = ms / 60_000L
        return if (totalMinutes < 60) "$totalMinutes 分钟"
        else "${totalMinutes / 60} 小时 ${totalMinutes % 60} 分钟"
    }

    fun fullDate(day: String): String = PracticeDay.displayDate(day)
}
