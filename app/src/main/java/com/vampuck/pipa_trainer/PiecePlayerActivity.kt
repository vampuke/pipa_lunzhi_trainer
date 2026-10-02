package com.vampuck.pipa_trainer

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vampuck.pipa_trainer.audio.Metronome
import com.vampuck.pipa_trainer.data.JScore
import com.vampuck.pipa_trainer.data.PracticePiece
import com.vampuck.pipa_trainer.data.PracticePieces
import com.vampuck.pipa_trainer.databinding.ActivityPiecePlayerBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * 简谱「跟练」播放页。
 *
 * 只做一件事：按 BPM 让简谱逐小节高亮前进，供用户看着谱子跟弹。
 * 时间轴以“已完成拍数 elapsedBeats”为核心（用 elapsedRealtime 累加，不随帧率漂移），
 * 当前小节 = floor(elapsedBeats / beatsPerBar)。没有进度条、没有段落列表。
 */
class PiecePlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPiecePlayerBinding
    private lateinit var piece: PracticePiece
    private lateinit var score: JScore

    private val metronome = Metronome()
    private var clickOn = true

    private var bpm = 60
    private var running = false
    private var elapsedBeats = 0.0
    private var lastTickMs = 0L
    private var totalBeats = 0.0
    private var totalBars = 1
    private var lastHlBar = -1

    private lateinit var dots: List<View>
    private var dotOffColor = 0
    private var accentColor = 0
    private var primaryColor = 0

    private var countdownJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPiecePlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        val id = intent.getStringExtra(EXTRA_PIECE_ID)
        val p = id?.let { PracticePieces.byId(it) }
        val sc = id?.let { PracticePieces.scoreFor(it) }
        if (p == null || sc == null) { finish(); return }
        piece = p
        score = sc

        dotOffColor = getColor(R.color.outline)
        accentColor = getColor(R.color.pipa_accent)
        primaryColor = getColor(R.color.pipa_primary)

        // 以简谱自身的拍数为权威时间轴
        totalBeats = score.totalBeats
        val bpb = score.beatsPerBar.coerceAtLeast(1)
        totalBars = ceil(totalBeats / bpb).toInt().coerceAtLeast(1)

        bpm = piece.refBpm
        b.playTitle.text = piece.title
        b.scoreKey.visibility = View.VISIBLE
        b.scoreKey.text = score.key
        b.scoreCard.visibility = View.VISIBLE
        b.jianpu.setScore(score, score.sections.map { it.name })

        dots = listOf(b.dot0, b.dot1, b.dot2, b.dot3)
        resetDots()

        b.seekBpm.max = MAX_BPM - MIN_BPM
        b.seekBpm.progress = bpm - MIN_BPM
        b.seekBpm.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, pr: Int, fromUser: Boolean) {
                if (fromUser) applyBpm(pr + MIN_BPM)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        b.btnMinus.setOnClickListener { applyBpm(bpm - 1) }
        b.btnPlus.setOnClickListener { applyBpm(bpm + 1) }

        b.btnClick.setOnClickListener {
            clickOn = !clickOn
            b.btnClick.setText(if (clickOn) R.string.play_click_on else R.string.play_click_off)
            if (running) { if (clickOn) startClick() else metronome.stop() }
        }

        b.btnStart.setOnClickListener { if (running) pause() else start() }
        b.btnReset.setOnClickListener { reset() }

        metronome.onBeat = { slotIndex, _, accent ->
            runOnUiThread { flashBeat(slotIndex, accent) }
        }

        applyBpm(bpm)
        startUiLoop()
    }

    // ---------------- 控制 ----------------

    private fun applyBpm(value: Int) {
        bpm = value.coerceIn(MIN_BPM, MAX_BPM)
        b.bpmLabel.text = getString(R.string.play_bpm_label, bpm)
        if (b.seekBpm.progress != bpm - MIN_BPM) b.seekBpm.progress = bpm - MIN_BPM
        metronome.setBpm(bpm.toDouble())
    }

    private fun start() {
        if (elapsedBeats >= totalBeats) { elapsedBeats = 0.0; lastHlBar = -1 }
        enlargeScore(true)
        runCountdown(3) { beginRun() }
    }

    private fun runCountdown(from: Int, onDone: () -> Unit) {
        b.btnStart.isEnabled = false
        b.countdown.visibility = View.VISIBLE
        countdownJob?.cancel()
        countdownJob = lifecycleScope.launch {
            for (n in from downTo 1) {
                b.countdown.text = n.toString()
                b.countdown.scaleX = 1.4f; b.countdown.scaleY = 1.4f
                b.countdown.animate().scaleX(1f).scaleY(1f).setDuration(400)
                    .setInterpolator(DecelerateInterpolator()).start()
                delay(1000)
            }
            b.countdown.visibility = View.GONE
            b.btnStart.isEnabled = true
            onDone()
        }
    }

    private fun beginRun() {
        running = true
        lastTickMs = SystemClock.elapsedRealtime()
        b.btnStart.setText(R.string.play_pause)
        if (clickOn) startClick()
    }

    private fun enlargeScore(big: Boolean) {
        val lp = b.scoreCard.layoutParams
        lp.height = (resources.displayMetrics.density * (if (big) 460f else 300f)).toInt()
        b.scoreCard.layoutParams = lp
    }

    private fun pause() {
        running = false
        countdownJob?.cancel()
        b.countdown.visibility = View.GONE
        b.btnStart.isEnabled = true
        b.btnStart.setText(R.string.btn_start)
        metronome.stop()
        resetDots()
    }

    private fun reset() {
        running = false
        countdownJob?.cancel()
        b.countdown.visibility = View.GONE
        b.btnStart.isEnabled = true
        elapsedBeats = 0.0
        lastHlBar = -1
        b.jianpu.highlightBar = -1
        enlargeScore(false)
        b.btnStart.setText(R.string.btn_start)
        metronome.stop()
        resetDots()
    }

    private fun startClick() {
        metronome.setBpm(bpm.toDouble())
        metronome.setSubdivision(1)
        metronome.setAccentFirst(true)
        if (!metronome.isRunning) metronome.start()
    }

    // ---------------- 时间推进 ----------------

    private fun startUiLoop() {
        lifecycleScope.launch {
            while (isActive) {
                if (running) {
                    val now = SystemClock.elapsedRealtime()
                    val dt = (now - lastTickMs) / 1000.0
                    lastTickMs = now
                    elapsedBeats += dt * bpm / 60.0
                    if (elapsedBeats >= totalBeats) {
                        elapsedBeats = totalBeats
                        updateHighlight()
                        onFinished()
                    } else {
                        updateHighlight()
                    }
                }
                delay(60)
            }
        }
    }

    private fun onFinished() {
        running = false
        b.btnStart.setText(R.string.play_again)
        metronome.stop()
        resetDots()
    }

    /** 当前小节 = floor(elapsedBeats / beatsPerBar)。整小节高亮。 */
    private fun updateHighlight() {
        val bpb = score.beatsPerBar.coerceAtLeast(1)
        var bar = (elapsedBeats / bpb).toInt()
        if (bar >= totalBars) bar = totalBars - 1
        if (bar != lastHlBar) {
            lastHlBar = bar
            b.jianpu.highlightBar = bar
            autoScroll()
        }
    }

    private fun autoScroll() {
        b.jianpu.post {
            val top = b.jianpu.currentTop
            if (top < 0) return@post
            val viewH = b.scoreScroll.height
            val target = (top - viewH * 0.30f).toInt().coerceAtLeast(0)
            b.scoreScroll.smoothScrollTo(0, target)
        }
    }

    // ---------------- 节拍灯 ----------------

    private fun flashBeat(beatInBar: Int, accent: Boolean) {
        for ((i, dot) in dots.withIndex()) {
            val on = i == beatInBar
            tintCircle(dot, if (!on) dotOffColor else if (accent) primaryColor else accentColor)
            if (on) {
                dot.animate().cancel()
                dot.scaleX = 1.6f; dot.scaleY = 1.6f
                dot.animate().scaleX(1f).scaleY(1f).setDuration(160)
                    .setInterpolator(DecelerateInterpolator()).start()
            } else {
                dot.animate().cancel(); dot.scaleX = 1f; dot.scaleY = 1f
            }
        }
    }

    private fun resetDots() {
        for (d in dots) { d.animate().cancel(); d.scaleX = 1f; d.scaleY = 1f; tintCircle(d, dotOffColor) }
    }

    private fun tintCircle(v: View, color: Int) {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        v.background = d
    }

    override fun onStop() {
        super.onStop()
        if (running) pause()
    }

    companion object {
        const val EXTRA_PIECE_ID = "piece_id"
        private const val MIN_BPM = 20
        private const val MAX_BPM = 220
    }
}
