package com.vampuck.pipa_trainer.practice

import java.util.Locale

/** A monotonic stopwatch/countdown. Paused and background time never counts. */
enum class FreePracticeMode { STOPWATCH, COUNTDOWN }

class FreePracticeTimer(
    val mode: FreePracticeMode,
    val targetMs: Long = 0L
) {
    private var anchorMs = 0L
    private var accumulatedMs = 0L
    private var completionDelivered = false

    var isStarted = false
        private set
    var isPaused = false
        private set
    var isCompleted = false
        private set

    fun start(now: Long) {
        if (isStarted && !isPaused) return
        if (isStarted && isPaused) {
            resume(now)
            return
        }
        anchorMs = now
        accumulatedMs = 0L
        isStarted = true
        isPaused = false
        isCompleted = false
        completionDelivered = false
    }

    fun pause(now: Long) {
        if (!isStarted || isPaused || isCompleted) return
        accumulatedMs = elapsedAt(now)
        isPaused = true
    }

    fun resume(now: Long) {
        if (!isStarted || !isPaused || isCompleted) return
        anchorMs = now
        isPaused = false
    }

    fun reset() {
        isStarted = false
        isPaused = false
        isCompleted = false
        completionDelivered = false
        accumulatedMs = 0L
    }

    fun elapsedAt(now: Long): Long {
        if (isCompleted) return targetMs
        if (!isStarted || isPaused) return accumulatedMs
        return accumulatedMs + (now - anchorMs).coerceAtLeast(0L)
    }

    fun displayedMsAt(now: Long): Long = when (mode) {
        FreePracticeMode.STOPWATCH -> elapsedAt(now)
        FreePracticeMode.COUNTDOWN -> (targetMs - elapsedAt(now)).coerceAtLeast(0L)
    }

    /** The caller stops recording before invoking this again. */
    fun pollCompletion(now: Long): Boolean {
        if (mode != FreePracticeMode.COUNTDOWN || !isStarted || isPaused ||
            isCompleted || elapsedAt(now) < targetMs
        ) return false
        isCompleted = true
        if (completionDelivered) return false
        completionDelivered = true
        return true
    }

    fun displayAt(now: Long): String {
        val hundredths = displayedMsAt(now) / 10L
        val seconds = hundredths / 100L
        return if (seconds >= 3_600L) {
            String.format(Locale.ROOT, "%02d:%02d:%02d.%02d",
                seconds / 3_600L, seconds % 3_600L / 60L,
                seconds % 60L, hundredths % 100L
            )
        } else {
            String.format(Locale.ROOT, "%02d:%02d.%02d", seconds / 60L, seconds % 60L, hundredths % 100L)
        }
    }
}
