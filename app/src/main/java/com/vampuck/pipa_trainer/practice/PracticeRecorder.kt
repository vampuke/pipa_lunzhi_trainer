package com.vampuck.pipa_trainer.practice

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object PracticeTime {
    fun dayAt(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(epochMs))

    fun nextDayStart(epochMs: Long): Long = Calendar.getInstance().apply {
        timeInMillis = epochMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
}

/**
 * Active-time session. Checkpoints are cheap and idempotent in PracticeStore.
 * Wall time is used only for the date; elapsedRealtime drives all durations.
 */
class PracticeRecorder(private val save: (String, PracticeSource, String, Long) -> Unit) {
    var sessionId: String? = null
        private set
    private var source = PracticeSource.BASICS
    private var wallStartMs = 0L
    private var anchorMs = 0L
    private var accumulatedMs = 0L
    private var pausedAtMs: Long? = null

    fun elapsed(now: Long): Long =
        accumulatedMs + if (sessionId != null && pausedAtMs == null) now - anchorMs else 0L

    fun start(source: PracticeSource, now: Long,
              wallNow: Long = System.currentTimeMillis()) {
        stop(now)
        sessionId = "${wallNow}_${now}"
        this.source = source
        wallStartMs = wallNow
        anchorMs = now
        accumulatedMs = 0L
        pausedAtMs = null
    }

    fun checkpoint(now: Long) {
        val id = sessionId ?: return
        val total = elapsed(now)
        var start = wallStartMs
        var offset = 0L
        while (true) {
            val dayEnd = PracticeTime.nextDayStart(start)
            val end = (wallStartMs + total).coerceAtMost(dayEnd)
            val duration = (end - start).coerceAtLeast(0L)
            if (duration > 0L) save("$id:$offset", source, PracticeTime.dayAt(start), duration)
            if (end >= wallStartMs + total) break
            offset += duration
            start = dayEnd
        }
    }

    fun pause(now: Long) {
        if (sessionId == null || pausedAtMs != null) return
        accumulatedMs = elapsed(now)
        pausedAtMs = now
        checkpoint(now)
    }

    fun resume(now: Long) {
        if (sessionId == null || pausedAtMs == null) return
        wallStartMs += now - anchorMs
        anchorMs = now
        pausedAtMs = null
    }

    fun stop(now: Long) {
        if (sessionId == null) return
        if (pausedAtMs == null) checkpoint(now)
        sessionId = null
        accumulatedMs = 0L
        pausedAtMs = null
    }
}
