package com.vampuck.pipa_trainer

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.vampuck.pipa_trainer.audio.Metronome
import com.vampuck.pipa_trainer.databinding.ActivityMetronomeBinding
import com.vampuck.pipa_trainer.view.SymbolButton

/**
 * 独立节拍器。
 *
 *  - 拍型：四分音符整拍（每拍一响）/ 八分音符分拍（每拍中间多一记弱音）。
 *  - 速度：20–200 BPM，SeekBar 粗调 + −/+ 微调 + 常用速度(80–100)快捷按钮。
 *  - 重音：强调每小节第一拍（首拍不同、其余三拍相同）/ 四拍完全相同，一键切换。
 *  - 「开始 / 停止」是节奏声音的快捷开关；改任意参数即时生效，无需重启。
 *
 * 运行时的视觉指示（参考成熟节拍器）：每一拍都会让显示卡整体「闪一下」、
 * 当前拍圆点放大回弹，首拍用更强的重音色，一眼就能看出在不在走、走到第几拍。
 *
 * 不录音、不需要任何权限，只经 AudioTrack 发声。
 */
class MetronomeActivity : AppCompatActivity() {

    private lateinit var b: ActivityMetronomeBinding
    private val metronome = Metronome()

    private var bpm = 80
    /** 当前模式用到的指示圆点，数量随模式变化（4 整拍 / 8 分拍 / 5 轮指）。 */
    private var dots: List<View> = emptyList()
    private var slotCount = 4

    private val argb = ArgbEvaluator()
    private var cardFlash: ValueAnimator? = null

    private var surfaceColor = 0
    private var accentColor = 0
    private var downbeatColor = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMetronomeBinding.inflate(layoutInflater)
        setContentView(b.root)

        surfaceColor = getColor(R.color.surface)
        accentColor = getColor(R.color.pipa_accent)
        downbeatColor = getColor(R.color.pipa_primary)

        dots = emptyList()
        rebuildDots(4)

