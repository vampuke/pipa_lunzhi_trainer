package com.vampuck.pipa_trainer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.vampuck.pipa_trainer.audio.TonePlayer
import com.vampuck.pipa_trainer.databinding.ActivityTunerBinding
import com.vampuck.pipa_trainer.databinding.ItemTunerStringBinding
import com.vampuck.pipa_trainer.dsp.Tuner
import com.vampuck.pipa_trainer.dsp.Tuning
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 调音器：拨一根弦，看音名与偏高/偏低多少音分。
 *
 * 主显示是最接近的十二平均律音名 + 音分（任何定弦都对），下面再用音分距离
 * 指出是哪根弦、往哪边拧。点任意一行可以听该弦的参考音。
 */
class TunerActivity : AppCompatActivity() {

    private lateinit var b: ActivityTunerBinding
    private val sampleRate = 44100
    @Volatile private var listening = false
    private var recordThread: Thread? = null
    private var tuner: Tuner? = null
    private val tonePlayer = TonePlayer()
    private var lastLevel = 0.0
    private val rows = ArrayList<ItemTunerStringBinding>()

    private val permReq = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) start() else Toast.makeText(this, R.string.need_mic, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTunerBinding.inflate(layoutInflater)
        setContentView(b.root)
        buildStringRows()
        b.btnTunerToggle.setOnClickListener {
            if (listening) stop() else ensurePermThenStart()
        }
        tintDot(0xFFBDBDBD.toInt())   // 未取到读数时指针也要可见，停在中间
        setStatus(getString(R.string.tuner_ready))
    }

    private fun buildStringRows() {
        b.stringList.removeAllViews()
        rows.clear()
        for (s in Tuning.PIPA_STANDARD) {
            val row = ItemTunerStringBinding.inflate(LayoutInflater.from(this), b.stringList, false)
            row.strLabel.text = "${s.label}  ${s.note}"
            row.strDev.text = String.format(java.util.Locale.US, "%.2f Hz", s.hz)
            row.strDev.setTextColor(getColor(R.color.black))
            row.root.setOnClickListener {
                tonePlayer.play(s.hz)
                setStatus(getString(R.string.tuner_playing, s.label, s.note))
            }
            rows.add(row)
            b.stringList.addView(row.root)
        }
    }

    private fun ensurePermThenStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) start() else permReq.launch(Manifest.permission.RECORD_AUDIO)
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
            Toast.makeText(this, "麦克风初始化失败", Toast.LENGTH_LONG).show(); return
        }

        val t = Tuner(sampleRate)
        tuner = t
        lastLevel = 0.0
        listening = true
        b.btnTunerToggle.setText(R.string.btn_tuner_stop)
        setStatus(getString(R.string.tuner_listening))
        recorder.startRecording()

        recordThread = thread(name = "tuner-mic") {
            val shortBuf = ShortArray(sampleRate / 10)
            val floatBuf = FloatArray(shortBuf.size)
            while (listening) {
                val n = recorder.read(shortBuf, 0, shortBuf.size)
                if (n > 0) {
                    var acc = 0.0
                    for (i in 0 until n) {
                        val v = shortBuf[i] / 32768f
                        floatBuf[i] = v
                        acc += v.toDouble() * v
                    }
                    val rms = sqrt(acc / n)
                    lastLevel = if (lastLevel <= 0.0) rms else lastLevel * 0.7 + rms * 0.3
                    val reading = t.push(floatBuf, n)
                    runOnUiThread { show(reading) }
                }
            }
            recorder.stop(); recorder.release()
        }
    }

    private fun stop() {
        listening = false
        recordThread?.join(500)
        recordThread = null
        b.btnTunerToggle.setText(R.string.btn_tuner_start)
        setStatus(getString(R.string.tuner_ready))
    }

    override fun onStop() {
        super.onStop()
        if (listening) stop()
        tonePlayer.stop()
    }

    private fun show(reading: Tuner.Reading?) {
        if (reading == null) {
            // 区分「没拨弦」和「有声音但听不出音高」
            if (lastLevel < 0.006) {
                setStatus(getString(R.string.tuner_quiet))
            } else {
                setStatus(getString(R.string.tuner_unclear))
            }
            markStrings(-1, 0.0)
            return
        }
        b.noteName.text = reading.note.label
        b.noteFreq.text = String.format(java.util.Locale.US, "%.1f Hz", reading.hz)

        val cents = reading.note.cents.roundToInt()
        val inTune = abs(reading.note.cents) <= Tuning.IN_TUNE_CENTS
        val color = when {
            inTune -> getColor(R.color.good)
            abs(reading.note.cents) <= 20 -> getColor(R.color.warn)
            else -> getColor(R.color.bad)
        }
        b.centsText.text = when {
            inTune -> getString(R.string.tuner_in_tune)
            cents > 0 -> getString(R.string.tuner_sharp_by, cents)
            else -> getString(R.string.tuner_flat_by, -cents)
        }
        b.centsText.setTextColor(color)

        val m = reading.string
        b.stringHint.text = getString(
            R.string.tuner_string_hint,
            m.string.label, m.string.note,
            m.direction, abs(m.cents).roundToInt()
        )
        b.stringHint.setTextColor(if (m.inTune) getColor(R.color.good) else getColor(R.color.black))

        tintDot(color)
        positionDot(reading.note.cents)
        markStrings(m.string.number, m.cents)
        setStatus(getString(R.string.tuner_listening))
    }

    private fun positionDot(cents: Double) {
        val bar = b.centsBar
        val half = (bar.width - b.centsDot.width) / 2f
        if (half <= 0f) return
        b.centsDot.translationX = (cents / 50.0).coerceIn(-1.0, 1.0).toFloat() * half
    }

    private fun tintDot(color: Int) {
        val d = GradientDrawable()
        d.cornerRadius = 6f * resources.displayMetrics.density
        d.setColor(color)
        b.centsDot.background = d
    }

    /** 高亮最接近的那根弦，其余显示目标频率。 */
    private fun markStrings(activeNumber: Int, cents: Double) {
        for ((i, row) in rows.withIndex()) {
            val s = Tuning.PIPA_STANDARD[i]
            val active = s.number == activeNumber
            if (active) {
                val off = abs(cents).roundToInt()
                row.strDev.text = when {
                    abs(cents) <= Tuning.IN_TUNE_CENTS -> getString(R.string.tuner_string_ok)
                    cents > 0 -> getString(R.string.tuner_string_sharp, off)
                    else -> getString(R.string.tuner_string_flat, off)
                }
                row.strDev.setTextColor(getColor(R.color.pipa_primary_dark))
                row.root.setBackgroundColor(0x228D3B2E)
            } else {
                row.strDev.text = String.format(java.util.Locale.US, "%.2f Hz", s.hz)
                row.strDev.setTextColor(getColor(R.color.black))
                row.root.setBackgroundColor(0x00000000)
            }
        }
    }

    private fun setStatus(s: String) {
        b.tunerStatus.text = s
    }
}
