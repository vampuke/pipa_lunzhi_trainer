package com.vampuck.pipa_trainer.practice

/**
 * Active-time recorder. Every run between pauses is an independent segment;
 * a segment may be split at midnight, but never overwrites another segment.
 */
class PracticeRecorder(private val save: (String, PracticeSource, String, Long) -> Unit) {
    var sessionId: String? = null
        private set
    private var source = PracticeSource.BASICS
    private var segment = 0
    private var wallStartMs = 0L
    private var anchorMs = 0L
    private var paused = false

    fun start(source: PracticeSource, now: Long, wallNow: Long = System.currentTimeMillis()) {
        stop(now)
        sessionId = "${wallNow}_$now"
        this.source = source
        segment = 0
        wallStartMs = wallNow
        anchorMs = now
        paused = false
    }

    fun checkpoint(now: Long) {
        val id = sessionId ?: return
        if (paused) return // pause() already saved this segment's exact endpoint
        val activeMs = (now - anchorMs).coerceAtLeast(0L)
        var partStart = wallStartMs
        val activeEnd = wallStartMs + activeMs
        while (partStart < activeEnd) {
            val dayEnd = PracticeDay.nextDayStart(partStart)
            val partEnd = activeEnd.coerceAtMost(dayEnd)
            val day = PracticeDay.at(partStart)
            // The segment number makes every pause/resume segment distinct.
            // Re-saving a checkpoint for the same segment/day replaces its value.
            save("$id:$segment:$day", source, day, partEnd - partStart)
            partStart = partEnd
        }
    }

    fun pause(now: Long) {
        if (sessionId == null || paused) return
        checkpoint(now)
        paused = true
    }

    fun resume(now: Long, wallNow: Long = System.currentTimeMillis()) {
        if (sessionId == null || !paused) return
        // Keep all saved segments untouched. The next segment begins at the
        // actual resume instant, so neither a pause nor midnight is counted.
        segment++
        wallStartMs = wallNow
        anchorMs = now
        paused = false
    }

    fun stop(now: Long) {
        if (sessionId == null) return
        checkpoint(now)
        sessionId = null
        paused = false
    }
}
