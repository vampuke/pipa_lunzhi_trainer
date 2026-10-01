package com.vampuck.pipa_trainer.view

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * 琵琶指法符号的统一绘制。谱面(JianpuView)和编辑器调色板(FingerGlyphView)都调用它，
 * 保证「按钮上看到的」与「谱子里画出来的」完全一致。
 *
 * 所有坐标以 (cx,cy) 为中心，scale 控制整体大小（1f≈谱面默认尺寸）。
 */
object FingerSymbols {

    /**
     * @param code  指法 code（见 Fingerings）
     * @param cx,cy 中心坐标（px）
     * @param u     单位长度（px），符号整体按它缩放（谱面传 density*dp 后的基准）
     */
    fun draw(canvas: Canvas, code: String, cx: Float, cy: Float, u: Float, color: Int) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = u * 0.14f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val w = u * 0.5f

        when (code) {
            "tan" -> // 弹：反斜线「\」(左上→右下)
                canvas.drawLine(cx - w, cy - u * 0.45f, cx + w, cy + u * 0.45f, stroke)

            "tiao" -> // 挑：正斜线「/」(左下→右上)
                canvas.drawLine(cx - w, cy + u * 0.45f, cx + w, cy - u * 0.45f, stroke)

            "lun" -> flower(canvas, cx, cy, 5, u, stroke)          // 轮：五瓣小花

            "changlun" -> {                                        // 长轮：小花 + 右侧点
                flower(canvas, cx, cy, 5, u, stroke)
                canvas.drawCircle(cx + u * 0.95f, cy, u * 0.17f, fill)
            }

            "banlun" -> flower(canvas, cx, cy, 3, u, stroke)       // 半轮：三瓣

            "sao" -> { // 扫：向下 ∨
                canvas.drawLine(cx - w, cy - u * 0.4f, cx, cy + u * 0.4f, stroke)
                canvas.drawLine(cx, cy + u * 0.4f, cx + w, cy - u * 0.4f, stroke)
            }

            "fu" -> { // 拂：向上 ∧
                canvas.drawLine(cx - w, cy + u * 0.4f, cx, cy - u * 0.4f, stroke)
                canvas.drawLine(cx, cy - u * 0.4f, cx + w, cy + u * 0.4f, stroke)
            }

            "gou" -> { // 勾：横折带下钩（勹）
                val p = Path()
                p.moveTo(cx - u * 0.4f, cy - u * 0.45f)
                p.lineTo(cx + u * 0.4f, cy - u * 0.45f)
                p.quadTo(cx + u * 0.6f, cy, cx, cy + u * 0.5f)
                canvas.drawPath(p, stroke)
            }

            "mo" -> // 抹：短横线
                canvas.drawLine(cx - w, cy, cx + w, cy, stroke)

            "fan" -> // 泛：空心小圆
                canvas.drawCircle(cx, cy, u * 0.4f, stroke)
        }
    }

    /**
     * 轮指「小花」：n 根短线从中心向四周均匀放射（整圆分布），形如小花/星芒。
     * 这是琵琶谱里轮指的通行记号形态。
     */
    private fun flower(canvas: Canvas, cx: Float, cy: Float, n: Int, u: Float, stroke: Paint) {
        val len = u * 0.5f
        for (i in 0 until n) {
            // 从正上方起，均匀分布 360°/n；5 瓣即五角星芒
            val a = Math.toRadians(-90.0 + 360.0 * i / n)
            val ex = cx + (Math.cos(a) * len).toFloat()
            val ey = cy + (Math.sin(a) * len).toFloat()
            canvas.drawLine(cx, cy, ex, ey, stroke)
        }
        // 中心小点，让放射线像从花心长出
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = stroke.color }
        canvas.drawCircle(cx, cy, u * 0.1f, fill)
    }
}
