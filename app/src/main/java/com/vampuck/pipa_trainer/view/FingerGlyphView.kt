package com.vampuck.pipa_trainer.view

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View

/**
 * 单个指法符号的小预览控件：用 FingerSymbols 绘制，与谱面完全一致。
 * 编辑器调色板用它做按钮图标，保证「按钮上看到的」=「谱子里画出来的」。
 */
class FingerGlyphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    var code: String = ""
        set(value) { field = value; invalidate() }
    var glyphColor: Int = 0xFF231A16.toInt()
        set(value) { field = value; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val sz = (density * 26).toInt()
        setMeasuredDimension(
            resolveSize(sz, widthMeasureSpec),
            resolveSize(sz, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (code.isEmpty()) return
        val cx = width / 2f
        val cy = height / 2f
        FingerSymbols.draw(canvas, code, cx, cy, density * 12f, glyphColor)
    }
}
