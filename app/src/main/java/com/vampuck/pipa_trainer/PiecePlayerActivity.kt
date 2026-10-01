package com.vampuck.pipa_trainer

import android.animation.ArgbEvaluator
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
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
import com.vampuck.pipa_trainer.databinding.ItemSectionRowBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 名曲「跟练」播放页。
 *
 * 核心：按预设/用户调整的 BPM，把曲目的段落拍数换算成时间轴，点「开始」后
 * 按真实时间推进，实时显示：当前段落、段落序号、整体进度条、当前段进度条、
 * 剩余时间、下一段提示，并同步四拍节拍灯（可选节拍器声）。
 *
 * 时间轴用 [SystemClock.elapsedRealtime] 计时（不随 UI 帧率漂移），每段边界用
 * 累计拍数换算；改变 BPM 会即时按「已完成比例」重算，不跳进度。
 */
class PiecePlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPiecePlayerBinding
    private lateinit var piece: PracticePiece

    private val metronome = Metronome()
    private var clickOn = true

    private var bpm = 60
    // 段落边界（累计拍数），sectionEndBeat[i] = 到第 i 段结束为止的累计拍数
    private lateinit var sectionEndBeat: IntArray

    // 计时：以“已完成拍数”为核心状态，暂停/变速都围绕它
    private var running = false
    private var elapsedBeats = 0.0          // 已完成拍数（含小数）
    private var lastTickMs = 0L
    private lateinit var dots: List<View>
    private val argb = ArgbEvaluator()
    private lateinit var sectionRows: List<ItemSectionRowBinding>

    private var dotOffColor = 0
    private var accentColor = 0
    private var primaryColor = 0

    // 简谱（有谱才非空）：按拍数定位的音符起始拍，用于高亮当前音符
    private var score: JScore? = null
    private var noteStartBeats: DoubleArray = DoubleArray(0)
    private var lastHlIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPiecePlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        val id = intent.getStringExtra(EXTRA_PIECE_ID)
        val p = id?.let { PracticePieces.byId(it) }
        if (p == null) { finish(); return }
        piece = p

        dotOffColor = getColor(R.color.outline)
        accentColor = getColor(R.color.pipa_accent)
        primaryColor = getColor(R.color.pipa_primary)

        bpm = piece.refBpm
        // 段落累计拍数
        sectionEndBeat = IntArray(piece.sections.size)
        var acc = 0
        for (i in piece.sections.indices) {
            acc += piece.sections[i].beats
            sectionEndBeat[i] = acc
        }

        b.playTitle.text = piece.title
        dots = listOf(b.dot0, b.dot1, b.dot2, b.dot3)
        resetDots()
        buildSectionList()
        setupScore()

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

        metronome.onBeat = { beatInBar, accent, _ ->
            runOnUiThread { flashBeat(beatInBar, accent) }
        }

        applyBpm(bpm)
        renderProgress()   // 初始 0 进度
        startUiLoop()
    }

    // ---------------- 控制 ----------------

    private fun applyBpm(value: Int) {
        bpm = value.coerceIn(MIN_BPM, MAX_BPM)
        b.bpmLabel.text = getString(R.string.play_bpm_label, bpm)
        if (b.seekBpm.progress != bpm - MIN_BPM) b.seekBpm.progress = bpm - MIN_BPM
        metronome.setBpm(bpm.toDouble())
        renderProgress()   // 变速立即刷新时间显示（进度按拍数不跳）
    }

    private fun start() {
        if (elapsedBeats >= piece.totalBeats) elapsedBeats = 0.0
        // 放大曲谱区域（有谱时），再倒数三秒开始
        if (score != null) {
            enlargeScore(true)
            runCountdown(3) { beginRun() }
        } else {
            beginRun()
        }
    }

    private var countdownJob: kotlinx.coroutines.Job? = null

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

    /** 放大/还原曲谱卡片高度，让跟练时谱面更大。 */
    private fun enlargeScore(big: Boolean) {
        val lp = b.scoreCard.layoutParams
        lp.height = (resources.displayMetrics.density * (if (big) 420f else 240f)).toInt()
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
        enlargeScore(false)
        b.btnStart.setText(R.string.btn_start)
        metronome.stop()
        resetDots()
        renderProgress()
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
                    if (elapsedBeats >= piece.totalBeats) {
                        elapsedBeats = piece.totalBeats.toDouble()
                        renderProgress()
                        onFinished()
                    } else {
                        renderProgress()
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

    // ---------------- 渲染 ----------------

    private fun renderProgress() {
        val total = piece.totalBeats
        val done = elapsedBeats.coerceIn(0.0, total.toDouble())

        // 当前段落：第一个累计拍数 > done 的段
        var idx = sectionEndBeat.indexOfFirst { done < it }
        if (idx < 0) idx = piece.sections.lastIndex
        val sec = piece.sections[idx]
        val secStartBeat = if (idx == 0) 0 else sectionEndBeat[idx - 1]
        val secBeats = sec.beats.coerceAtLeast(1)
        val secDone = (done - secStartBeat).coerceIn(0.0, secBeats.toDouble())

        b.curSection.text = sec.name
        b.sectionIndex.text = getString(R.string.play_section_idx, idx + 1, piece.sections.size)

        // 时间：按当前 bpm 把拍数换算成秒
        val totalSec = (total * 60.0 / bpm).toInt()
        val doneSec = (done * 60.0 / bpm).toInt()
        b.clock.text = "${fmt(doneSec)} / ${fmt(totalSec)}"

        b.overallBar.progress = ((done / total) * 1000).toInt()
        b.sectionBar.progress = ((secDone / secBeats) * 1000).toInt()

        updateScoreHighlight(done / total)

        // 下一段
        if (idx < piece.sections.lastIndex) {
            b.nextSection.text = getString(R.string.play_next, piece.sections[idx + 1].name)
        } else {
            b.nextSection.setText(R.string.play_next_last)
        }

        // 段落清单高亮当前段
        for ((i, row) in sectionRows.withIndex()) {
            val active = i == idx
            row.rowName.setTextColor(if (active) primaryColor else getColor(R.color.text_primary))
            tintCircle(row.rowDot, if (active) accentColor else dotOffColor)
        }
    }

    private fun buildSectionList() {
        val inflater = LayoutInflater.from(this)
        val rows = ArrayList<ItemSectionRowBinding>()
        var accBeat = 0
        for (s in piece.sections) {
            val row = ItemSectionRowBinding.inflate(inflater, b.sectionList, false)
            row.rowName.text = s.name
            accBeat += s.beats
            // 该段结束时间点（以参考起点，按当前初始 bpm）
            row.rowDur.text = fmt((s.beats * 60.0 / bpm).toInt())
            b.sectionList.addView(row.root)
            rows.add(row)
        }
        sectionRows = rows
    }

    // ---------------- 简谱 ----------------

    private fun setupScore() {
        val sc = PracticePieces.scoreFor(piece.id)
        score = sc
        if (sc == null) return

        // 计算每个音符的起始拍（flatNotes 顺序）
        val notes = sc.flatNotes
        noteStartBeats = DoubleArray(notes.size)
        var acc = 0.0
        for (i in notes.indices) { noteStartBeats[i] = acc; acc += notes[i].dur }

        b.scoreLabel.visibility = View.VISIBLE
        b.scoreKey.visibility = View.VISIBLE
        b.scoreCard.visibility = View.VISIBLE
        b.scoreKey.text = sc.key
        b.jianpu.setScore(sc, sc.sections.map { it.name })
    }

    /**
     * 简谱的时间轴可能和「段落结构进度」不同（段落 beats 是粗估，简谱是精确拍数）。
     * 有谱时，用简谱总拍数把 elapsedBeats 的完成比例映射到谱内拍，定位当前音符。
     */
    private fun updateScoreHighlight(doneRatio: Double) {
        val sc = score ?: return
        if (noteStartBeats.isEmpty()) return
        val scoreBeat = doneRatio.coerceIn(0.0, 1.0) * sc.totalBeats
        // 最后一个 start <= scoreBeat 的音符
        var idx = noteStartBeats.indexOfLast { it <= scoreBeat + 1e-6 }
        if (idx < 0) idx = 0
        if (idx != lastHlIndex) {
            lastHlIndex = idx
            b.jianpu.highlightIndex = idx
            autoScrollToCurrent()
        }
    }

    /** 让当前高亮音符所在行滚动到可视区「中间靠上」(约 33%) 处。 */
    private fun autoScrollToCurrent() {
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

    private fun fmt(totalSec: Int): String {
        val m = totalSec / 60
        val s = totalSec % 60
        return "%02d:%02d".format(m, s)
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
