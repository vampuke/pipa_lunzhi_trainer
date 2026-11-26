package com.vampuck.pipa_trainer.practice

import android.content.Context

/** Local practice storage. Account metadata lives in a separate store. */
class PracticeStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("practice_activity", Context.MODE_PRIVATE)

    @Synchronized
    fun save(sessionId: String, source: PracticeSource, day: String, elapsedMs: Long) {
        if (!PracticeLog.isValidDay(day) || sessionId.isBlank() || elapsedMs < 0) return
        prefs.edit().putString(sessionId, "${source.key}|$day|$elapsedMs").apply()
    }

    @Synchronized
    fun load(): PracticeLog = PracticeLog.decode(rawEntries())

    /** Read-only copy, including original keys and nonpositive durations for sync bookkeeping. */
    @Synchronized
    fun rawEntries(): List<Pair<String, PracticeLog.Entry>> {
        val rows = ArrayList<Pair<String, PracticeLog.Entry>>()
        for ((id, raw) in prefs.all) {
            val parts = (raw as? String)?.split('|') ?: continue
            if (id.startsWith("_") || parts.size != 3) continue
            val source = PracticeSource.values().firstOrNull { it.key == parts[0] } ?: continue
            val ms = parts[2].toLongOrNull() ?: continue
            rows.add(id to PracticeLog.Entry(source, parts[1], ms))
        }
        return rows
    }
}
