package com.vampuck.pipa_trainer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vampuck.pipa_trainer.databinding.ActivityFreePracticeBinding
import com.vampuck.pipa_trainer.practice.FreePracticeMode
import com.vampuck.pipa_trainer.practice.FreePracticeTimer
import com.vampuck.pipa_trainer.practice.PracticeRecorder
import com.vampuck.pipa_trainer.practice.PracticeSource
import com.vampuck.pipa_trainer.practice.PracticeStore

/** Free practice timer; no mic, sound, permissions or network involved. */
class FreePracticeActivity : AppCompatActivity() {
    private lateinit var b: ActivityFreePracticeBinding
    private var mode = FreePracticeMode.COUNTDOWN
    private var durationMin = DEFAULT_MINUTES
    private var timer = FreePracticeTimer(mode, durationMin * 60_000L)
    private var startedThisVisit = false
    private var nextCheckpointMs = 0L
    private val recorder by lazy {
        val store = PracticeStore(applicationContext)
        PracticeRecorder(store::save)
    }
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (timer.isStarted && !timer.isPaused && !timer.isCompleted) {
                handler.postDelayed(this, 31L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityFreePracticeBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        b.modeGroup.check(R.id.btnCountdown)
        b.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || timer.isStarted) return@addOnButtonCheckedListener
            mode = if (checkedId == R.id.btnStopwatch) {
                FreePracticeMode.STOPWATCH
            } else {
                FreePracticeMode.COUNTDOWN
            }
            rebuildTimer()
        }
        b.btnMinus.setOnClickListener { setDuration(durationMin - 1) }
        b.btnPlus.setOnClickListener { setDuration(durationMin + 1) }
        b.btnStart.setOnClickListener { start() }
        b.btnPause.setOnClickListener {
            if (timer.isPaused) resume() else pause(manual = true)
        }
        b.btnReset.setOnClickListener { confirmFinish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (timer.isStarted && !timer.isCompleted) confirmFinish() else finish()
            }
        })
        render()
    }

    private fun setDuration(minutes: Int) {
        if (timer.isStarted) return
        durationMin = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
        rebuildTimer()
    }

    private fun rebuildTimer() {
        recorder.stop(SystemClock.elapsedRealtime())
        timer = FreePracticeTimer(mode, durationMin * 60_000L)
        startedThisVisit = false
        handler.removeCallbacks(ticker)
        render()
    }

    private fun start() {
        if (timer.isStarted && timer.isPaused) {
            resume()
            return
        }
        if (timer.isStarted && !timer.isCompleted) return
        val now = SystemClock.elapsedRealtime()
        rebuildTimer()
        timer.start(now)
        recorder.start(PracticeSource.FREE, now)
        startedThisVisit = true
        nextCheckpointMs = now + 5_000L
        handler.post(ticker)
        render()
    }

    private fun pause(manual: Boolean) {
        if (!timer.isStarted || timer.isPaused || timer.isCompleted) return
        val now = SystemClock.elapsedRealtime()
        timer.pause(now)
        recorder.pause(now)
        handler.removeCallbacks(ticker)
        render()
        if (!manual) b.timeHint.setText(R.string.free_background)
    }

    private fun resume() {
        if (!timer.isStarted || !timer.isPaused || timer.isCompleted) return
        val now = SystemClock.elapsedRealtime()
        timer.resume(now)
        recorder.resume(now)
        nextCheckpointMs = now + 5_000L
        handler.post(ticker)
        render()
    }

    private fun complete() {
        recorder.stop(SystemClock.elapsedRealtime())
        handler.removeCallbacks(ticker)
        startedThisVisit = false
        render()
        b.timeHint.setText(R.string.free_completed)
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (now >= nextCheckpointMs) {
            recorder.checkpoint(now)
            nextCheckpointMs = now + 5_000L
        }
        if (timer.pollCompletion(now)) {
            complete()
            return
        }
        renderTime()
    }

    private fun render() {
        renderTime()
        val active = timer.isStarted && !timer.isCompleted
        b.modeGroup.isEnabled = !active
        b.btnCountdown.isEnabled = !active
        b.btnStopwatch.isEnabled = !active
        b.btnMinus.isEnabled = !active && mode == FreePracticeMode.COUNTDOWN
        b.btnPlus.isEnabled = !active && mode == FreePracticeMode.COUNTDOWN
        b.btnDuration.isEnabled = false
        b.durationLabel.alpha = if (mode == FreePracticeMode.COUNTDOWN) 1f else 0.45f
        b.durationControls.alpha = if (mode == FreePracticeMode.COUNTDOWN) 1f else 0.45f
        b.btnStart.isEnabled = !active || timer.isPaused
        b.btnStart.setText(if (timer.isPaused) R.string.free_resume else R.string.free_start)
        b.btnPause.isEnabled = active
        b.btnPause.setText(if (timer.isPaused) R.string.free_resume else R.string.free_pause)
        b.btnReset.isEnabled = startedThisVisit
        b.timeCaption.setText(
            if (mode == FreePracticeMode.COUNTDOWN) R.string.free_countdown_remaining
            else R.string.free_elapsed
        )
        b.btnDuration.text = getString(R.string.free_duration_value, durationMin)
        b.timeHint.setText(
            when {
                timer.isCompleted -> R.string.free_completed
                timer.isPaused -> R.string.free_paused
                timer.isStarted -> R.string.free_running
                else -> R.string.free_ready
            }
        )
    }

    private fun renderTime() {
        b.timeValue.text = timer.displayAt(SystemClock.elapsedRealtime())
    }

    private fun confirmFinish() {
        if (!startedThisVisit) {
            finish()
            return
        }
        pause(manual = true)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.free_quit_title)
            .setMessage(R.string.free_quit_message)
            .setPositiveButton(R.string.free_quit_confirm) { _, _ -> finish() }
            .setNegativeButton(R.string.free_resume) { _, _ -> resume() }
            .setOnCancelListener { resume() }
            .show()
    }

    override fun onStop() {
        super.onStop()
        pause(manual = false)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        recorder.stop(SystemClock.elapsedRealtime())
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    private companion object {
        const val DEFAULT_MINUTES = 10
        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 180
    }
}
