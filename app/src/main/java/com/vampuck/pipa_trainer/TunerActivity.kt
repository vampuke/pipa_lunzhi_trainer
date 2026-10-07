package com.vampuck.pipa_trainer

import android.Manifest
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.vampuck.pipa_trainer.audio.TonePlayer
import com.vampuck.pipa_trainer.databinding.ActivityTunerBinding
import com.vampuck.pipa_trainer.databinding.ItemTunerStringBinding
import com.vampuck.pipa_trainer.dsp.Tuner
import com.vampuck.pipa_trainer.dsp.Tuning
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 调音器：拨一根弦，看音名与偏高/偏低多少音分。
 *
 * 主显示是「正在调的那根弦 + 它的音名」，因为用户是照着**弦**调音；麦克风实际听到的
 * 绝对音高放在次行（「听到 B3 · 246.9 Hz」），指针与偏高/偏低于相对这根弦算。
 *
 * 为什么不能反过来（曾经的写法）：一弦高整整一个全音时，绝对音名正好落在 B3 上，
 * 音分≈0，屏幕就会显示「B3」并把指针放到中间报「准了」——用户既以为一弦的
 * 音名是 B3，又以为弦已经调好了。相对弦算的话，同一读数会显示
 * 「一弦 / A3 / 听到 B3 / 偏高 200 音分 · 松一点」，该怎么调一目了然。
 *
 * 下面仍用音分距离指出是哪根弦，点任意一行可以听该弦的参考音。
 */
class TunerActivity : AppCompatActivity() {

    private lateinit var b: ActivityTunerBinding
    private val sampleRate = 44100

    /** 实际交付的采样率（不少设备固定 48kHz），换乐器重建 Tuner 时要沿用。 */
    private var actualRate = 44100
    private var instrument: Tuning.Instrument = Tuning.PIPA
    @Volatile private var listening = false
    private var recordThread: Thread? = null

    /** 持有 recorder 才能在停止时先打断阻塞中的 read()，再去 join。 */
    @Volatile private var recorder: AudioRecord? = null

    /**
     * 当前正在用的检测器实例。换乐器会重启引擎并换掉它；录音线程闭包里是构造时
     * 捕获的那个 Tuner，界面用 [tuner] 做一次身份校验，丢掉换表瞬间还在飞的旧读数。
     */
    @Volatile private var tuner: Tuner? = null
    private val tonePlayer = TonePlayer()
    private var lastLevel = 0.0

    /** 状态行里显示的实际采样率，出「音高整体偏移」类问题时一眼能看出来。 */
    private var rateText = "44.1 kHz"
    private val rows = ArrayList<ItemTunerStringBinding>()

