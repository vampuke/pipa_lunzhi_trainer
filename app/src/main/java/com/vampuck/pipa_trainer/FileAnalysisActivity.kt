package com.vampuck.pipa_trainer

import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import com.vampuck.pipa_trainer.databinding.ActivityFileAnalysisBinding
import com.vampuck.pipa_trainer.databinding.ItemDimensionBinding
import com.vampuck.pipa_trainer.dsp.AudioDecoder
import com.vampuck.pipa_trainer.dsp.Evaluation
import com.vampuck.pipa_trainer.dsp.LunzhiAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

class FileAnalysisActivity : AppCompatActivity() {

    private lateinit var b: ActivityFileAnalysisBinding
    private var lastResult: LunzhiAnalyzer.Result? = null
    private var currentUri: Uri? = null

    // segment bounds in ANALYSIS seconds
    private var segStart = 0.0
    private var segEnd = 0.0

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var userSeekingPlay = false
    private var playhead: LimitLine? = null
    private var mediaDurMs = 0        // MediaPlayer's duration
    private var decodedDurSec = 0.0   // analysis timeline duration

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            if (::b.isInitialized) b.status.text = "未选择文件，可重新选择"
            return@registerForActivityResult
        }
        analyze(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityFileAnalysisBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnPick.setOnClickListener { launchPicker() }
        b.btnSegment.setOnClickListener { applySegment() }
        b.btnPlay.setOnClickListener { togglePlay() }
        b.seekPlay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                userSeekingPlay = true
                val t = segStart + (segEnd - segStart) * p / 1000.0
                seekMediaTo(t)
                movePlayhead(t)
                updateTimeText(t)
                userSeekingPlay = false
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        b.seekSegStart.setOnSeekBarChangeListener(segListener)
        b.seekSegEnd.setOnSeekBarChangeListener(segListener)
        launchPicker()
    }

    private val segListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
            if (fromUser) updateSegRangeText()
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {
            // released a slider -> refresh range + charts for the new segment
            applySegment()
        }
    }

    private fun launchPicker() {
        b.root.post { picker.launch(arrayOf("audio/*", "video/*")) }
    }

    private fun analyze(uri: Uri) {
        currentUri = uri
        b.status.text = getString(R.string.analyzing)
        lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.Default) {
                    val pcm = AudioDecoder.decode(this@FileAnalysisActivity, uri)
                    LunzhiAnalyzer.analyze(pcm.samples, pcm.sampleRate, pcm.containerDurationSec)
                }
                render(res)
            } catch (oom: OutOfMemoryError) {
                b.status.text = "分析失败：文件太长，内存不足。请截取较短的音频/视频再试。"
            } catch (t: Throwable) {
                b.status.text = "分析失败：${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    private fun render(res: LunzhiAnalyzer.Result) {
        lastResult = res
        decodedDurSec = res.durationSec
        b.status.text = getString(R.string.analysis_done)
        val rep = Evaluation.build(res.metrics)

        b.scoreCard.visibility = View.VISIBLE
        b.scoreNum.text = rep.score.toString()
        b.scoreGrade.text = rep.grade
        b.headline.text = rep.headline
        (b.scoreCard.getChildAt(0) as LinearLayout).background =
            GradientDrawable().apply { setColor(Evaluation.levelColor(rep.level)) }

        b.dimRow.removeAllViews()
        for (d in rep.dimensions) {
            val item = ItemDimensionBinding.inflate(LayoutInflater.from(this), b.dimRow, false)
            item.dimTitle.text = d.title
            item.dimValue.text = d.valueText
            item.dimGrade.text = d.grade
            item.dimGrade.background = GradientDrawable().apply {
                cornerRadius = 24f; setColor(Evaluation.levelColor(d.level))
            }
            item.dimSub.text = d.subText
            b.dimRow.addView(item.root)
        }

        b.summaryCard.visibility = View.VISIBLE
        b.summaryText.text = rep.summary
        b.adviceText.text = rep.advice.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")

        b.segCard.visibility = View.VISIBLE
        b.tvSegResult.text = ""
        // default segment = whole file
        segStart = 0.0; segEnd = res.durationSec
        b.seekSegStart.progress = 0
        b.seekSegEnd.progress = 1000
        updateSegRangeText()

        setupPlayback()
        drawFlux(segStart, segEnd)
        drawIoi(segStart, segEnd)
        drawFinger(res.metrics)
    }

    // ---------------- segment ----------------
    private fun segTimes(): Pair<Double, Double> {
        val dur = lastResult?.durationSec ?: return 0.0 to 0.0
        var s = dur * b.seekSegStart.progress / 1000.0
        var e = dur * b.seekSegEnd.progress / 1000.0
        if (e < s) { val t = s; s = e; e = t }
        if (e - s < 0.5) e = (s + 0.5).coerceAtMost(dur)
        return s to e
    }

    private fun updateSegRangeText() {
        val (s, e) = segTimes()
        b.tvSegRange.text = "起点 %.1fs　终点 %.1fs　（共 %.1fs）".format(s, e, (e - s).coerceAtLeast(0.0))
    }

    private fun applySegment() {
        val res = lastResult ?: return
        val (s, e) = segTimes()
        segStart = s; segEnd = e
        updateSegRangeText()

        val m = LunzhiAnalyzer.metrics(res.onsetTimes, res.strokeAmp, s, e)
        if (m.strokes < 4) {
            b.tvSegResult.text = "该段只有 ${m.strokes} 击，样本太少，无法评估。请选更长的区间。"
        } else {
            val rep = Evaluation.build(m)
            val sb = StringBuilder()
            sb.append("该段：${m.strokes} 击 · ${m.strokesPerMin.roundToInt()} 音/分 · ")
            sb.append("时值抖动 ≈ ${m.jitterPct.roundToInt()}%（±${m.stdIoiMs.roundToInt()}ms）\n")
            sb.append("评级 ${rep.grade}（${rep.score} 分） · 主带 CV ${"%.3f".format(m.modalCv)} · 异常间隔 ${m.outlierCount} 处\n")
            sb.append(rep.summary)
            b.tvSegResult.text = sb.toString()
        }
        // charts follow the segment
        drawFlux(s, e)
        drawIoi(s, e)
        drawFinger(m)
        // rewind playback to the segment start
        try { if (player?.isPlaying == true) { player?.pause(); b.btnPlay.text = "▶ 播放" } } catch (_: Throwable) {}
        seekMediaTo(segStart)
        movePlayhead(segStart)
        updateTimeText(segStart)
    }

    // ---------------- playback ----------------
    private fun setupPlayback() {
        releasePlayer()
        val uri = currentUri ?: return
        try {
            val mp = MediaPlayer()
            b.btnPlay.isEnabled = false
            mp.setDataSource(this, uri)
            mp.setOnPreparedListener {
                b.btnPlay.isEnabled = true
                b.btnPlay.text = "▶ 播放"
                mediaDurMs = try { it.duration } catch (_: Throwable) { 0 }
                showDurationNote()
                updateTimeText(segStart)
            }
            mp.setOnCompletionListener {
                b.btnPlay.text = "▶ 播放"
                handler.removeCallbacks(tick)
            }
            mp.prepareAsync()
            player = mp
        } catch (t: Throwable) {
            b.btnPlay.isEnabled = false
            b.tvTime.text = "无法播放该格式"
        }
    }

    /** Warn if the player's duration disagrees with our decoded timeline. */
    private fun showDurationNote() {
        if (mediaDurMs <= 0 || decodedDurSec <= 0) return
        val mediaSec = mediaDurMs / 1000.0
        if (abs(mediaSec - decodedDurSec) > 0.15 * decodedDurSec) {
            b.durationNote.visibility = View.VISIBLE
            b.durationNote.text = "注意：播放器时长 %.0fs，解析时长 %.0fs，两者不一致；进度与波形按解析时间对齐。"
                .format(mediaSec, decodedDurSec)
        }
    }

    /** Analysis-time (s) -> media position (ms), tolerant of a duration mismatch. */
    private fun analysisToMediaMs(t: Double): Int {
        val ratio = if (decodedDurSec > 0 && mediaDurMs > 0) mediaDurMs / 1000.0 / decodedDurSec else 1.0
        return (t * ratio * 1000).toInt().coerceAtLeast(0)
    }

    private fun mediaMsToAnalysis(ms: Int): Double {
        val ratio = if (decodedDurSec > 0 && mediaDurMs > 0) mediaDurMs / 1000.0 / decodedDurSec else 1.0
        return if (ratio > 0) ms / 1000.0 / ratio else 0.0
    }

    private fun seekMediaTo(tAnalysis: Double) {
        try { player?.seekTo(analysisToMediaMs(tAnalysis)) } catch (_: Throwable) {}
    }

    private fun togglePlay() {
        val mp = player ?: return
        try {
            if (mp.isPlaying) {
                mp.pause(); b.btnPlay.text = "▶ 播放"; handler.removeCallbacks(tick)
            } else {
                val cur = mediaMsToAnalysis(mp.currentPosition)
                if (cur < segStart || cur >= segEnd) mp.seekTo(analysisToMediaMs(segStart))
                mp.start(); b.btnPlay.text = "⏸ 暂停"; handler.post(tick)
            }
        } catch (_: Throwable) {}
    }

    private val tick = object : Runnable {
        override fun run() {
            val mp = player ?: return
            try {
                if (mp.isPlaying && !userSeekingPlay) {
                    val t = mediaMsToAnalysis(mp.currentPosition)
                    val len = (segEnd - segStart).coerceAtLeast(0.001)
                    b.seekPlay.progress = ((t - segStart) / len * 1000).toInt().coerceIn(0, 1000)
                    movePlayhead(t)
                    updateTimeText(t)
                    if (t >= segEnd) { mp.pause(); b.btnPlay.text = "▶ 播放" }
                }
            } catch (_: Throwable) {}
            handler.postDelayed(this, 200)
        }
    }

    private fun movePlayhead(tAnalysis: Double) {
        val axis = b.chartFlux.xAxis
        playhead?.let { axis.removeLimitLine(it) }
        val ph = LimitLine(tAnalysis.toFloat())
        ph.lineColor = 0xCC2255CC.toInt(); ph.lineWidth = 2f
        axis.addLimitLine(ph)
        playhead = ph
        b.chartFlux.invalidate()
    }

    private fun updateTimeText(tAnalysis: Double) {
        val rel = (tAnalysis - segStart).coerceAtLeast(0.0)
        val len = (segEnd - segStart).coerceAtLeast(0.0)
        b.tvTime.text = "${fmt(rel)} / ${fmt(len)}"
    }

    private fun fmt(sec: Double): String {
        val s = sec.toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun releasePlayer() {
        handler.removeCallbacksAndMessages(null)
        try { player?.release() } catch (_: Throwable) {}
        player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    override fun onStop() {
        super.onStop()
        try { if (player?.isPlaying == true) { player?.pause(); b.btnPlay.text = "▶ 播放" } } catch (_: Throwable) {}
        handler.removeCallbacks(tick)
    }

    // ---------------- charts ----------------
    private fun fluxAt(res: LunzhiAnalyzer.Result, t: Double): Float {
        val fps = res.sampleRate.toDouble() / LunzhiAnalyzer.HOP
        val i = (t * fps).roundToInt().coerceIn(0, res.fluxEnvelope.size - 1)
        return res.fluxEnvelope[i]
    }

    private fun drawFlux(s: Double, e: Double) {
        val res = lastResult ?: return
        val entries = ArrayList<Entry>()
        val step = maxOf(1, res.fluxEnvelope.size / 4000)
        var i = 0
        while (i < res.fluxEnvelope.size) {
            val t = res.fluxTimes[i]
            if (t in s..e) entries.add(Entry(t.toFloat(), res.fluxEnvelope[i]))
            i += step
        }
        val line = LineDataSet(entries, "波形").apply {
            setDrawCircles(false); lineWidth = 1f
            color = 0xFF33BB66.toInt(); setDrawValues(false)
        }
        // onset markers as a second dataset with an invisible line -> dots spread
        // naturally across the whole range (no stacking like limit lines did)
        val marks = ArrayList<Entry>()
        for (t in res.onsetTimes) {
            if (t >= s && t <= e) marks.add(Entry(t.toFloat(), fluxAt(res, t) * 1.04f))
        }
        val markSet = LineDataSet(marks, "击").apply {
            color = 0x00000000
            lineWidth = 0f
            setDrawCircles(true)
            circleRadius = 2.5f
            setCircleColor(0xFF9933DD.toInt())
            setDrawFilled(false)
            setDrawValues(false)
        }
        val chart = b.chartFlux
        chart.data = LineData(line, markSet)
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.xAxis.setDrawGridLines(false)
        chart.xAxis.removeAllLimitLines()
        val ph = LimitLine(s.toFloat())
        ph.lineColor = 0xCC2255CC.toInt(); ph.lineWidth = 2f
        chart.xAxis.addLimitLine(ph)
        playhead = ph
        b.lblFlux.text = "起音波形与检测到的每一击（%.1f–%.1fs）".format(s, e)
        chart.invalidate()
    }

    private fun drawIoi(s: Double, e: Double) {
        val res = lastResult ?: return
        val entries = ArrayList<Entry>()
        val onsets = res.onsetTimes
        var maxY = 0.0
        for (k in 1 until onsets.size) {
            val d = onsets[k] - onsets[k - 1]
            if (d in LunzhiAnalyzer.MIN_IOI..LunzhiAnalyzer.MAX_IOI && onsets[k] >= s && onsets[k] <= e) {
                entries.add(Entry(onsets[k].toFloat(), (d * 1000).toFloat()))
                if (d * 1000 > maxY) maxY = d * 1000
            }
        }
        val m = LunzhiAnalyzer.metrics(onsets, res.strokeAmp, s, e)
        val ds = ScatterDataSet(entries, "间隔").apply {
            color = 0xFF2255CC.toInt(); scatterShapeSize = 8f; setDrawValues(false)
        }
        val chart = b.chartIoi
        chart.data = ScatterData(ds)
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.axisLeft.axisMinimum = 0f
        chart.axisLeft.axisMaximum = (maxY * 1.1).coerceAtLeast(50.0).toFloat()
        val mean = LimitLine(m.meanIoiMs.toFloat(), "平均 ${m.meanIoiMs.roundToInt()}ms")
        mean.lineColor = 0xFFEE3333.toInt(); mean.lineWidth = 1.2f
        chart.axisLeft.removeAllLimitLines()
        chart.axisLeft.addLimitLine(mean)
        b.lblIoi.text = "相邻音间隔随时间变化（%.1f–%.1fs，越平越匀）".format(s, e)
        chart.invalidate()
    }

    private fun drawFinger(m: LunzhiAnalyzer.Metrics) {
        val names = arrayOf("第1击", "第2击", "第3击", "第4击", "第5击")
        val entries = ArrayList<BarEntry>()
        for (p in m.positionProfile.indices) {
            entries.add(BarEntry(p.toFloat(), (m.positionProfile[p] * 100).toFloat()))
        }
        val ds = BarDataSet(entries, "力度%").apply { color = 0xFF8D3B2E.toInt() }
        val chart = b.chartFinger
        chart.data = BarData(ds)
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.axisLeft.axisMinimum = 0f
        chart.axisLeft.axisMaximum = 110f
        chart.xAxis.granularity = 1f
        chart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String = names.getOrElse(value.toInt()) { "" }
        }
        b.lblFinger.text = "轮内各位力度（该段，以第1击为第1位 %）"
        chart.invalidate()
    }
}
