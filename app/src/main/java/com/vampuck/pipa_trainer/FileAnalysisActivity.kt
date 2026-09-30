package com.vampuck.pipa_trainer

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.ScatterChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.data.*
import com.vampuck.pipa_trainer.databinding.ActivityFileAnalysisBinding
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
        if (uri == null) { finish(); return@registerForActivityResult }
        analyze(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityFileAnalysisBinding.inflate(layoutInflater)
        setContentView(b.root)
        picker.launch(arrayOf("audio/*", "video/*"))
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
            } catch (e: Exception) {
                b.status.text = "Failed: ${e.message}"
            }
        }
    }

    private fun render(res: LunzhiAnalyzer.Result) {
        b.status.text = "Analysis complete"
        val rep = Evaluation.build(
            res.strokesPerMin, res.strokesPerSec, res.cv, res.modalCv,
            res.fingerProfile, res.onsetTimes.size, res.durationSec
        )
        b.headline.text = "Grade ${rep.grade}   •   ${rep.headline}"
        val sb = StringBuilder()
        rep.bullets.forEach { sb.append("• ").append(it).append('\n') }
        sb.append('\n')
        rep.fingerBullets.forEach { sb.append("• ").append(it).append('\n') }
        sb.append("\nPer-5s windows:\n")
        res.perWindow.forEach {
            sb.append("  ${it.startSec.toInt()}-${(it.startSec + 5).toInt()}s: " +
                "n=${it.strokes}  rate=${"%.1f".format(it.ratePerSec)}/s  " +
                "CV=${"%.3f".format(it.cv)}\n")
        }
        b.report.text = sb.toString()

        drawFlux(res)
        drawIoi(res)
        drawFinger(res)
    }

    private fun drawFlux(res: LunzhiAnalyzer.Result) {
        val entries = ArrayList<Entry>()
        // subsample for performance
        val step = maxOf(1, res.fluxEnvelope.size / 2000)
        var i = 0
        while (i < res.fluxEnvelope.size) {
            entries.add(Entry(res.fluxTimes[i].toFloat(), res.fluxEnvelope[i]))
            i += step
        }
        val ds = LineDataSet(entries, "flux").apply {
            setDrawCircles(false); lineWidth = 1f
            color = 0xFF33BB66.toInt(); setDrawValues(false)
        }
        val chart = b.chartFlux
        chart.data = LineData(ds)
        chart.description.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.xAxis.setDrawGridLines(false)
        // mark onsets as vertical limit lines (cap count)
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
        val ds = ScatterDataSet(entries, "IOI (ms)").apply {
            color = 0xFF2255CC.toInt(); scatterShapeSize = 8f; setDrawValues(false)
        }
        val chart = b.chartIoi
        chart.data = ScatterData(ds)
        chart.description.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.axisLeft.axisMinimum = 0f
        chart.axisLeft.axisMaximum = 300f
        val mean = LimitLine(res.meanIoiMs.toFloat(), "mean ${res.meanIoiMs.toInt()}ms")
        mean.lineColor = 0xFFEE3333.toInt(); mean.lineWidth = 1.2f
        chart.axisLeft.removeAllLimitLines()
        chart.axisLeft.addLimitLine(mean)
        chart.invalidate()
    }

    private fun drawFinger(res: LunzhiAnalyzer.Result) {
        val entries = ArrayList<BarEntry>()
        for (p in res.fingerProfile.indices) {
            entries.add(BarEntry(p.toFloat(), (res.fingerProfile[p] * 100).toFloat()))
        }
        val ds = BarDataSet(entries, "loudness %").apply {
            color = 0xFF8D3B2E.toInt()
        }
        val chart = b.chartFinger
        chart.data = BarData(ds)
        chart.description.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.axisLeft.axisMinimum = 0f
        chart.axisLeft.axisMaximum = 110f
        chart.xAxis.granularity = 1f
        chart.invalidate()
    }
}
