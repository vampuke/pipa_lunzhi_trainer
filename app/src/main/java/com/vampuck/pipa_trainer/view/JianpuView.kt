package com.vampuck.pipa_trainer.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.vampuck.pipa_trainer.data.JNote
import com.vampuck.pipa_trainer.data.JScore
import kotlin.math.max

/**
 * 简谱（数字谱）滚动谱渲染。
 *
 * 支持的记号：
 *  - 音级数字 1..7，0 = 休止符（画「0」）。
 *  - 八度点：高八度在数字上方加点，低八度在下方加点（支持 ±1，可叠加 ±2）。
 *  - 时值：
 *      · 二分音符（dur=2）数字后跟一条「—」增时线；全音符（dur=4）跟三条。
 *      · 八分音符（dur=0.5）数字下一条下划线；十六分（dur=0.25）两条。
 *  - 轮指：数字上方画三条小斜线（简谱里轮指常用记号）。
 *  - 小节线：按 beatsPerBar 自动插入竖线。
 *
 * [highlightIndex] 指定当前音符（flatNotes 下标），高亮并通过 [currentTop]/[currentBottom]
 * 回报其纵向位置，外部用来自动滚动。
 */
class JianpuView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(22f); textAlign = Paint.Align.CENTER
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1.6f) }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1.2f) }
    private val trPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1.4f) }
    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(11f); textAlign = Paint.Align.CENTER }
    private val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(13f); isFakeBoldText = true }

    private val colText = 0xFF231A16.toInt()
    private val colSecondary = 0xFF7A6A60.toInt()
    private val colPrimary = 0xFF8C3A2B.toInt()
    private val colAccent = 0xFFC8973F.toInt()
    private val colHl = 0x33C8973F         // 丝弦金高亮底（半透明）
    private val colBar = 0xFFC9BBAE.toInt()

    private var score: JScore? = null
    private var sectionStarts: List<Int> = emptyList()
    private var sectionNames: List<String> = emptyList()

    /** 按小节高亮：当前小节序号（0-based），-1 表示无。 */
    var highlightBar: Int = -1
        set(value) { field = value; updateCurrentBounds(); invalidate() }

    private fun updateCurrentBounds() {
        currentTop = -1f; currentBottom = -1f
        for (g in glyphs) if (g.barIndex == highlightBar) {
            if (currentTop < 0 || g.lineTop < currentTop) currentTop = g.lineTop
            if (g.lineBottom > currentBottom) currentBottom = g.lineBottom
        }
    }

    /** 当前高亮音符的纵向范围（px），布局完成后有效；-1 表示未知。 */
    var currentTop: Float = -1f; private set
    var currentBottom: Float = -1f; private set
    var onLayoutReady: (() -> Unit)? = null

    // ---- 布局缓存 ----
    private data class Glyph(
        val note: JNote, val index: Int, val barIndex: Int,
        val cx: Float, val cy: Float,       // 数字中心
        val cellLeft: Float, val cellRight: Float,
        val lineTop: Float, val lineBottom: Float  // 该行占用的纵向范围（含点/线）
    )
    private val glyphs = ArrayList<Glyph>()
    private val barXsPerRow = ArrayList<Pair<Float, Float>>() // (x, rowCy) 小节线
    private val sectionLabels = ArrayList<Triple<String, Float, Float>>() // name, x, y
    private var contentHeight = 0f

    fun setScore(s: JScore, names: List<String>) {
        score = s
        sectionStarts = s.sectionStartIndices()
        sectionNames = names
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        layoutGlyphs(w.toFloat())
        val h = max(contentHeight, dp(120f)).toInt()
        setMeasuredDimension(w, h)
    }

    /** 把音符从左到右排布，按宽度换行；每个段落首音符前换行并留标签空间。 */
    private fun layoutGlyphs(width: Float) {
        glyphs.clear(); barXsPerRow.clear(); sectionLabels.clear()
        val s = score ?: run { contentHeight = 0f; return }
        if (width <= 0) return

        val padL = dp(16f); val padR = dp(16f)
        val cell = dp(34f)                 // 每个音符水平占位
        val rowH = dp(62f)                 // 行高（含上方点/轮指 + 下方时值线）
        val numBaselineOffset = dp(8f)     // 数字基线相对中心
        val usableW = width - padL - padR
        // 容差：只要再放得下大半个 cell 就不换行，避免宽屏右侧留一大段空白
        val wrapSlack = cell * 0.5f

        var x = padL
        var rowTop = dp(10f)
        var cy = rowTop + rowH / 2
        var beatInBar = 0.0
        val bpb = s.beatsPerBar
        var barIndex = 0

        val starts = sectionStarts.toHashSet()

        for ((i, note) in s.flatNotes.withIndex()) {
            // 段落开头：换行 + 预留标签行
            if (i in starts) {
                if (i != 0) { rowTop += rowH; }
                rowTop += dp(26f)   // 段标签高度
                val secIdx = sectionStarts.indexOf(i)
                sectionLabels.add(Triple(sectionNames.getOrElse(secIdx) { "" }, padL, rowTop - dp(8f)))
                x = padL
                cy = rowTop + rowH / 2
                beatInBar = 0.0
            }
            // 行末换行：留 wrapSlack 容差，宽屏尽量用满整行再换
            if (x + cell > padL + usableW + wrapSlack) {
                rowTop += rowH
                x = padL
                cy = rowTop + rowH / 2
            }

            val cx = x + cell / 2
            glyphs.add(
                Glyph(note, i, barIndex, cx, cy + numBaselineOffset, x, x + cell, rowTop, rowTop + rowH)
            )

            // 增时线（二分/全音符）：数字后面画横线，占额外 cell
            var extraCells = 0
            if (!note.isRest) {
                val dashes = when {
                    note.dur >= 4.0 -> 3
                    note.dur >= 3.0 -> 2
                    note.dur >= 2.0 -> 1
                    else -> 0
                }
                extraCells = dashes
            }

            x += cell * (1 + extraCells)
            beatInBar += note.dur

            // 小节线
            if (beatInBar >= bpb - 1e-6) {
                beatInBar = 0.0
                barIndex++
                if (x + dp(6f) < padL + usableW) {
                    barXsPerRow.add(Pair(x + dp(4f), cy))
                    x += dp(10f)
                }
            }
        }
        contentHeight = (rowTop + rowH + dp(16f))
        updateCurrentBounds()
        onLayoutReady?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        val s = score ?: return

        // 段标签
        labelPaint.color = colPrimary
        for ((name, lx, ly) in sectionLabels) canvas.drawText(name, lx, ly, labelPaint)

        // 小节线
        barPaint.color = colBar
        for ((bx, bcy) in barXsPerRow) {
            canvas.drawLine(bx, bcy - dp(14f), bx, bcy + dp(14f), barPaint)
        }

        // 整小节高亮：按行把当前小节的字块合并成一条连续底，而非每个音符一个框
        if (highlightBar >= 0) {
            hlPaint.color = colHl
            // 同一小节可能跨行，按 (rowTop) 分组，每行画一条连续圆角底
            val barGlyphs = glyphs.filter { it.barIndex == highlightBar }
            val byRow = barGlyphs.groupBy { it.lineTop }
            for ((_, rowGlyphs) in byRow) {
                val left = rowGlyphs.minOf { it.cellLeft } - dp(3f)
                val right = rowGlyphs.maxOf { it.cellRight } + dp(3f)
                val cy = rowGlyphs.first().cy
                val r = RectF(left, cy - dp(28f), right, cy + dp(22f))
                canvas.drawRoundRect(r, dp(8f), dp(8f), hlPaint)
            }
        }

        for (g in glyphs) {
            val active = g.barIndex == highlightBar
            drawGlyph(canvas, g, active)
        }
    }

    private fun drawGlyph(canvas: Canvas, g: Glyph, active: Boolean) {
        val note = g.note
        val color = if (active) colPrimary else colText
        numPaint.color = color
        numPaint.isFakeBoldText = active

        val label = if (note.isRest) "0" else note.degree.toString()
        canvas.drawText(label, g.cx, g.cy, numPaint)

        if (note.isRest) return

        // 八度点
        dotPaint.color = color
        val radius = dp(1.8f)
        if (note.octave > 0) {
            val baseY = g.cy - dp(16f)
            for (k in 0 until note.octave) {
                canvas.drawCircle(g.cx, baseY - k * dp(5f), radius, dotPaint)
            }
        } else if (note.octave < 0) {
            val baseY = g.cy + dp(8f)
            for (k in 0 until -note.octave) {
                canvas.drawCircle(g.cx, baseY + k * dp(5f), radius, dotPaint)
            }
        }

        // 琵琶指法符号：画在数字上方（行顶留出的区域），用几何图形绘制，不依赖字体。
        // 兼容老数据：若无 finger 但 tremolo=true，按「轮」画。
        val fg = note.finger.ifEmpty { if (note.tremolo) "lun" else "" }
        if (fg.isNotEmpty()) {
            val fc = if (active) colPrimary else colSecondary
            drawFinger(canvas, fg, g.cx, g.lineTop + dp(12f), fc)
        }

        // 时值
        linePaint.color = color
        if (note.dur <= 0.5 + 1e-6) {
            val underlines = if (note.dur <= 0.25 + 1e-6) 2 else 1
            var uy = g.cy + dp(6f)
            repeat(underlines) {
                canvas.drawLine(g.cellLeft + dp(4f), uy, g.cellRight - dp(4f), uy, linePaint)
                uy += dp(4f)
            }
        } else if (note.dur >= 2.0) {
            val dashes = when {
                note.dur >= 4.0 -> 3
                note.dur >= 3.0 -> 2
                else -> 1
            }
            var dx = g.cellRight
            repeat(dashes) {
                canvas.drawLine(dx + dp(6f), g.cy - dp(6f), dx + dp(28f), g.cy - dp(6f), linePaint)
                dx += dp(34f)
            }
        }
    }

    /**
     * 用几何图形画琵琶指法符号，中心 x=cx，纵向中心 y=cy。各机型一致。
     * 依通行琵琶记谱：弹=反斜线「\」，挑=正斜线「/」，轮=草书「卢」(近似螺旋)，
     * 半轮=「卢」加短撇，扫=向下∨，拂=向上∧，勾/抹用字，泛=空心圆圈。
     */
    private fun drawFinger(canvas: Canvas, code: String, cx: Float, cy: Float, color: Int) {
        trPaint.color = color
        trPaint.strokeWidth = dp(1.5f)
        fgPaint.color = color
        val w = dp(4.5f)
        when (code) {
            "tan" -> { // 弹：反斜线「\」(左上→右下)
                canvas.drawLine(cx - w, cy - dp(4f), cx + w, cy + dp(4f), trPaint)
            }
            "tiao" -> { // 挑：正斜线「/」(左下→右上)
                canvas.drawLine(cx - w, cy + dp(4f), cx + w, cy - dp(4f), trPaint)
            }
            "lun" -> { // 轮：五根线从一点放射，形如小花
                drawFlower(canvas, cx, cy, 5, color)
            }
            "changlun" -> { // 长轮：小花后加一点
                drawFlower(canvas, cx, cy, 5, color)
                dotPaint.color = color
                canvas.drawCircle(cx + dp(7f), cy + dp(2f), dp(1.6f), dotPaint)
            }
            "banlun" -> { // 半轮：三根线的半朵花
                drawFlower(canvas, cx, cy, 3, color)
            }
            "sao" -> { // 扫：向下的 ∨
                canvas.drawLine(cx - w, cy - dp(3f), cx, cy + dp(3f), trPaint)
                canvas.drawLine(cx, cy + dp(3f), cx + w, cy - dp(3f), trPaint)
            }
            "fu" -> { // 拂：向上的 ∧
                canvas.drawLine(cx - w, cy + dp(3f), cx, cy - dp(3f), trPaint)
                canvas.drawLine(cx, cy - dp(3f), cx + w, cy + dp(3f), trPaint)
            }
            "gou" -> { // 勾：用「勹」近似——竖折带钩
                val p = Path()
                p.moveTo(cx - dp(3f), cy - dp(4f))
                p.lineTo(cx + dp(3f), cy - dp(4f))
                p.quadTo(cx + dp(5f), cy, cx, cy + dp(4f))
                strokePath(canvas, p, color)
            }
            "mo" -> { // 抹：短横线（食指向里）
                canvas.drawLine(cx - w, cy, cx + w, cy, trPaint)
            }
            "fan" -> { // 泛：空心小圆
                strokeCircle(canvas, cx, cy, dp(3f), color)
            }
        }
    }

    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private fun strokePath(canvas: Canvas, p: Path, color: Int) {
        pathPaint.color = color
        canvas.drawPath(p, pathPaint)
    }

    private fun strokeCircle(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        pathPaint.color = color
        canvas.drawCircle(cx, cy, r, pathPaint)
    }

    /** 轮指「小花」：n 根短线从中心向上/两侧放射，模拟谱面轮指记号。 */
    private fun drawFlower(canvas: Canvas, cx: Float, cy: Float, n: Int, color: Int) {
        trPaint.color = color
        trPaint.strokeWidth = dp(1.3f)
        val len = dp(5f)
        // n 根线均匀分布在 -70°..+70°（朝上张开的扇形），形似小花
        val startDeg = -70.0
        val endDeg = 70.0
        val step = if (n > 1) (endDeg - startDeg) / (n - 1) else 0.0
        for (i in 0 until n) {
            val a = Math.toRadians(startDeg + step * i)
            val ex = cx + (Math.sin(a) * len).toFloat()
            val ey = cy - (Math.cos(a) * len).toFloat()
            canvas.drawLine(cx, cy + dp(2f), ex, ey, trPaint)
        }
    }
}
