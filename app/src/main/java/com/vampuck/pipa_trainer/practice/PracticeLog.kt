package com.vampuck.pipa_trainer.practice

import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Only these two activities are practice; opening a page alone never counts. */
enum class PracticeSource(val key: String) {
    BASICS("basics"),
    METRONOME("metronome")
}

/**
 * A session's checkpoint replaces its previous value. Activities may therefore
 * save during a session and again on stop without adding the same time twice.
 */
class PracticeLog {
    private val entries = LinkedHashMap<String, Entry>()

    data class Entry(
        val source: PracticeSource,
        val day: String,
        val elapsedMs: Long
    )

    fun put(sessionId: String, source: PracticeSource, day: String, elapsedMs: Long): Boolean {
        if (sessionId.isBlank() || elapsedMs < 0L || !isValidDay(day)) return false
        entries[sessionId] = Entry(source, day, elapsedMs)
        return true
    }

    fun totalMs(day: String, source: PracticeSource? = null): Long = entries.values
        .filter { it.day == day && (source == null || it.source == source) }
        .sumOf { it.elapsedMs }

    fun totalMsSince(firstDay: String): Long = entries.values
        .filter { it.day >= firstDay }
        .sumOf { it.elapsedMs }

    fun snapshot(): List<Pair<String, Entry>> = entries.map { it.key to it.value }

    companion object {
        fun isValidDay(day: String): Boolean {
            if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(day)) return false
            return try { LocalDate.parse(day); true } catch (_: DateTimeParseException) { false }
        }

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
        if (!PracticeLog.isValidDay(today)) return 0
        var day = LocalDate.parse(today)
        var count = 0
        while (log.totalMs(day.toString()) > 0L) {
            count++
            day = day.minusDays(1)
        }
        return count
    }
}
