package com.vampuck.pipa_trainer.practice

/** Coordinates the timer and the active-time recorder. */
class FreePracticeSession(val timer: FreePracticeTimer, private val recorder: PracticeRecorder) {
    fun start(now: Long, wallNow: Long = System.currentTimeMillis()) {
        if (timer.isStarted) {
            if (timer.isPaused) resume(now, wallNow)
            return
        }
        timer.start(now)
        recorder.start(PracticeSource.FREE, now, wallNow)
    }

    fun checkpoint(now: Long) = recorder.checkpoint(timer.recordingEndAt(now))

    fun pollCompletion(now: Long): Boolean {
        val endpoint = timer.recordingEndAt(now)
        if (!timer.pollCompletion(now)) return false
        recorder.stop(endpoint)
        return true
    }

    fun pause(now: Long) {
        if (!timer.isStarted || timer.isPaused || timer.isCompleted) return
        if (pollCompletion(now)) return
        recorder.pause(timer.recordingEndAt(now))
        timer.pause(now)
    }

    fun resume(now: Long, wallNow: Long = System.currentTimeMillis()) {
        if (!timer.isStarted || !timer.isPaused || timer.isCompleted) return
        timer.resume(now)
        recorder.resume(now, wallNow)
    }

    fun stop(now: Long) = recorder.stop(timer.recordingEndAt(now))
}
