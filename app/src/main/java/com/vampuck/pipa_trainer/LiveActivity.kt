package com.vampuck.pipa_trainer

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
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
    private var analyzer: StreamingAnalyzer? = null
    private val metronome = Metronome()
    @Volatile private var sessionStartNs: Long = 0L

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
            if (recording) {
                if (on) metronome.start() else metronome.stop()
            }
        }
        b.seekBpm.setOnSeekBarChangeListener(object :
            android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, u: Boolean) {
                val v = (p + 20)  // slider 0..220 -> BPM 20..240
                b.valMetroBpm.text = v.toString()
                metronome.setBpm(v.toDouble())
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        metronome.setBpm(80.0)
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
        // VOICE_COMMUNICATION source enables the system acoustic echo canceller
        // (AEC) on devices that support it. Combined with the software masking
        // of metronome clicks in StreamingAnalyzer, this gives two layers of
        // protection against the click registering as a pipa onset.
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (e: SecurityException) {
            Toast.makeText(this, R.string.need_mic, Toast.LENGTH_LONG).show(); return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Toast.makeText(this, "Mic init failed", Toast.LENGTH_LONG).show(); return
        }

        analyzer = StreamingAnalyzer(sampleRate)
        b.finalReport.text = ""
        b.gateStatus.text = getString(R.string.gate_active)
        b.btnToggle.setText(R.string.btn_stop)
        recording = true
        sessionStartNs = System.nanoTime()
        recorder.startRecording()

        if (b.switchMetro.isChecked) metronome.start()

        recordThread = thread(name = "mic") {
            val shortBuf = ShortArray(sampleRate / 10) // 100ms chunks
            val floatBuf = FloatArray(shortBuf.size)
            while (recording) {
                val n = recorder.read(shortBuf, 0, shortBuf.size)
                if (n > 0) {
                    for (i in 0 until n) floatBuf[i] = shortBuf[i] / 32768f
                    val live = analyzer!!.push(floatBuf, n)
                    runOnUiThread { updateLive(live) }
                }
            }
            recorder.stop(); recorder.release()
        }
    }

    /** Called from the metronome worker thread. */
    override fun onClick(beatIndex: Long, sessionTimeSec: Double) {
        analyzer?.addMetronomeClick(sessionTimeSec)
    }

    private fun updateLive(live: StreamingAnalyzer.Live) {
        b.valBpm.text = if (live.strokesPerMin > 0) live.strokesPerMin.roundToInt().toString() else "--"
        b.valCv.text = if (live.cv > 0) "%.2f".format(live.cv) else "--"
        b.valStrokes.text = "${live.totalStrokes} 击"
        b.ampBar.progress = (live.lastAmp * 100).roundToInt().coerceIn(0, 100)
        // show whether gate is letting things through and current floor
        b.gateStatus.text = if (live.totalStrokes == 0 && live.lastAmp <= 0.05)
            getString(R.string.gate_quiet)
        else
            getString(R.string.gate_floor_fmt, live.noiseFloor, live.noiseFloor * 3.0)
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