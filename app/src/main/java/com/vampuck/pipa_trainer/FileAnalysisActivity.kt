package com.vampuck.pipa_trainer

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
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

class FileAnalysisActivity : AppCompatActivity() {

    private lateinit var b: ActivityFileAnalysisBinding

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        // User cancelled — don't kill the screen; let them pick again.
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
        launchPicker()
    }

    private fun launchPicker() {
        // posting avoids firing the picker before the activity is RESUMED,
        // which on some ROMs returns an immediate null/cancel.
        b.root.post { picker.launch(arrayOf("audio/*", "video/*")) }
    }

    private fun analyze(uri: Uri) {
        b.status.text = getString(R.string.analyzing)
        lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.Default) {
                    val pcm = AudioDecoder.decode(this@FileAnalysisActivity, uri)
                    LunzhiAnalyzer.analyze(pcm.samples, pcm.sampleRate)
                }
                render(res)
            } catch (oom: OutOfMemoryError) {
                // OOM is an Error, not an Exception — must be caught explicitly.
                b.status.text = "分析失败：文件太长，内存不足。请截取一段较短的音频/视频再试。"
            } catch (t: Throwable) {
                b.status.text = "分析失败：${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    private fun render(res: LunzhiAnalyzer.Result) {
        b.status.text = getString(R.string.analysis_done)
        val rep = Evaluation.build(
            res.strokesPerMin, res.strokesPerSec, res.cv, res.modalCv,
            res.fingerProfile, res.onsetTimes.size, res.durationSec, res.perWindow
        )

        // 综合评分卡
        b.scoreCard.visibility = android.view.View.VISIBLE
        b.scoreNum.text = rep.score.toString()
        b.scoreGrade.text = rep.grade
        b.headline.text = rep.headline
        (b.scoreCard.getChildAt(0) as LinearLayout).background =
            GradientDrawable().apply { setColor(Evaluation.levelColor(rep.level)) }

        // 维度卡
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

        // 总结 + 建议
        b.summaryCard.visibility = android.view.View.VISIBLE
        b.summaryText.text = rep.summary
        b.adviceText.text = rep.advice.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")

        drawFlux(res)
        drawIoi(res)
        drawFinger(res)
    }

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
            ll.lineColor = 0x55EE3333.toInt(); ll.lineWidth = 0.6f
            chart.xAxis.addLimitLine(ll)
        }
        chart.invalidate()
    }

    private fun drawIoi(res: LunzhiAnalyzer.Result) {
        val entries = ArrayList<Entry>()
        for (k in 1 until res.onsetTimes.size) {
            val d = (res.onsetTimes[k] - res.onsetTimes[k - 1])
            if (d in LunzhiAnalyzer.MIN_IOI..LunzhiAnalyzer.MAX_IOI) {
                entries.add(Entry(res.onsetTimes[k].toFloat(), (d * 1000).toFloat()))
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
        val mean = LimitLine(res.meanIoiMs.toFloat(), "平均 ${res.meanIoiMs.toInt()}ms")
        mean.lineColor = 0xFFEE3333.toInt(); mean.lineWidth = 1.2f
        chart.axisLeft.removeAllLimitLines()
        chart.axisLeft.addLimitLine(mean)
        chart.invalidate()
    }

    private fun drawFinger(res: LunzhiAnalyzer.Result) {
        val names = arrayOf("食指", "中指", "名指", "小指", "挑")
        val entries = ArrayList<BarEntry>()
        for (p in res.fingerProfile.indices) {
            entries.add(BarEntry(p.toFloat(), (res.fingerProfile[p] * 100).toFloat()))
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
            override fun getFormattedValue(value: Float): String =
                names.getOrElse(value.toInt()) { "" }
        }
        chart.invalidate()
    }
}