    private val permReq = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) start() else MicPermission.showHelp(this) {
            permReq.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTunerBinding.inflate(layoutInflater)
        setContentView(b.root)
        buildStringRows()
        b.stringsHint.setText(stringsHintRes(instrument))
        b.instrumentGroup.check(R.id.btnPipa)
        b.instrumentGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectInstrument(
                when (checkedId) {
                    R.id.btnGuzheng -> Tuning.GUZHENG
                    R.id.btnGuitar -> Tuning.GUITAR
                    else -> Tuning.PIPA
                }
            )
        }
        b.btnTunerToggle.setOnClickListener {
            if (listening) stop() else ensurePermThenStart()
        }
        tintDot(0xFFBDBDBD.toInt())   // 未取到读数时指针也要可见，停在中间
        setStatus(getString(R.string.tuner_ready))
    }

    /** 切换乐器：换弦表、重建检测器、清掉上一件乐器的读数。 */
    private fun selectInstrument(inst: Tuning.Instrument) {
        if (inst.key == instrument.key) return
        instrument = inst
        buildStringRows()
        b.stringsHint.setText(stringsHintRes(inst))
        // 弦号/音分是相对定弦表的，换表后旧读数不再有效，必须清掉。
        b.noteName.text = "--"
        b.noteFreq.text = getString(R.string.tuner_ready)
        b.centsText.text = ""
        b.stringHint.text = ""
        tintDot(0xFFBDBDBD.toInt())
        markStrings(-1, 0.0)
        setStatus(getString(R.string.tuner_ready))
        // 录音线程闭包捕获的是构造时的 Tuner：只换字段的话，跑着的线程会继续用
        // 旧弦表出「第几弦、往哪拧」，用户照着拧就拧错弦。所以正在听就重启引擎。
        if (listening) {
            stop()
            start()
        }
    }

    private fun stringsHintRes(inst: Tuning.Instrument): Int = when (inst.key) {
        Tuning.GUZHENG.key -> R.string.tuner_strings_hint_guzheng
        Tuning.GUITAR.key -> R.string.tuner_strings_hint_guitar
        else -> R.string.tuner_strings_hint
    }

    private fun buildStringRows() {
        b.stringList.removeAllViews()
        rows.clear()
        for (s in instrument.strings) {
            val row = ItemTunerStringBinding.inflate(LayoutInflater.from(this), b.stringList, false)
            row.strLabel.text = "${s.label}  ${s.note}"
            row.strDev.text = String.format(Locale.US, "%.2f Hz", s.hz)
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
        if (MicPermission.granted(this)) start()
        else permReq.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun start() {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, sampleRate / 4) * 2
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (e: Throwable) {
            Toast.makeText(this, R.string.mic_init_failed, Toast.LENGTH_LONG).show(); return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            // 构造成功了也要 release，否则每次重试漏一个 native 缓冲。
            try { rec.release() } catch (_: Throwable) {}
            Toast.makeText(this, R.string.mic_init_failed, Toast.LENGTH_LONG).show(); return
        }

        // 设备不一定按请求的采样率交付（不少机器固定 48kHz）。若实际是 48000 而
        // 我们按 44100 去算，偏差是 1200*log2(48/44.1) ≈ 147 音分——比半个音还多，
        // 表现成「调音器坏了」。所以一律用 AudioRecord 回报的实际采样率。
        actualRate = rec.sampleRate.takeIf { it > 0 } ?: sampleRate
        rateText = String.format(Locale.US, "%.1f kHz", actualRate / 1000f)

        val t = Tuner(actualRate, instrument.strings)
        tuner = t
        try {
            rec.startRecording()
        } catch (e: Throwable) {
            // 麦克风被其它应用占用时会抛 IllegalStateException（原来是裸调用 → 崩溃）。
            try { rec.release() } catch (_: Throwable) {}
            Toast.makeText(this, R.string.mic_init_failed, Toast.LENGTH_LONG).show(); return
        }
        recorder = rec
        lastLevel = 0.0
        listening = true
        b.btnTunerToggle.setText(R.string.btn_tuner_stop)
        setStatus(getString(R.string.tuner_listening_rate, rateText))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        recordThread = thread(name = "tuner-mic") {
            // 512 采样 ≈ 11.6ms：相邻两次分析窗重叠 75%，指针才稳；按 100ms 读时
            // 相邻两窗完全不重叠，每次都像在量一段全新音频。
            val shortBuf = ShortArray(512)
            val floatBuf = FloatArray(shortBuf.size)
            var lastUi = 0L
            while (listening) {
                val n = try { rec.read(shortBuf, 0, shortBuf.size) } catch (e: Throwable) { -1 }
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
                    // 分析约 86 次/秒，但界面只需约 30 次/秒
                    val now = System.currentTimeMillis()
                    if (reading != null && now - lastUi >= UI_MIN_INTERVAL_MS) {
                        lastUi = now
                        runOnUiThread { show(reading, t) }
                    }
                }
            }
            try { rec.stop() } catch (_: Throwable) {}
            try { rec.release() } catch (_: Throwable) {}
        }
    }

    private companion object {
        const val UI_MIN_INTERVAL_MS = 33L
    }

    private fun stop() {
        listening = false
        tuner = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b.btnTunerToggle.setText(R.string.btn_tuner_start)
        setStatus(getString(R.string.tuner_ready))
        // 先 stop() 打断阻塞中的 read()，再 join —— 否则主线程最多干等 500ms。
        try { recorder?.stop() } catch (_: Throwable) {}
        recordThread?.join(300)
        recordThread = null
        recorder = null
    }

    override fun onStop() {
        super.onStop()
        if (listening) stop()
        tonePlayer.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        tonePlayer.stop()
    }

    /**
     * @param t 产生这个读数的 [Tuner]。必须用它来取「第几弦」，不能用字段——换乐器
     *          时旧线程可能还有一两次读数在飞，字段已经指向新弦表了。
     */
    private fun show(reading: Tuner.Reading?, t: Tuner) {
        // 换乐器时旧线程可能还有一两次读数在飞，直接丢掉。
        if (t !== tuner) return
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

        // 主显示 = 要调的那根弦 + 它的音名（目标）；绝对音高降为次行。
        // 弦位来自产生这个读数的 Tuner（它有自己的弦表，已通过上面的身份校验），
        // 不要再用字段 instrument 重算一遍。
        val m = reading.string
        b.stringHint.text = getString(R.string.tuner_tuning_target, m.string.label)
        b.noteName.text = m.string.note
        b.noteFreq.text = getString(
            R.string.tuner_heard,
            reading.note.label,
            String.format(Locale.US, "%.1f Hz", reading.hz)
        )

        // 「准不准」「偏高/偏低多少」「指针」全部相对这根弦算。绝不能相对绝对音名算：
        // 一弦高一个全音时绝对音名正好是 B3（音分≈0），那样会报「准了」。
        val cents = m.cents.roundToInt()
        val inTune = m.inTune
        val color = when {
            inTune -> getColor(R.color.good)
            abs(m.cents) <= 20 -> getColor(R.color.warn)
            else -> getColor(R.color.bad)
        }
        b.centsText.text = when {
            inTune -> getString(R.string.tuner_in_tune)
            cents > 0 -> getString(R.string.tuner_adjust_loosen, cents)
            else -> getString(R.string.tuner_adjust_tighten, -cents)
        }
        b.centsText.setTextColor(color)

        tintDot(color)
        positionDot(m.cents)
        markStrings(m.string.number, m.cents)
        setStatus(getString(R.string.tuner_listening_rate, rateText))
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
            if (i >= instrument.strings.size) break
            val s = instrument.strings[i]
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
                row.strDev.text = String.format(Locale.US, "%.2f Hz", s.hz)
                row.strDev.setTextColor(getColor(R.color.black))
                row.root.setBackgroundColor(0x00000000)
            }
        }
    }

    private fun setStatus(s: String) {
        b.tunerStatus.text = s
    }
}
