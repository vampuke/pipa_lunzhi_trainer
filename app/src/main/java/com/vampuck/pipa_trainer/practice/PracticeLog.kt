package com.vampuck.pipa_trainer.practice

/** Only actual running practice time counts; opening a page alone never counts. */
enum class PracticeSource(val key: String) {
    BASICS("basics"), METRONOME("metronome"), FREE("free")
}

/** A session checkpoint replaces its previous value, never double-counting it. */
class PracticeLog {
    private val entries = LinkedHashMap<String, Entry>()
    data class Entry(val source: PracticeSource, val day: String, val elapsedMs: Long)

    fun put(sessionId: String, source: PracticeSource, day: String, elapsedMs: Long): Boolean {
        if (sessionId.isBlank() || elapsedMs < 0L || !PracticeDay.isValid(day)) return false
        entries[sessionId] = Entry(source, day, elapsedMs)
        return true
    }

    fun totalMs(day: String, source: PracticeSource? = null): Long = entries.values
        .filter { it.day == day && (source == null || it.source == source) }
        .sumOf { it.elapsedMs }

    fun totalMsSince(firstDay: String): Long = entries.values
        .filter { it.day >= firstDay }.sumOf { it.elapsedMs }

    fun snapshot(): List<Pair<String, Entry>> = entries.map { it.key to it.value }

    companion object {
        fun isValidDay(day: String) = PracticeDay.isValid(day)
        fun decode(rows: List<Pair<String, Entry>>): PracticeLog {
            val log = PracticeLog()
            for ((id, entry) in rows) log.put(id, entry.source, entry.day, entry.elapsedMs)
            return log
        }
    }
}

object PracticeStats {
    fun level(elapsedMs: Long): Int = when {
        elapsedMs <= 0L -> 0
        elapsedMs < 10 * 60_000L -> 1
        elapsedMs < 20 * 60_000L -> 2
        elapsedMs < 30 * 60_000L -> 3
        else -> 4
    }

    fun streak(log: PracticeLog, today: String): Int {
        if (!PracticeDay.isValid(today)) return 0
        var day = today
        // Before the first practice today, yesterday's current streak is still alive.
        if (log.totalMs(day) == 0L) day = PracticeDay.shift(day, -1)
        var count = 0
        while (log.totalMs(day) > 0L) {
            count++
            day = PracticeDay.shift(day, -1)
        }
        return count
    }
}
