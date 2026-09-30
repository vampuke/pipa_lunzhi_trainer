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
import androidx.core.content.ContextCompat
import com.vampuck.pipa_trainer.databinding.ActivityLiveBinding
import com.vampuck.pipa_trainer.dsp.Evaluation
import com.vampuck.pipa_trainer.dsp.StreamingAnalyzer
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class LiveActivity : AppCompatActivity() {

    private lateinit var b: ActivityLiveBinding
    private val sampleRate = 44100
    @Volatile private var recording = false
    private var recordThread: Thread? = null
    private var analyzer: StreamingAnalyzer? = null

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
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
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
        b.btnToggle.setText(R.string.btn_stop)
        recording = true
        recorder.startRecording()

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

    private fun updateLive(live: StreamingAnalyzer.Live) {
        b.valBpm.text = if (live.strokesPerMin > 0) live.strokesPerMin.roundToInt().toString() else "--"
        b.valCv.text = if (live.cv > 0) "%.2f".format(live.cv) else "--"
        b.valStrokes.text = "${live.totalStrokes} strokes"
        b.ampBar.progress = (live.lastAmp * 100).roundToInt().coerceIn(0, 100)
    }

    private fun stop() {
        recording = false
        b.btnToggle.setText(R.string.btn_start)
        recordThread?.join(500)
        val a = analyzer ?: return
        val s = a.finalize()
        val rep = Evaluation.build(
            s.strokesPerMin, s.strokesPerSec, s.cv, s.modalCv,
            s.fingerProfile, s.totalStrokes, s.durationSec
        )
        val sb = StringBuilder()
        sb.append("=== Session evaluation ===\n")
        sb.append("Grade ${rep.grade}  •  ${rep.headline}\n\n")
        rep.bullets.forEach { sb.append("• ").append(it).append('\n') }
        sb.append('\n')
        rep.fingerBullets.forEach { sb.append("• ").append(it).append('\n') }
        b.finalReport.text = sb.toString()
    }

    override fun onStop() {
        super.onStop()
        if (recording) stop()
    }
}
