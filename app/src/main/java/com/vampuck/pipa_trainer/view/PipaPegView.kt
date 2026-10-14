package com.vampuck.pipa_trainer.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.vampuck.pipa_trainer.R
import com.vampuck.pipa_trainer.dsp.Tuning
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 琵琶琴头 + 四个琴轴，指出「要调的那根弦该拧哪个轴」。
 *
 * 布局照参考图：中间是琴头本体，左右各伸出两个轴柄，轴柄外端各一个圆形徽标。
 * 徽标里大字是音名、小字是弦号——**两个 A 轴（一弦 A3 / 四弦 A2）光看字母分不出来**，
 * 所以弦号必须写上。
 *
 * 高亮只用一个颜色：当前弦对应的轴柄与徽标一起按「准/偏高/偏低」取 good/warn/bad，
 * 和调音器的指针、音分文字是同一套语义。不在一个界面上再叠第二种强调色。
 *
 * 颜色全部在 onDraw 里现取，深浅模式各自跟随 colors.xml / values-night。
 * 画不出来的一部分靠 PIPA 定弦表，弦号不在表里就跳过那个轴（不会画出空徽标）。
 */
class PipaPegView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** 正在调的弦号（1..4）。0 = 还没有读数，四个轴都不高亮。 */
    var activeString: Int = 0
        set(value) {
            if (field == value) return
            field = value
            updateContentDescription()
            invalidate()
        }

    /** 相对当前弦的音分偏差，只用来决定高亮轴的颜色。 */
    var cents: Double = 0.0
        set(value) {
            if (field == value) return
            field = value
            updateContentDescription()
            invalidate()
        }

    /** 点某个轴的回调，参数是弦号；用来播该弦的参考音。 */
    var onPegTap: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /** 弦号 → 徽标圆心，onDraw 里算好给 onTouchEvent 做命中判定。 */
    private val badgeCenters = HashMap<Int, FloatArray>()
    private var badgeRadius = 0f

    /** ACTION_DOWN 落在哪个轴上，ACTION_UP 要落在同一个轴才算点中。 */
    private var pressedString = 0

    init {
        isClickable = true
        updateContentDescription()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = (density * 250f).roundToInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredH, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val pad = 2f * density
        val r = min(h * 0.12f, w * 0.10f)
        badgeRadius = r

        // 琴头本体：竖直的圆角矩形，上下留出边距给四根轴柄
        val bodyHalfW = w * 0.155f
        val bodyCorner = w * 0.05f
        fill.color = context.getColor(R.color.pipa_primary)
        canvas.drawRoundRect(
            RectF(cx - bodyHalfW, h * 0.07f, cx + bodyHalfW, h * 0.93f),
            bodyCorner, bodyCorner, fill
        )

        badgeCenters.clear()
        for (peg in Tuning.PIPA_PEGS) {
            // 弦号必须在定弦表里，否则这个轴没法标音名，直接不画
            val s = Tuning.PIPA_STANDARD.firstOrNull { it.number == peg.stringNumber } ?: continue
            val active = peg.stringNumber == activeString
            val accent = if (active) statusColor() else NO_ACCENT

            val onLeft = peg.side == Tuning.PegSide.LEFT
            val bx = if (onLeft) pad + r else w - pad - r
            val by = if (peg.upper) h * 0.30f else h * 0.70f
            badgeCenters[peg.stringNumber] = floatArrayOf(bx, by)

            drawPegHandle(canvas, onLeft, cx, bodyHalfW, bx, by, r, w, h, accent)
            drawBadge(canvas, bx, by, r, s, accent)
        }
    }

    /** 轴柄：从琴头边缘伸出去，外端停在徽标外侧留一点缝。 */
    private fun drawPegHandle(
        canvas: Canvas, onLeft: Boolean, cx: Float, bodyHalfW: Float,
        bx: Float, by: Float, r: Float, w: Float, h: Float, accent: Int
    ) {
        val thickness = h * 0.075f
        val innerX = if (onLeft) cx - bodyHalfW else cx + bodyHalfW
        val gap = w * 0.015f
        val outerX = if (onLeft) bx + r + gap else bx - r - gap
        fill.color = if (accent != NO_ACCENT) accent else context.getColor(R.color.outline)
        canvas.drawRoundRect(
            RectF(min(innerX, outerX), by - thickness / 2f, max(innerX, outerX), by + thickness / 2f),
            thickness / 2f, thickness / 2f, fill
        )
    }

    /** 徽标：大字母是音名，小字是弦号。 */
    private fun drawBadge(
        canvas: Canvas, bx: Float, by: Float, r: Float,
        s: Tuning.InstrumentString, accent: Int
    ) {
        val active = accent != NO_ACCENT
        fill.color = if (active) accent else context.getColor(R.color.surface_variant)
        canvas.drawCircle(bx, by, r, fill)
        stroke.color = if (active) accent else context.getColor(R.color.outline)
        canvas.drawCircle(bx, by, r, stroke)

        val ink = if (active) context.getColor(R.color.white) else context.getColor(R.color.text_secondary)

        label.color = ink
        label.typeface = Typeface.DEFAULT_BOLD
        label.textSize = r * 0.95f
        val lm = label.fontMetrics
        canvas.drawText(
            s.note.trimEnd { it.isDigit() },          // "A3" → "A"，"F#5" → "F#"
            bx, by - (lm.ascent + lm.descent) / 2f - r * 0.18f, label
        )

        label.typeface = Typeface.DEFAULT
        label.textSize = r * 0.42f
        val nm = label.fontMetrics
        canvas.drawText(s.label, bx, by - (nm.ascent + nm.descent) / 2f + r * 0.52f, label)
    }

    /**
     * 高亮色，与 TunerActivity.show() 里的判定同源：准 → good，[Tuning.NEAR_CENTS] 内 →
     * warn，否则 bad。两边必须用同一套门限，否则会出现「字是绿的、轴是红的」。
     */
    private fun statusColor(): Int = when {
        abs(cents) <= Tuning.IN_TUNE_CENTS -> context.getColor(R.color.good)
        abs(cents) <= Tuning.NEAR_CENTS -> context.getColor(R.color.warn)
        else -> context.getColor(R.color.bad)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedString = pegAt(event.x, event.y)
                return pressedString != 0
            }
            MotionEvent.ACTION_UP -> {
                val n = pegAt(event.x, event.y)
                pressedString = 0
                if (n != 0) {
                    performClick()
                    onPegTap?.invoke(n)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedString = 0
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** 命中判定：徽标本身只有 30dp 上下，命中区放大到视觉半径的 1.5 倍，手指没那么准也点得到。 */
    private fun pegAt(x: Float, y: Float): Int {
        val hit = badgeRadius * 1.5f
        if (hit <= 0f) return 0
        for ((number, c) in badgeCenters) {
            val dx = x - c[0]
            val dy = y - c[1]
            if (dx * dx + dy * dy <= hit * hit) return number
        }
        return 0
    }

    /** 读屏要说清楚「哪个轴、什么状态」，光靠颜色不够。 */
    private fun updateContentDescription() {
        val s = Tuning.PIPA_STANDARD.firstOrNull { it.number == activeString }
        contentDescription = if (s == null) {
            context.getString(R.string.tuner_peg_desc, context.getString(R.string.tuner_ready))
        } else {
            // 与 statusColor() 同一套判据，别用四舍五入后的整数——5.4 音分会被舍成 5
            // 而误判成「准」（IN_TUNE_CENTS 是 5.0）。
            val off = cents.roundToInt()
            val detail = when {
                abs(cents) <= Tuning.IN_TUNE_CENTS -> context.getString(R.string.tuner_string_ok)
                cents > 0 -> context.getString(R.string.tuner_string_sharp, off)
                else -> context.getString(R.string.tuner_string_flat, -off)
            }
            context.getString(R.string.tuner_peg_desc, "${s.label} ${s.note}，$detail")
        }
    }

    private companion object {
        /** 「没有轴高亮」。真实颜色取不到这个值（ARGB 全透明），只是省得再传一个布尔。 */
        const val NO_ACCENT = 0
    }
}
