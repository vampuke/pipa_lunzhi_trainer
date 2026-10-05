package com.vampuck.pipa_trainer

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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

    /** 持有 recorder 才能在停止时先打断阻塞中的 read()，再去 join。 */
    @Volatile private var recorder: AudioRecord? = null
    private val metronome = Metronome()

    /** 节拍器速度的唯一真源：滑杆 progress = BPM - [BPM_OFFSET]。 */
    private var bpm = DEFAULT_BPM

    private val permReq = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) start() else MicPermission.showHelp(this) {
            permReq.launch(Manifest.permission.RECORD_AUDIO)
        }
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
                applyBpm(p + BPM_OFFSET)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        // 走同一个入口：滑杆的 progress、显示文本、节拍器速度、遮蔽周期
        // 曾经是四份互不一致的状态（XML 写 80、代码按 100 算）。
        applyBpm(b.seekBpm.progress + BPM_OFFSET)

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

    /** BPM 的唯一入口：显示、节拍器、麦克风遮蔽周期一起改。 */
    private fun applyBpm(value: Int) {
        bpm = value.toDouble()
        b.valMetroBpm.text = value.toString()
        metronome.setBpm(bpm)
        analyzer?.setMetronomeBeat(60.0 / bpm)
    }

    private fun sensLabel(p: Int) = when (p) {
        0 -> getString(R.string.live_sens_strict)
        2 -> getString(R.string.live_sens_sensitive)
        else -> getString(R.string.live_sens_normal)
    }

    private fun ensurePermThenStart() {
        if (MicPermission.granted(this)) start()
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
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (e: Throwable) {
            Toast.makeText(this, R.string.mic_init_failed, Toast.LENGTH_LONG).show()
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            // 构造成功了也要 release：native 缓冲已经分配了，不释放就是每次重试漏一个。
            try { rec.release() } catch (_: Throwable) {}
            Toast.makeText(this, R.string.mic_init_failed, Toast.LENGTH_LONG).show()
            return
        }
        try {
            rec.startRecording()
        } catch (e: Throwable) {
            // 麦克风被别的应用占用时 startRecording 会抛 IllegalStateException，
            // 原来这里是裸调用，直接把 App 打崩。
            try { rec.release() } catch (_: Throwable) {}
            Toast.makeText(this, R.string.mic_init_failed, Toast.LENGTH_LONG).show()
            return
        }

        val an = StreamingAnalyzer(sampleRate)
        an.setSensitivity(b.seekSens.progress)
        an.setMetronomeBeat(60.0 / bpm)
        analyzer = an
        recorder = rec
        b.finalReport.text = ""
        b.btnToggle.setText(R.string.btn_stop)
        recording = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (b.switchMetro.isChecked) metronome.start()

        recordThread = thread(name = "mic") {
            val shortBuf = ShortArray(sampleRate / 10)
            val floatBuf = FloatArray(shortBuf.size)
            while (recording) {
                val n = try { rec.read(shortBuf, 0, shortBuf.size) } catch (e: Throwable) { -1 }
                if (n > 0) {
                    for (i in 0 until n) floatBuf[i] = shortBuf[i] / 32768f
                    val live = an.push(floatBuf, n)
                    runOnUiThread { updateLive(live) }
                }
            }
            try { rec.stop() } catch (_: Throwable) {}
            try { rec.release() } catch (_: Throwable) {}
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
        b.valStrokes.text = getString(R.string.live_strokes_line, live.totalStrokes, live.cv)
        b.ampBar.progress = (live.rms / maxOf(live.gate * 4, 0.05) * 100)
            .roundToInt().coerceIn(0, 100)
        b.gateStatus.text = getString(
            R.string.live_gate_line, live.noiseFloor, live.gate, live.rms
        )
    }

    private fun stop() {
        recording = false
        metronome.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b.btnToggle.setText(R.string.btn_start)
        // 先 stop() 打断阻塞中的 read()，再 join：顺序反了主线程会白等最多 500ms。
        try { recorder?.stop() } catch (_: Throwable) {}
        recordThread?.join(300)
        recordThread = null
        recorder = null
        val a = analyzer ?: return
        analyzer = null
        val m = a.metrics()
        val rep = Evaluation.build(m)
        val sb = StringBuilder()
        if (m.strokes < 4) {
            sb.append(getString(R.string.live_too_few, m.strokes)).append('\n')
            sb.append(getString(R.string.live_too_few_hint)).append('\n')
            b.finalReport.text = sb.toString()
            return
        }
        sb.append(getString(R.string.live_report_head)).append('\n')
        sb.append(getString(R.string.live_report_score, rep.score, rep.grade)).append('\n')
        sb.append(rep.headline).append("\n\n")
        rep.dimensions.forEach {
            sb.append("· ${it.title}：${it.valueText}（${it.grade}）\n")
        }
        sb.append('\n').append(rep.summary).append("\n\n")
        sb.append(getString(R.string.live_report_advice)).append('\n')
        rep.advice.forEachIndexed { i, s2 -> sb.append("${i + 1}. ").append(s2).append('\n') }
        b.finalReport.text = sb.toString()
    }

    override fun onStop() {
        super.onStop()
        if (recording) stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 别让节拍器的工作线程继续持有已销毁的 Activity。
        metronome.listener = null
        metronome.onBeat = null
    }

    private companion object {
        const val DEFAULT_BPM = 80
        const val BPM_OFFSET = 20
    }
}
