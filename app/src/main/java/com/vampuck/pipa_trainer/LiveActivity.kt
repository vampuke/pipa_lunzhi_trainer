package com.vampuck.pipa_trainer

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.vampuck.pipa_trainer.audio.Metronome
import com.vampuck.pipa_trainer.databinding.ActivityLiveBinding
import com.vampuck.pipa_trainer.dsp.Evaluation
import com.vampuck.pipa_trainer.dsp.StreamingAnalyzer
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class LiveActivity : AppCompatActivity(), Metronome.Listener {

    private lateinit var b: ActivityLiveBinding
    private val sampleRate = 44100
    @Volatile private var recording = false
    private var recordThread: Thread? = null
    @Volatile private var analyzer: StreamingAnalyzer? = null
    private val metronome = Metronome()

    private val permReq = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) start() else
            Toast.makeText(this, R.string.need_mic, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLiveBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnToggle.setOnClickListener { if (recording) stop() else ensurePermThenStart() }

        metronome.listener = this
        b.switchMetro.setOnCheckedChangeListener { _, on ->
            if (recording) { if (on) metronome.start() else metronome.stop() }
        }
        b.seekBpm.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                val v = p + 20
                b.valMetroBpm.text = v.toString()
                metronome.setBpm(v.toDouble())
                analyzer?.setMetronomeBeat(60.0 / v)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        metronome.setBpm(80.0)

        b.seekSens.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                analyzer?.setSensitivity(p)
                b.valSens.text = sensLabel(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        b.valSens.text = sensLabel(b.seekSens.progress)
    }

    private fun sensLabel(p: Int) = when (p) {
        0 -> "严格"
        2 -> "灵敏"
        else -> "标准"
    }

    private fun ensurePermThenStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) start()
        else permReq.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun start() {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, sampleRate / 4) * 2
        // MIC = unprocessed capture (no speech noise-suppression / AGC that
        // would flatten instrument transients). Metronome bleed is handled by
        // timestamp masking in the analyzer, not by the platform AEC.
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (e: SecurityException) {
            Toast.makeText(this, R.string.need_mic, Toast.LENGTH_LONG).show(); return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Toast.makeText(this, "麦克风初始化失败", Toast.LENGTH_LONG).show(); return
        }

        val an = StreamingAnalyzer(sampleRate)
        an.setSensitivity(b.seekSens.progress)
        an.setMetronomeBeat(60.0 / (b.seekBpm.progress + 20))
        analyzer = an
        b.finalReport.text = ""
        b.btnToggle.setText(R.string.btn_stop)
        recording = true
        recorder.startRecording()

        if (b.switchMetro.isChecked) metronome.start()

        recordThread = thread(name = "mic") {
            val shortBuf = ShortArray(sampleRate / 10)
            val floatBuf = FloatArray(shortBuf.size)
            while (recording) {
                val n = recorder.read(shortBuf, 0, shortBuf.size)
                if (n > 0) {
                    for (i in 0 until n) floatBuf[i] = shortBuf[i] / 32768f
                    val live = an.push(floatBuf, n)
                    runOnUiThread { updateLive(live) }
                }
            }
            recorder.stop(); recorder.release()
        }
    }

    /** Called from the metronome worker thread — map onto the ANALYZER's clock. */
    override fun onClick(beatIndex: Long, audibleNanos: Long) {
        val an = analyzer ?: return
        // [audibleNanos] is System.nanoTime() at the estimated audible moment.
        // Convert it into the analyzer's sample clock (t = 0 at the first captured
        // sample) instead of the wall clock: the wall-clock origin is captured
        // *before* AudioRecord starts, so it omits the mic start-up latency and the
        // 100 ms feed lag. That offset (commonly tens of ms, far wider than the
        // 12 ms pre-mask) shoved every click past the mask, so clicks were counted
        // as strokes — inflating BPM / stroke count. Mapping through the analyzer's
        // own time cancels both latencies and keeps the mask centred on the click.
        val t = an.currentTimeSec() + (audibleNanos - System.nanoTime()) / 1_000_000_000.0
        if (t > 0) an.addMetronomeClick(t)
    }

    private fun updateLive(live: StreamingAnalyzer.Live) {
        b.valBpm.text = if (live.strokesPerMin > 0) live.strokesPerMin.roundToInt().toString() else "--"
        b.valCv.text = if (live.cvRoll > 0) "≈${live.jitterPct.roundToInt()}%" else "--"
        b.valStrokes.text = "${live.totalStrokes} 击　·　全段CV ${"%.2f".format(live.cv)}"
        b.ampBar.progress = (live.rms / maxOf(live.gate * 4, 0.05) * 100)
            .roundToInt().coerceIn(0, 100)
        b.gateStatus.text = "底噪 %.4f · 门限 %.4f · 当前 %.4f".format(
            live.noiseFloor, live.gate, live.rms)
    }

    private fun stop() {
        recording = false
        metronome.stop()
        b.btnToggle.setText(R.string.btn_start)
        recordThread?.join(500)
        val a = analyzer ?: return
        val m = a.metrics()
        val rep = Evaluation.build(m)
        val sb = StringBuilder()
        if (m.strokes < 4) {
            sb.append("只检测到 ${m.strokes} 击，样本太少。\n")
            sb.append("可尝试：把「灵敏度」调到「灵敏」；手机离琴近一些；确认开始后确实在弹。\n")
            b.finalReport.text = sb.toString()
            return
        }
        sb.append("【整段评估】\n")
        sb.append("综合 ${rep.score} 分 · ${rep.grade}\n")
        sb.append(rep.headline).append("\n\n")
        rep.dimensions.forEach {
            sb.append("· ${it.title}：${it.valueText}（${it.grade}）\n")
        }
        sb.append('\n').append(rep.summary).append("\n\n练习建议：\n")
        rep.advice.forEachIndexed { i, s2 -> sb.append("${i + 1}. ").append(s2).append('\n') }
        b.finalReport.text = sb.toString()
    }

    override fun onStop() {
        super.onStop()
        if (recording) stop()
    }
}
