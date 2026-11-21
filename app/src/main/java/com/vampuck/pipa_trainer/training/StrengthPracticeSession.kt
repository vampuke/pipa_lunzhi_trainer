package com.vampuck.pipa_trainer.training

import com.vampuck.pipa_trainer.practice.PracticeRecorder
import com.vampuck.pipa_trainer.practice.PracticeSource

/**
 * One monotonic timeline for phases, summaries and saved WORK intersections.
 * Every uninterrupted run maps planned boundaries back to their real clock times;
 * rendering may miss any number of stages without losing work or recording rest.
 */
class StrengthPracticeSession(val plan: TrainingPlan, private val recorder: PracticeRecorder) {
    var elapsedMs = 0L
        private set
    /** Real active session time, including lead-in/rest but excluding skips and pauses. */
    var activeMs = 0L
        private set
    var isRunning = false
        private set
    var isPaused = false
        private set
    private var anchorMs = 0L
    private var wallAnchorMs = 0L
    private var runAnchorMs = 0L
    private var recordingRound = -1
    private var nextCheckpointMs = 0L
    private val played = mutableMapOf<Int, Long>()
    val stage: TrainingPlan.Stage get() = plan.stageAt(elapsedMs / 1000.0)
    fun playedMs(index: Int): Long = played[index] ?: 0L
    fun hasWork(index: Int): Boolean = played.containsKey(index)

    fun start(now: Long, wallNow: Long = System.currentTimeMillis()) {
        stop(now)
        elapsedMs = 0L
        activeMs = 0L
        played.clear()
        anchorMs = now
        runAnchorMs = now
        wallAnchorMs = wallNow
        isRunning = plan.rounds.isNotEmpty()
        isPaused = false
    }

    fun update(now: Long) {
        if (!isRunning || isPaused) return
        val from = elapsedMs
        val to = (from + (now - anchorMs).coerceAtLeast(0L))
            .coerceAtMost(plan.totalSec.toLong() * 1_000L)
        for (index in plan.rounds.indices) {
            val workStart = plan.roundStartSec(index).toLong() * 1_000L
            val workEnd = workStart + plan.rounds[index].durationSec.toLong() * 1_000L
            val begin = maxOf(from, workStart)
            val end = minOf(to, workEnd)
            if (end <= begin) continue
            val realStart = anchorMs + begin - from
            val realEnd = anchorMs + end - from
            if (recordingRound != index) {
                // A previous WORK is always closed at its boundary below.
                recorder.start(PracticeSource.BASICS, realStart,
                    wallAnchorMs + realStart - runAnchorMs)
                recordingRound = index
                nextCheckpointMs = realStart + 5_000L
            }
            played[index] = playedMs(index) + end - begin
            if (end == workEnd) {
                closeRecording(realEnd)
            } else if (realEnd >= nextCheckpointMs) {
                recorder.checkpoint(realEnd)
                nextCheckpointMs = realEnd + 5_000L
            }
        }
        activeMs += to - from
        elapsedMs = to
        anchorMs = now
        if (stage.phase == TrainingPlan.Phase.DONE) isRunning = false
    }

    fun checkpoint(now: Long) {
        update(now)
        if (isRunning && !isPaused && recordingRound >= 0) recorder.checkpoint(now)
    }

    fun pause(now: Long) {
        if (!isRunning || isPaused) return
        update(now) // include the tail since the last tick, before freezing
        closeRecording(now)
        if (isRunning) isPaused = true
    }

    fun resume(now: Long, wallNow: Long = System.currentTimeMillis()) {
        if (!isRunning || !isPaused) return
        anchorMs = now
        runAnchorMs = now
        wallAnchorMs = wallNow
        isPaused = false
    }

    fun skip(now: Long) {
        if (!isRunning || isPaused) return
        update(now) // the stage at click time may differ from the last render
        if (!isRunning) return
        if (stage.phase == TrainingPlan.Phase.WORK) played.putIfAbsent(stage.roundIndex, 0L)
        closeRecording(now)
        // Moving the planned cursor is not an interval of actual practice.
        elapsedMs = (plan.stageEndSec(elapsedMs / 1000.0) * 1_000L).toLong()
        if (stage.phase == TrainingPlan.Phase.DONE) isRunning = false
    }

    fun stop(now: Long) {
        update(now)
        closeRecording(now)
        isRunning = false
        isPaused = false
    }

    fun append(now: Long) {
        update(now)
        if (isRunning) plan.addRound()
    }

    fun dropLast(now: Long): Boolean {
        update(now)
        val index = plan.size - 1
        if (!isRunning || !plan.canRemoveDuringRun(index, stage) || !plan.removeRound(index)) return false
        if (stage.phase == TrainingPlan.Phase.DONE) {
            closeRecording(now)
            isRunning = false
        }
        return true
    }

    private fun closeRecording(now: Long) {
        if (recordingRound < 0) return
        recorder.stop(now)
        recordingRound = -1
    }
}
