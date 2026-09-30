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
import kotlin.math.roundToInt

class FileAnalysisActivity : AppCompatActivity() {

    private lateinit var b: ActivityFileAnalysisBinding
    private var lastResult: LunzhiAnalyzer.Result? = null
    private var currentUri: Uri? = null

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var userSeekingPlay = false
    private var playhead: LimitLine? = null

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
        b.btnSegment.setOnClickListener { runSegmentAnalysis() }
        b.btnPlay.setOnClickListener { togglePlay() }
        b.seekPlay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                userSeekingPlay = true
                val mp = player ?: return
                val dur = try { mp.duration } catch (_: Throwable) { -1 }
                if (dur > 0) mp.seekTo((dur * p / 1000.0).toInt())
                updateTimeText()
                movePlayheadFromProgress(p)
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
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = updateSegRangeText()
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
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
                    LunzhiAnalyzer.analyze(pcm.samples, pcm.sampleRate)
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

        setupSegControls(res.durationSec)
        setupPlayback(res.durationSec)

        b.tvSegResult.text = ""
        drawFlux(res)
        drawIoi(res)
        drawFinger(res)
    }

    // ---------------- playback ----------------
    private fun setupPlayback(dur: Double) {
        b.playCard.visibility = View.VISIBLE
        releasePlayer()
        val uri = currentUri ?: return
        try {
            val mp = MediaPlayer()
            b.btnPlay.isEnabled = false
            b.tvTime.text = "加载中…"
            mp.setDataSource(this, uri)
            mp.setOnPreparedListener {
                b.btnPlay.isEnabled = true
                b.btnPlay.text = "▶ 播放"
                updateTimeText()
            }
            mp.prepareAsync()
            player = mp
        } catch (t: Throwable) {
            b.btnPlay.isEnabled = false
            b.tvTime.text = "无法播放该格式"
        }
    }

    private fun togglePlay() {
        val mp = player ?: return
        try {
            if (mp.isPlaying) {
                mp.pause(); b.btnPlay.text = "▶ 播放"; handler.removeCallbacks(tick)
            } else {
                mp.start(); b.btnPlay.text = "⏸ 暂停"; handler.post(tick)
            }
        } catch (_: Throwable) {}
    }

    private val tick = object : Runnable {
        override fun run() {
            val mp = player ?: return
            try {
                if (mp.isPlaying && !userSeekingPlay) {
                    val dur = mp.duration
                    if (dur > 0) {
                        val p = (mp.currentPosition * 1000.0 / dur).toInt().coerceIn(0, 1000)
                        b.seekPlay.progress = p
                        movePlayheadFromProgress(p)
                    }
                    updateTimeText()
                }
            } catch (_: Throwable) {}
            handler.postDelayed(this, 200)
        }
    }

    private fun movePlayheadFromProgress(p: Int) {
        val res = lastResult ?: return
        val t = res.durationSec * p / 1000.0
        val axis = b.chartFlux.xAxis
        playhead?.let { axis.removeLimitLine(it) }
        val ph = LimitLine(t.toFloat())
        ph.lineColor = 0xCC2255CC.toInt(); ph.lineWidth = 2f
        axis.addLimitLine(ph)
        playhead = ph
        b.chartFlux.invalidate()
    }

    private fun updateTimeText() {
        val mp = player ?: return
        try {
            val dur = mp.duration
            val cur = mp.currentPosition
            b.tvTime.text = "${fmt(cur)} / ${fmt(dur)}"
        } catch (_: Throwable) {}
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
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

    // ---------------- segment ----------------
    private fun setupSegControls(dur: Double) {
        b.segCard.visibility = View.VISIBLE
        b.seekSegStart.progress = 0
        b.seekSegEnd.progress = 1000
        b.tvSegResult.text = ""
        updateSegRangeText()
    }

    private fun segTimes(): Pair<Double, Double> {
        val res = lastResult ?: return 0.0 to 0.0
        var s = res.durationSec * b.seekSegStart.progress / 1000.0
        var e = res.durationSec * b.seekSegEnd.progress / 1000.0
        if (e < s) { val t = s; s = e; e = t }
        if (e - s < 0.5) e = (s + 0.5).coerceAtMost(res.durationSec)
        return s to e
    }

    private fun updateSegRangeText() {
        val (s, e) = segTimes()
        b.tvSegRange.text = "起点 %.1fs　终点 %.1fs　（共 %.1fs）".format(s, e, (e - s).coerceAtLeast(0.0))
    }

    private fun runSegmentAnalysis() {
        val res = lastResult ?: return
        val (s, e) = segTimes()
        val m = LunzhiAnalyzer.metrics(res.onsetTimes, res.strokeAmp, s, e)
        val rep = Evaluation.build(m)
        if (m.strokes < 4) {
            b.tvSegResult.text = "该段只有 ${m.strokes} 击，样本太少，无法评估。请选更长的区间。"
            return
        }
        val sb = StringBuilder()
        sb.append("该段：${m.strokes} 击 · ${m.strokesPerMin.roundToInt()} 音/分 · ")
        sb.append("时值抖动 ≈ ${m.jitterPct.roundToInt()}%（±${m.stdIoiMs.roundToInt()}ms）\n")
        sb.append("评级 ${rep.grade}（${rep.score} 分） · 主带 CV ${"%.3f".format(m.modalCv)} · 异常间隔 ${m.outlierCount} 处\n")
        sb.append(rep.summary)
        b.tvSegResult.text = sb.toString()
    }

    // ---------------- charts ----------------
    private fun drawFlux(res: LunzhiAnalyzer.Result) {
        val entries = ArrayList<Entry>()
        val step = maxOf(1, res.fluxEnvelope.size / 2000)
        var i = 0
        while (i < res.fluxEnvelope.size) {
            entries.add(Entry(res.fluxTimes[i].toFloat(), res.fluxEnvelope[i]))
            i += step
        }
        val ds = LineDataSet(entries, "波形").apply {
            setDrawCircles(false); lineWidth = 1f
            color = 0xFF33BB66.toInt(); setDrawValues(false)
        }
        val chart = b.chartFlux
        chart.data = LineData(ds)
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.xAxis.setDrawGridLines(false)
        chart.xAxis.removeAllLimitLines()
        val cap = minOf(res.onsetTimes.size, 400)
        for (k in 0 until cap) {
            val ll = LimitLine(res.onsetTimes[k].toFloat())
            ll.lineColor = 0x44EE3333.toInt(); ll.lineWidth = 0.6f
            chart.xAxis.addLimitLine(ll)
        }
        val ph = LimitLine(0f)
        ph.lineColor = 0xCC2255CC.toInt(); ph.lineWidth = 2f
        chart.xAxis.addLimitLine(ph)
        playhead = ph
        chart.invalidate()
    }

    private fun drawIoi(res: LunzhiAnalyzer.Result) {
        val entries = ArrayList<Entry>()
        val onsets = res.onsetTimes
        for (k in 1 until onsets.size) {
            val d = onsets[k] - onsets[k - 1]
            if (d in LunzhiAnalyzer.MIN_IOI..LunzhiAnalyzer.MAX_IOI) {
                entries.add(Entry(onsets[k].toFloat(), (d * 1000).toFloat()))
            }
        }
        val ds = ScatterDataSet(entries, "间隔").apply {
            color = 0xFF2255CC.toInt(); scatterShapeSize = 8f; setDrawValues(false)
        }
        val chart = b.chartIoi
        chart.data = ScatterData(ds)
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.axisLeft.axisMinimum = 0f
        chart.axisLeft.axisMaximum = 300f
        val mean = LimitLine(res.metrics.meanIoiMs.toFloat(), "平均 ${res.metrics.meanIoiMs.roundToInt()}ms")
        mean.lineColor = 0xFFEE3333.toInt(); mean.lineWidth = 1.2f
        chart.axisLeft.removeAllLimitLines()
        chart.axisLeft.addLimitLine(mean)
        chart.invalidate()
    }

    private fun drawFinger(res: LunzhiAnalyzer.Result) {
        val names = arrayOf("食指", "中指", "名指", "小指", "挑")
        val entries = ArrayList<BarEntry>()
        for (p in res.metrics.fingerProfile.indices) {
            entries.add(BarEntry(p.toFloat(), (res.metrics.fingerProfile[p] * 100).toFloat()))
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
        chart.invalidate()
    }
}
