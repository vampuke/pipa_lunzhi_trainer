package com.vampuck.pipa_trainer.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreePracticeTimerTest {
    @Test
    fun `stopwatch counts up in hundredths`() {
        val timer = FreePracticeTimer(FreePracticeMode.STOPWATCH)
        timer.start(1_000)
        assertEquals("00:01.23", timer.displayAt(2_234))
        assertEquals("01:02:03.45", timer.displayAt(1_000 + 3_723_456))
    }

    @Test
    fun `pause freezes the clock and resume excludes the pause`() {
        val timer = FreePracticeTimer(FreePracticeMode.STOPWATCH)
        timer.start(0)
        timer.pause(5_000)
        assertEquals("00:05.00", timer.displayAt(50_000))
        timer.resume(50_000)
        assertEquals("00:06.00", timer.displayAt(51_000))
    }

    @Test
    fun `countdown displays the initial duration and reaches zero exactly once`() {
        val timer = FreePracticeTimer(FreePracticeMode.COUNTDOWN, 90_000)
        assertEquals("01:30.00", timer.displayAt(0))
        timer.start(1_000)
        assertEquals("01:29.99", timer.displayAt(1_001))
        assertFalse(timer.pollCompletion(90_999))
        assertTrue(timer.pollCompletion(91_000))
        assertFalse(timer.pollCompletion(100_000))
        assertEquals("00:00.00", timer.displayAt(100_000))
        assertTrue(timer.isCompleted)
    }

    @Test
    fun `countdown paused time is not counted`() {
        val timer = FreePracticeTimer(FreePracticeMode.COUNTDOWN, 30_000)
        timer.start(0)
        timer.pause(10_000)
        timer.resume(20_000)
        assertEquals("00:20.00", timer.displayAt(20_000))
        assertFalse(timer.pollCompletion(39_999))
        assertTrue(timer.pollCompletion(40_000))
    }

    @Test
    fun `timer can reset and restart after stopping`() {
        val timer = FreePracticeTimer(FreePracticeMode.STOPWATCH)
        timer.start(0)
        timer.pause(5_000)
        timer.reset()
        assertEquals("00:00.00", timer.displayAt(99_000))
        assertFalse(timer.isStarted)
        timer.start(100_000)
        assertEquals("00:01.00", timer.displayAt(101_000))
    }
}
