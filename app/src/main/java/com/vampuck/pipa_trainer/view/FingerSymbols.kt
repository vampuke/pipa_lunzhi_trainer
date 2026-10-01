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
        val h = u * 0.5f

        // 单条「\」(弹)：左上→右下
        fun backslash(ox: Float) =
            canvas.drawLine(cx + ox - w, cy - h, cx + ox + w, cy + h, stroke)
        // 单条「/」(挑)：左下→右上
        fun slash(ox: Float) =
            canvas.drawLine(cx + ox - w, cy + h, cx + ox + w, cy - h, stroke)

        when (code) {
            "tan" -> backslash(0f)                       // 弹：\
            "tiao" -> slash(0f)                           // 挑：/
            "shuangtan" -> { backslash(-u * 0.3f); backslash(u * 0.3f) }   // 双弹：\\
            "shuangtiao" -> { slash(-u * 0.3f); slash(u * 0.3f) }         // 双挑：//
            "fen" -> slash(0f)                            // 分：/（同挑向）

            "zhe" -> { // 摭：右括号弧「)」
                val p = Path()
                p.moveTo(cx - u * 0.1f, cy - u * 0.55f)
                p.quadTo(cx + u * 0.55f, cy, cx - u * 0.1f, cy + u * 0.55f)
                canvas.drawPath(p, stroke)
            }

            "sao" -> { // 扫：\ 主线 + 2 条短横穿线（羽状）
                backslash(0f)
                featherCross(canvas, cx, cy, u, stroke, backslashDir = true)
            }
            "fu" -> { // 拂：/ 主线 + 2 条短横穿线（羽状）
                slash(0f)
                featherCross(canvas, cx, cy, u, stroke, backslashDir = false)
            }

            "lun" -> flower(canvas, cx, cy, u, stroke, fill)       // 轮：放射星芒
            "changlun" -> {                                        // 长轮：星芒 + 右点
                flower(canvas, cx, cy, u, stroke, fill)
                canvas.drawCircle(cx + u * 1.0f, cy, u * 0.16f, fill)
            }
            "banlun" -> flowerHalf(canvas, cx, cy, u, stroke, fill) // 半轮：半星芒

            "gun" -> { // 滚：/// 三条平行斜线
                slash(-u * 0.42f); slash(0f); slash(u * 0.42f)
            }

            "gou" -> { // 勾：横折带下钩「勹」
                val p = Path()
                p.moveTo(cx - u * 0.4f, cy - u * 0.45f)
                p.lineTo(cx + u * 0.4f, cy - u * 0.45f)
                p.quadTo(cx + u * 0.6f, cy, cx, cy + u * 0.5f)
                canvas.drawPath(p, stroke)
            }
            "mo" -> canvas.drawLine(cx - w, cy, cx + w, cy, stroke)   // 抹：横线
            "fan" -> canvas.drawCircle(cx, cy, u * 0.4f, stroke)      // 泛：○
        }
    }

    /** 扫/拂的羽状穿线：在主斜线中段叠 2 条与之交叉的短线。 */
    private fun featherCross(
        canvas: Canvas, cx: Float, cy: Float, u: Float, stroke: Paint, backslashDir: Boolean
    ) {
        // 短穿线方向与主线垂直，分别落在主线中上、中下两处
        val s = u * 0.26f
        val offs = floatArrayOf(-u * 0.22f, u * 0.22f)
        for (o in offs) {
            // 主线上该点坐标
            val px = cx + o
            val py = if (backslashDir) cy + o else cy - o
            if (backslashDir) {
                // 主线 \，穿线用 /
                canvas.drawLine(px - s, py + s, px + s, py - s, stroke)
            } else {
                // 主线 /，穿线用 \
                canvas.drawLine(px - s, py - s, px + s, py + s, stroke)
            }
        }
    }

    /** 轮指「星芒」：多条短线从中心整圆放射（图中约 5 条），中心一点。 */
    private fun flower(canvas: Canvas, cx: Float, cy: Float, u: Float, stroke: Paint, fill: Paint) {
        val len = u * 0.52f
        val n = 6
        for (i in 0 until n) {
            val a = Math.toRadians(360.0 * i / n)
            canvas.drawLine(
                cx, cy,
                cx + (Math.cos(a) * len).toFloat(),
                cy + (Math.sin(a) * len).toFloat(),
                stroke
            )
        }
        canvas.drawCircle(cx, cy, u * 0.1f, fill)
    }

    /** 半轮：上半的 3 条放射。 */
    private fun flowerHalf(canvas: Canvas, cx: Float, cy: Float, u: Float, stroke: Paint, fill: Paint) {
        val len = u * 0.52f
        for (deg in intArrayOf(180, 225, 270, 315, 360)) {
            val a = Math.toRadians(deg.toDouble())
            canvas.drawLine(
                cx, cy,
                cx + (Math.cos(a) * len).toFloat(),
                cy + (Math.sin(a) * len).toFloat(),
                stroke
            )
        }
        canvas.drawCircle(cx, cy, u * 0.1f, fill)
    }
}
