package com.vampuck.pipa_trainer.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.vampuck.pipa_trainer.R
import com.vampuck.pipa_trainer.dsp.Tuning
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 琵琶琴头 + 四个琴轴，指出「要调的那根弦该拧哪个轴」。
 *
 * 照实物画，视角是**正面看琴、琴头朝上**（左右即用户正面看琴时的左右；抱琴演奏时看到的
 * 是左右镜像，界面文案里写明了）。实物自上而下是：
 *
 *  · 杆顶一块圆形雕花头（如意/寿字那种圆牌），比下面的柄宽；
 *  · 一根细柄（琴头），四根轸子从两侧伸出来，一排两根，**左右两排还上下错开一点**
 *    （实物就是这样，不是规规矩矩的 2×2）；轸子是细长纺锤，鼓的那段上几道箍；
 *  · 山口（象牙色弦枕）压在最下面，把琴头和琴颈分开；
 *  · 山口以下是琴颈，横着几道相，往下淡出（画到画面外，暗示琴还在往下）。
 *
 * 哪根弦的轴在左/右、上/下由 [Tuning.PIPA_PEGS] 说了算，这里只负责摆——**位置摆错就等于
 * 让用户去拧错轴**，所以别在绘制里另写一套位置。
 *
 * 徽标（画在轸子外端的圆牌）里大字是音名、小字是弦号：**两个 A 轴（一弦 A3 / 四弦 A2）
 * 光看字母分不出来**，弦号必须写上。
 *
 * 高亮只用一个颜色：当前弦的轸子与徽标一起按「准/偏高/偏低」取 good/warn/bad，和调音器的
 * 指针、音分文字是同一套语义（门限同源，见 [Tuning.NEAR_CENTS]）。不在一个界面上再叠第二种强调色。
 *
 * 颜色全部在 onDraw 里现取，深浅模式各自跟随 colors.xml / values-night；木头的高光/暗部
 * 用 [lerp] 现场调，不额外加颜色资源。弦号不在定弦表里就跳过那个轴（不会画出空徽标）。
 *
 * 几何是在桌面用 AWT 试画定稿的，单位都是 dp；除 w/h 的分数外，尺寸常量走 [dp] 换算。
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
    private fun dp(v: Float) = v * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
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
        val desiredH = dp(250f).roundToInt()
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
        val nutY = h * NUT_Y
        val medalY = h * MEDAL_Y
        val medalR = min(h * MEDAL_R, w * 0.118f)
        val badgeR = min(h * BADGE_R, w * 0.082f)
        badgeRadius = badgeR
        val stalkHalf = w * STALK_HALF
        val neckHalf = w * NECK_HALF
        val neckBotHalf = w * NECK_HALF_BOT

        drawNeck(canvas, cx, h, nutY, neckHalf, neckBotHalf)
        drawStalk(canvas, cx, nutY, medalY, stalkHalf)
        drawMedallion(canvas, cx, medalY, medalR)
        drawNut(canvas, cx, nutY, neckHalf)

        badgeCenters.clear()
        val upperY = h * PEG_UP
        val lowerY = h * PEG_LO
        val stagger = h * PEG_STAGGER
        for (peg in Tuning.PIPA_PEGS) {
            // 弦号必须在定弦表里，否则这个轴没法标音名，直接不画
            val s = Tuning.PIPA_STANDARD.firstOrNull { it.number == peg.stringNumber } ?: continue
            val right = peg.side == Tuning.PegSide.RIGHT
            // 左右两排上下错开：实物上左边那排比右边高一点
            val by = (if (peg.upper) upperY else lowerY) - (if (right) 0f else stagger)
            val bx = if (right) w - dp(PAD) - badgeR else dp(PAD) + badgeR
            badgeCenters[peg.stringNumber] = floatArrayOf(bx, by)
            drawPeg(
                canvas, cx, stalkHalf, right, bx, by, badgeR, s,
                if (peg.stringNumber == activeString) statusColor() else null
            )
        }
    }

    /** 琴颈：山口以下的一块，横着几道相，往下淡出。 */
    private fun drawNeck(canvas: Canvas, cx: Float, h: Float, nutY: Float, neckHalf: Float, neckBotHalf: Float) {
        val primary = color(R.color.pipa_primary)
        val primaryDark = color(R.color.pipa_primary_dark)
        val accent = color(R.color.pipa_accent)

        val neck = Path().apply {
            moveTo(cx - neckHalf, nutY - dp(3f))
            lineTo(cx + neckHalf, nutY - dp(3f))
            lineTo(cx + neckBotHalf, h)
            lineTo(cx - neckBotHalf, h)
            close()
        }
        fill.shader = LinearGradient(
            0f, nutY, 0f, h,
            intArrayOf(lerp(primary, primaryDark, 0.45f), lerp(primaryDark, Color.BLACK, 0.10f), withAlpha(primaryDark, 70)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawPath(neck, fill)

        // 相：象牙色的道子。裁在琴颈里画，免得压到外面的背景
        val save = canvas.save()
        canvas.clipPath(neck)
        for (i in 0 until PHASE_COUNT) {
            val py = nutY + dp(7f + i * 16.5f)
            val t = (py - nutY) / (h - nutY)
            val half = neckHalf + (neckBotHalf - neckHalf) * t
            fill.shader = LinearGradient(
                0f, py, 0f, py + dp(3f),
                intArrayOf(lerp(accent, Color.WHITE, 0.55f), lerp(accent, primaryDark, 0.35f)),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(cx - half, py, cx + half, py + dp(3f), dp(1.5f), dp(1.5f), fill)
            fill.shader = null
            fill.color = withAlpha(Color.BLACK, 55)
            canvas.drawRoundRect(cx - half, py + dp(3f), cx + half, py + dp(3.9f), dp(0.45f), dp(0.45f), fill)
        }
        canvas.restoreToCount(save)
    }

    /** 琴头柄：从山口往上收窄，顶端藏在那块圆牌后面。 */
    private fun drawStalk(canvas: Canvas, cx: Float, nutY: Float, medalY: Float, stalkHalf: Float) {
        val primary = color(R.color.pipa_primary)
        val primaryDark = color(R.color.pipa_primary_dark)
        val topHalf = stalkHalf * 0.86f
        val stalk = Path().apply {
            moveTo(cx - stalkHalf, nutY - dp(2f))
            cubicTo(cx - stalkHalf * 1.02f, nutY - (nutY - medalY) * 0.45f, cx - topHalf, medalY + (nutY - medalY) * 0.35f, cx - topHalf, medalY)
            lineTo(cx + topHalf, medalY)
            cubicTo(cx + topHalf, medalY + (nutY - medalY) * 0.35f, cx + stalkHalf * 1.02f, nutY - (nutY - medalY) * 0.45f, cx + stalkHalf, nutY - dp(2f))
            close()
        }
        // 横向渐变：木头是圆的，左暗右亮再收暗，看着才有体积
        fill.shader = LinearGradient(
            cx - stalkHalf, 0f, cx + stalkHalf, 0f,
            intArrayOf(
                lerp(primaryDark, Color.BLACK, 0.15f), primary,
                lerp(primary, Color.WHITE, 0.10f), lerp(primary, primaryDark, 0.30f)
            ),
            floatArrayOf(0f, 0.28f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawPath(stalk, fill)
        fill.shader = null
        stroke.color = lerp(primaryDark, Color.BLACK, 0.25f)
        stroke.strokeWidth = dp(1.1f)
        canvas.drawPath(stalk, stroke)
    }

    /** 杆顶的圆形雕花头：木牌 + 一圈金线 + 中间一朵极简如意纹。 */
    private fun drawMedallion(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val primary = color(R.color.pipa_primary)
        val primaryDark = color(R.color.pipa_primary_dark)
        val accent = color(R.color.pipa_accent)

        fill.shader = null
        fill.color = withAlpha(SCRIM, 45)
        canvas.drawCircle(cx + dp(1.5f), cy + dp(3f), r, fill)
        fill.shader = RadialGradient(
            cx - r * 0.35f, cy - r * 0.40f, r * 1.8f,
            intArrayOf(lerp(primary, Color.WHITE, 0.16f), lerp(primaryDark, Color.BLACK, 0.30f)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, fill)
        fill.shader = null

        stroke.color = withAlpha(accent, 205)
        stroke.strokeWidth = dp(1.6f)
        canvas.drawCircle(cx, cy, r * 0.78f, stroke)
        stroke.color = withAlpha(Color.WHITE, 30)
        stroke.strokeWidth = dp(1.4f)
        for (i in 0 until 4) {
            val a = Math.PI / 2 * i + Math.PI / 4
            val ax = cx + (cos(a) * r * 0.20f).toFloat()
            val ay = cy + (sin(a) * r * 0.20f).toFloat()
            val bx = cx + (cos(a) * r * 0.58f).toFloat()
            val by = cy + (sin(a) * r * 0.58f).toFloat()
            for (sign in intArrayOf(1, -1)) {
                val path = Path().apply {
                    moveTo(ax, ay)
                    quadTo(
                        cx + (cos(a + sign * 0.42) * r * 0.55f).toFloat(),
                        cy + (sin(a + sign * 0.42) * r * 0.55f).toFloat(),
                        bx, by
                    )
                }
                canvas.drawPath(path, stroke)
            }
        }
        stroke.color = withAlpha(SCRIM, 70)
        stroke.strokeWidth = dp(1.2f)
        canvas.drawCircle(cx, cy, r, stroke)
    }

    /** 山口：象牙色弦枕，压在琴头与琴颈之间。 */
    private fun drawNut(canvas: Canvas, cx: Float, nutY: Float, neckHalf: Float) {
        val accent = color(R.color.pipa_accent)
        val primaryDark = color(R.color.pipa_primary_dark)
        val half = neckHalf * 1.08f
        fill.shader = LinearGradient(
            0f, nutY - dp(3.3f), 0f, nutY + dp(3.3f),
            intArrayOf(lerp(accent, Color.WHITE, 0.65f), lerp(accent, primaryDark, 0.35f)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(cx - half, nutY - dp(3.4f), cx + half, nutY + dp(3.2f), dp(3.3f), dp(3.3f), fill)
        fill.shader = null
        stroke.color = withAlpha(primaryDark, 140)
        stroke.strokeWidth = dp(1.0f)
        canvas.drawRoundRect(cx - half, nutY - dp(3.4f), cx + half, nutY + dp(3.2f), dp(3.3f), dp(3.3f), stroke)
    }

    /**
     * 一根轸子：细长纺锤（根粗、中段鼓、端头收），端头顶着徽标。
     * [status] 非空表示这根弦正在调：整根轴和徽标一起换成状态色。
     */
    private fun drawPeg(
        canvas: Canvas, cx: Float, rootHalf: Float, right: Boolean,
        bx: Float, by: Float, r: Float, s: Tuning.InstrumentString, status: Int?
    ) {
        val primary = color(R.color.pipa_primary)
        val primaryDark = color(R.color.pipa_primary_dark)
        val accent = color(R.color.pipa_accent)

        val sgn = if (right) 1f else -1f
        val innerX = cx + sgn * (rootHalf - dp(4f))
        val outerX = bx - sgn * r * 0.90f
        val len = abs(outerX - innerX)
        val t0 = r * 0.46f
        val t1 = r * 0.56f
        val t2 = r * 0.44f

        val shaft = Path().apply {
            moveTo(innerX, by - t0)
            cubicTo(innerX + sgn * len * 0.35f, by - t0, innerX + sgn * len * 0.42f, by - t1, innerX + sgn * len * 0.58f, by - t1)
            cubicTo(innerX + sgn * len * 0.78f, by - t1 * 0.94f, outerX - sgn * len * 0.10f, by - t2, outerX, by - t2)
            lineTo(outerX, by + t2)
            cubicTo(outerX - sgn * len * 0.10f, by + t2, innerX + sgn * len * 0.78f, by + t1 * 0.94f, innerX + sgn * len * 0.58f, by + t1)
            cubicTo(innerX + sgn * len * 0.42f, by + t1, innerX + sgn * len * 0.35f, by + t0, innerX, by + t0)
            close()
        }

        val light = if (status != null) lerp(status, Color.WHITE, 0.24f) else lerp(primary, Color.WHITE, 0.28f)
        val mid = if (status != null) status else lerp(primary, primaryDark, 0.35f)
        val dark = if (status != null) lerp(status, Color.BLACK, 0.28f) else lerp(primaryDark, Color.BLACK, 0.35f)
        fill.shader = LinearGradient(
            0f, by - t1, 0f, by + t1,
            intArrayOf(light, mid, dark), floatArrayOf(0f, 0.30f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawPath(shaft, fill)
        fill.shader = null
        stroke.color = withAlpha(Color.BLACK, 60)
        stroke.strokeWidth = dp(0.8f)
        canvas.drawPath(shaft, stroke)

        // 鼓的那一段上几道箍：丝弦金只做点睛
        stroke.color = if (status != null) withAlpha(Color.WHITE, 120) else withAlpha(accent, 220)
        stroke.strokeWidth = dp(1.3f)
        for (f in floatArrayOf(0.52f, 0.63f, 0.74f)) {
            val x = innerX + sgn * len * f
            val hw = t1 * (if (f < 0.56f) 0.97f else 0.90f)
            canvas.drawLine(x, by - hw + dp(1f), x, by + hw - dp(1f), stroke)
        }

        // 徽标：投影 + 圆盘 + 音名 / 弦号
        fill.color = withAlpha(SCRIM, 45)
        canvas.drawCircle(bx + dp(1.2f), by + dp(2.4f), r, fill)
        fill.color = status ?: color(R.color.surface_variant)
        canvas.drawCircle(bx, by, r, fill)
        if (status == null) {
            fill.shader = RadialGradient(
                bx - r * 0.35f, by - r * 0.45f, r * 1.7f,
                intArrayOf(withAlpha(Color.WHITE, 110), withAlpha(Color.WHITE, 0)),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(bx, by, r, fill)
            fill.shader = null
        }
        stroke.color = if (status != null) lerp(status, Color.BLACK, 0.25f) else color(R.color.outline)
        stroke.strokeWidth = dp(1.5f)
        canvas.drawCircle(bx, by, r, stroke)

        label.color = if (status != null) Color.WHITE else color(R.color.brand_text)
        label.typeface = Typeface.DEFAULT_BOLD
        label.textSize = r * 1.00f
        canvas.drawText(s.note.trimEnd { it.isDigit() }, bx, by + r * 0.08f, label)
        label.typeface = Typeface.DEFAULT
        label.textSize = r * 0.48f
        label.color = if (status != null) withAlpha(Color.WHITE, 235) else color(R.color.text_secondary)
        canvas.drawText(s.label, bx, by + r * 0.68f, label)
    }

    /**
     * 高亮色，与 TunerActivity.show() 里的判定同源：准 → good，[Tuning.NEAR_CENTS] 内 →
     * warn，否则 bad。两边必须用同一套门限，否则会出现「字是绿的、轴是红的」。
     */
    private fun statusColor(): Int = when {
        abs(cents) <= Tuning.IN_TUNE_CENTS -> color(R.color.good)
        abs(cents) <= Tuning.NEAR_CENTS -> color(R.color.warn)
        else -> color(R.color.bad)
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

    private fun color(res: Int) = context.getColor(res)

    private companion object {
        /** 影子的底色：暖黑（与 colors.xml 的 black / 设计稿一致），压在各处底下做厚度。 */
        val SCRIM = 0xFF231A16.toInt()

        // ---- 骨架（相对 w/h 的分数；尺寸常量以 dp 计，经 dp() 换算）----
        const val PAD = 4f                  // 徽标离画面边缘
        const val MEDAL_Y = 0.150f          // 杆顶圆牌圆心高度
        const val MEDAL_R = 0.133f          // 圆牌半径（相对 h，另受 w*0.118 封顶）
        const val NUT_Y = 0.660f            // 山口高度
        const val STALK_HALF = 0.095f       // 琴头柄半宽（相对 w）
        const val NECK_HALF = 0.122f        // 琴颈在山口处的半宽（相对 w）
        const val NECK_HALF_BOT = 0.101f    // 琴颈在画面底部的半宽（相对 w）
        const val PEG_UP = 0.323f           // 上面那排轸子的高度
        const val PEG_LO = 0.552f           // 下面那排轸子的高度
        const val PEG_STAGGER = 0.022f      // 左右两排的错开量（左边那排高一点）
        const val BADGE_R = 0.095f          // 徽标半径（相对 h，另受 w*0.082 封顶）
        const val PHASE_COUNT = 4           // 琴颈上画几道相

        /** 两个颜色按 t 混合（含 alpha），木头的高光/暗部都靠它现场调。 */
        fun lerp(from: Int, to: Int, t: Float): Int {
            fun ch(shift: Int): Int {
                val a = (from shr shift) and 0xFF
                val b = (to shr shift) and 0xFF
                return (a + (b - a) * t).roundToInt().coerceIn(0, 255)
            }
            return Color.argb(ch(24), ch(16), ch(8), ch(0))
        }

        fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)
    }
}
