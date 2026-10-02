package com.vampuck.pipa_trainer.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import com.google.android.material.button.MaterialButton

/**
 * 节拍器「拍型」切换按钮：只画一个符号，不写文字。
 *
 *  - 四分音符：符头 + 符干。
 *  - 二八（一拍两个八分音符）：两个符头、两条符干，顶端用一条**符杠**连起来
 *    —— 这才是「每拍响两下」在谱面上的样子，和单个八分音符不会看混。
 *  - 轮指：调用 [FingerSymbols] 的 `lun`，与谱面里画出来的轮指标记**同源**。
 *
 * 颜色取 [currentTextColor]，跟随按钮的选中/未选中态（颜色状态列表）。
 */
class SymbolButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialButton(context, attrs, defStyleAttr) {

    enum class Symbol { QUARTER, EIGHTH_PAIR, LUN }

    var symbol: Symbol = Symbol.QUARTER
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)                    // 先画 MaterialButton 的底/边框
        val color = currentTextColor
        val cx = width / 2f
        val cy = height / 2f
        val u = density * 12f                   // 与编辑器调色板同一基准
        when (symbol) {
            Symbol.LUN -> FingerSymbols.draw(canvas, "lun", cx, cy, u, color)
            Symbol.QUARTER -> drawQuarter(canvas, cx, cy, u, color)
            Symbol.EIGHTH_PAIR -> drawBeamedPair(canvas, cx, cy, u, color)
        }
    }

    /** 四分音符：实心符头 + 右侧竖直符干。 */
    private fun drawQuarter(canvas: Canvas, cx: Float, cy: Float, u: Float, color: Int) {
        val r = u * 0.30f
        val headCx = cx - u * 0.16f
        val headCy = cy + u * 0.36f
        head(canvas, headCx, headCy, r, color)

        val stemX = headCx + r * 0.90f
        val stemTop = headCy - u * 1.04f
        stroke.strokeWidth = u * 0.13f
        stroke.color = color
        canvas.drawLine(stemX, headCy - r * 0.15f, stemX, stemTop, stroke)
    }

    /**
     * 二八：一拍里的两个八分音符。两个符头 + 两条符干，顶端一条符杠相连
     * （谱面上同一拍的两个八分音符就是这么写的）。
     */
    private fun drawBeamedPair(canvas: Canvas, cx: Float, cy: Float, u: Float, color: Int) {
        val r = u * 0.27f
        val headCy = cy + u * 0.36f
        val stemTop = cy - u * 0.96f
        val dx = u * 0.66f
        stroke.color = color
        stroke.strokeWidth = u * 0.12f

        var leftStem = 0f
        var rightStem = 0f
        for (dir in intArrayOf(-1, 1)) {
            val headCx = cx + dir * dx
            head(canvas, headCx, headCy, r, color)
            val stemX = headCx + r * 0.90f
            canvas.drawLine(stemX, headCy - r * 0.15f, stemX, stemTop, stroke)
            if (dir < 0) leftStem = stemX else rightStem = stemX
        }

        // 符杠：比符干粗一些的横条，两端略微超出符干
        fill.color = color
        val over = u * 0.07f
        canvas.drawRect(leftStem - over, stemTop, rightStem + over, stemTop + u * 0.26f, fill)
    }

    /** 实心符头：略向右上倾斜的椭圆。 */
    private fun head(canvas: Canvas, hx: Float, hy: Float, r: Float, color: Int) {
        fill.color = color
        canvas.save()
        canvas.rotate(-20f, hx, hy)
        canvas.drawOval(hx - r, hy - r * 0.74f, hx + r, hy + r * 0.74f, fill)
        canvas.restore()
    }
}
