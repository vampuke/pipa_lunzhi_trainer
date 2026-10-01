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

            "zhe" -> { // 摭：一对左右相对的弧「（ ）」
                val p1 = Path()
                p1.moveTo(cx - u * 0.05f, cy - u * 0.55f)
                p1.quadTo(cx - u * 0.55f, cy, cx - u * 0.05f, cy + u * 0.55f)
                canvas.drawPath(p1, stroke)
                val p2 = Path()
                p2.moveTo(cx + u * 0.05f, cy - u * 0.55f)
                p2.quadTo(cx + u * 0.55f, cy, cx + u * 0.05f, cy + u * 0.55f)
                canvas.drawPath(p2, stroke)
            }

            "sao" -> { // 扫：\ 主线 + 2 条短横穿线（羽状）
                backslash(0f)
                featherCross(canvas, cx, cy, u, stroke, backslashDir = true)
            }
            "fu" -> { // 拂：/ 主线 + 2 条短横穿线（羽状）
                slash(0f)
                featherCross(canvas, cx, cy, u, stroke, backslashDir = false)
            }

            "lun" -> star5(canvas, cx, cy, u, stroke)             // 轮：5 条放射线，中心空（不相连）
            "changlun" -> {                                        // 长轮：5 线星芒 + 右侧三条「一字排开」短横
                star5(canvas, cx, cy, u, stroke)
                val y = cy
                val seg = u * 0.26f
                val gap = u * 0.16f
                var sx = cx + u * 0.72f
                for (k in 0 until 3) {
                    canvas.drawLine(sx, y, sx + seg, y, stroke)
                    sx += seg + gap
                }
            }
            "banlun" -> starHalf(canvas, cx, cy, u, stroke, fill)  // 半轮：半星芒

            "gun" -> { // 滚：≰ 形——两条左上→右下的斜撇交于一点（似「尤」上端）
                val len = u * 0.55f
                canvas.drawLine(cx - len, cy + len * 0.6f, cx, cy - len * 0.4f, stroke)
                canvas.drawLine(cx + len, cy + len * 0.6f, cx, cy - len * 0.4f, stroke)
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

    /** 轮指「五线放射」：5 条短线呈放射，中心留空（线不交于一点）。 */
    private fun star5(canvas: Canvas, cx: Float, cy: Float, u: Float, stroke: Paint) {
        val inner = u * 0.16f   // 内端离中心，保证中间空
        val outer = u * 0.56f
        // 5 条均布，整体略偏上张开（像一朵小花）
        val base = -90.0        // 从正上方开始
        for (i in 0 until 5) {
            val a = Math.toRadians(base + 360.0 * i / 5)
            val sx = cx + (Math.cos(a) * inner).toFloat()
            val sy = cy + (Math.sin(a) * inner).toFloat()
            val ex = cx + (Math.cos(a) * outer).toFloat()
            val ey = cy + (Math.sin(a) * outer).toFloat()
            canvas.drawLine(sx, sy, ex, ey, stroke)
        }
    }

    /** 半轮：只画 X 两条斜线的星爆（比全轮少）。 */
    private fun starHalf(canvas: Canvas, cx: Float, cy: Float, u: Float, stroke: Paint, fill: Paint) {
        val len = u * 0.5f
        for (deg in intArrayOf(45, 135)) {
            val a = Math.toRadians(deg.toDouble())
            val dx = (Math.cos(a) * len).toFloat()
            val dy = (Math.sin(a) * len).toFloat()
            canvas.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, stroke)
        }
        canvas.drawCircle(cx, cy, u * 0.09f, fill)
    }
}