        // ---- 速度：SeekBar 粗调 (progress 0..180 -> 20..200) ----
        b.seekBpm.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) applyBpm(p + MIN_BPM, fromSeek = true)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // ---- 微调 −/+ 1 BPM ----
        b.btnMinus.setOnClickListener { applyBpm(bpm - 1) }
        b.btnPlus.setOnClickListener { applyBpm(bpm + 1) }

        // ---- 常用速度快捷按钮（80~100） ----
        b.presetGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val v = when (checkedId) {
                R.id.preset80 -> 80
                R.id.preset85 -> 85
                R.id.preset90 -> 90
                R.id.preset95 -> 95
                R.id.preset100 -> 100
                else -> return@addOnButtonCheckedListener
            }
            applyBpm(v)
        }

        // ---- 拍型：按钮只显示符号（四分音符 / 八分音符 / 轮指标记），不放文字 ----
        b.btnQuarter.symbol = SymbolButton.Symbol.QUARTER
        b.btnEighth.symbol = SymbolButton.Symbol.EIGHTH
        b.btnLunzhi.symbol = SymbolButton.Symbol.LUN
        b.modeGroup.check(R.id.btnQuarter)
        b.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btnEighth -> { metronome.setMode(Metronome.MODE_EIGHTH); rebuildDots(8) }
                R.id.btnLunzhi -> { metronome.setMode(Metronome.MODE_LUNZHI); rebuildDots(0) }
                else -> { metronome.setMode(Metronome.MODE_QUARTER); rebuildDots(4) }
            }
        }

        // ---- 重音：强调首拍 / 四拍相同 ----
        b.accentGroup.check(R.id.btnAccentFirst)
        metronome.setAccentFirst(true)
        b.accentGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            metronome.setAccentFirst(checkedId == R.id.btnAccentFirst)
        }

        // ---- 声音快捷开关 ----
        b.btnMetroToggle.setOnClickListener { if (metronome.isRunning) stop() else start() }

        // 节拍回调：点亮并弹动当前指示器；强响额外闪一下整张卡。
        metronome.onBeat = { slotIndex, count, accent ->
            runOnUiThread { flashBeat(slotIndex, count, accent) }
        }

        applyBpm(bpm)   // 初始化显示与 SeekBar
    }

    /** 统一入口：夹到 20..200，更新 BPM、SeekBar、显示并同步节拍器。 */
    private fun applyBpm(value: Int, fromSeek: Boolean = false) {
        val v = value.coerceIn(MIN_BPM, MAX_BPM)
        bpm = v
        b.bpmValue.text = v.toString()
        if (!fromSeek) b.seekBpm.progress = v - MIN_BPM
        metronome.setBpm(v.toDouble())
        // 手动改速度后清掉预设按钮的选中态（除非正好等于某个预设）
        syncPresetSelection(v)
    }

    private fun syncPresetSelection(v: Int) {
        val id = when (v) {
            80 -> R.id.preset80
            85 -> R.id.preset85
            90 -> R.id.preset90
            95 -> R.id.preset95
            100 -> R.id.preset100
            else -> View.NO_ID
        }
        if (id == View.NO_ID) b.presetGroup.clearChecked()
        else if (b.presetGroup.checkedButtonId != id) b.presetGroup.check(id)
    }

    private fun start() {
        metronome.start()
        b.btnMetroToggle.setText(R.string.metro_stop)
    }

    private fun stop() {
        metronome.stop()
        b.btnMetroToggle.setText(R.string.metro_start)
        cardFlash?.cancel()
        b.bpmCard.setCardBackgroundColor(surfaceColor)
        resetDots()
    }

    // ---- 节拍指示 ----

    private fun flashBeat(slotIndex: Int, count: Int, accent: Boolean) {
        // 轮指模式无指示器（count=0）：只发声，不做任何视觉。
        if (dots.isEmpty()) return
        if (slotIndex !in dots.indices) return
        // 1) 指示器：当前点亮并「放大回弹」，其余熄灭复位。
        for ((i, dot) in dots.withIndex()) {
            val on = i == slotIndex
            val color = when {
                !on -> DOT_OFF
                accent -> downbeatColor
                else -> accentColor
            }
            tintDot(dot, color)
            if (on) popDot(dot, if (accent) 1.7f else 1.3f) else resetDotScale(dot)
        }
        // 2) 整卡闪一下：分拍时只在「正拍」(偶数槽)闪，避免 8 响把整卡闪成频闪；
        //    重音闪得更明显。
        val isMainPulse = count != 8 || slotIndex % 2 == 0
        if (isMainPulse || accent) flashCard(if (accent) downbeatColor else accentColor)
    }

    /** 瞬间放大，再平滑回到原尺寸——像节拍器摆锤到位时的「点」。 */
    private fun popDot(dot: View, peak: Float) {
        dot.animate().cancel()
        dot.scaleX = peak
        dot.scaleY = peak
        dot.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(170)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun resetDotScale(dot: View) {
        dot.animate().cancel()
        dot.scaleX = 1f
        dot.scaleY = 1f
    }

    /** 显示卡背景从 [flash] 渐隐回表面色，形成一次明显的脉冲。 */
    private fun flashCard(flash: Int) {
        cardFlash?.cancel()
        cardFlash = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val c = argb.evaluate(a.animatedFraction, flash, surfaceColor) as Int
                b.bpmCard.setCardBackgroundColor(c)
            }
            start()
        }
    }

    private fun resetDots() {
        for (d in dots) { resetDotScale(d); tintDot(d, DOT_OFF) }
    }

    /** 按模式重建指示圆点（4 整拍 / 8 分拍 / 5 轮指）。圆点动态加入 beatDots 容器。 */
    private fun rebuildDots(count: Int) {
        slotCount = count
        b.beatDots.removeAllViews()
        val density = resources.displayMetrics.density
        // 点多了就缩小，避免横向挤出屏幕
        val sizeDp = if (count >= 8) 15f else if (count >= 5) 17f else 20f
        val marginDp = if (count >= 8) 5f else 8f
        val size = (sizeDp * density).toInt()
        val margin = (marginDp * density).toInt()
        val list = ArrayList<View>(count)
        for (i in 0 until count) {
            val v = View(this)
            val lp = android.widget.LinearLayout.LayoutParams(size, size)
            lp.setMargins(margin, margin, margin, margin)
            v.layoutParams = lp
            b.beatDots.addView(v)
            list.add(v)
        }
        dots = list
        resetDots()
    }

    private fun tintDot(dot: View, color: Int) {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        dot.background = d
    }

    override fun onStop() {
        super.onStop()
        if (metronome.isRunning) stop()
    }

    private companion object {
        const val MIN_BPM = 20
        const val MAX_BPM = 200
        const val DOT_OFF = 0xFFE0D2C6.toInt()
    }
}
