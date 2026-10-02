package com.vampuck.pipa_trainer.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import com.google.android.material.button.MaterialButton

/**
 * 节拍器「拍型」切换按钮：只画一个符号，不写文字。
 *
 *  - 四分音符 / 八分音符：本类直接画（符头 + 符干，八分多一条符尾）。
 *  - 轮指：调用 [FingerSymbols] 的 `lun`，与谱面里画出来的轮指标记**同源**，
 *    所以按钮上看到的和谱子里看到的一致。
 *
 * 颜色取 [currentTextColor]，跟随按钮的选中/未选中态（颜色状态列表）。
 */
class SymbolButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialButton(context, attrs, defStyleAttr) {

    enum class Symbol { QUARTER, EIGHTH, LUN }

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
            Symbol.QUARTER -> drawNote(canvas, cx, cy, u, eighth = false, color = color)
            Symbol.EIGHTH -> drawNote(canvas, cx, cy, u, eighth = true, color = color)
        }
    }

    /** 音符：符头（略斜的实心椭圆）+ 符干；八分再加一条向右下的符尾。 */
    private fun drawNote(canvas: Canvas, cx: Float, cy: Float, u: Float, eighth: Boolean, color: Int) {
        fill.color = color
        stroke.color = color

        val headR = u * 0.30f
        val headCx = cx - u * 0.16f
        val headCy = cy + u * 0.36f
        canvas.save()
        canvas.rotate(-20f, headCx, headCy)
        canvas.drawOval(
            headCx - headR, headCy - headR * 0.74f,
            headCx + headR, headCy + headR * 0.74f, fill
        )
        canvas.restore()

        val stemX = headCx + headR * 0.90f
        val stemTop = headCy - u * 1.04f
        stroke.strokeWidth = u * 0.13f
        canvas.drawLine(stemX, headCy - headR * 0.15f, stemX, stemTop, stroke)

        if (eighth) {
            stroke.strokeWidth = u * 0.15f
            val flag = Path()
            flag.moveTo(stemX, stemTop)
            flag.quadTo(
                stemX + u * 0.54f, stemTop + u * 0.20f,
                stemX + u * 0.28f, stemTop + u * 0.80f
            )
            canvas.drawPath(flag, stroke)
        }
    }
}
