package com.vampuck.pipa_trainer.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.vampuck.pipa_trainer.R
import com.vampuck.pipa_trainer.practice.PracticeLog
import com.vampuck.pipa_trainer.practice.PracticeStats
import java.time.LocalDate

/** GitHub-style year grid: Monday-first rows, horizontally scrollable on phones. */
class PracticeHeatmapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val cell = dp(12f)
    private val gap = dp(3f)
    private val labelWidth = dp(24f)
    private val topSpace = dp(20f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val colors = intArrayOf(
        R.color.activity_0, R.color.activity_1, R.color.activity_2,
        R.color.activity_3, R.color.activity_4
    ).map { context.getColor(it) }
    private val textColor = context.getColor(R.color.text_secondary)
    private var log = PracticeLog()
    private var today: LocalDate = LocalDate.now()
    private var firstMonday = today.minusDays(364)
    private var weeks = 53
    private var hitDays = emptyList<Pair<RectF, LocalDate>>()
    var onDayClick: ((String) -> Unit)? = null

    init {
        minimumHeight = (topSpace + 7 * (cell + gap) + dp(4f)).toInt()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun submit(log: PracticeLog, today: LocalDate = LocalDate.now()) {
        this.log = log
        this.today = today
        val first = today.minusDays(364)
        firstMonday = first.minusDays((first.dayOfWeek.value - 1).toLong())
        weeks = ((java.time.temporal.ChronoUnit.DAYS.between(firstMonday, today).toInt() + 1 + 6) / 7)
        val width = (labelWidth + weeks * (cell + gap) + dp(2f)).toInt()
        minimumWidth = width
        layoutParams = layoutParams.apply { this.width = width }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = (labelWidth + weeks * (cell + gap) + dp(2f)).toInt()
        setMeasuredDimension(resolveSize(width, widthMeasureSpec),
            resolveSize(minimumHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val hits = ArrayList<Pair<RectF, LocalDate>>()
        paint.textSize = dp(9f)
        paint.typeface = android.graphics.Typeface.DEFAULT
        var previousMonth = -1
        for (week in 0 until weeks) {
            val monday = firstMonday.plusDays((week * 7).toLong())
            if (monday.monthValue != previousMonth && monday <= today) {
                paint.color = textColor
                canvas.drawText("${monday.monthValue}月", labelWidth + week * (cell + gap), dp(10f), paint)
                previousMonth = monday.monthValue
            }
            for (row in 0 until 7) {
                val day = monday.plusDays(row.toLong())
                if (day < firstMonday || day > today) continue
                val left = labelWidth + week * (cell + gap)
                val top = topSpace + row * (cell + gap)
                rect.set(left, top, left + cell, top + cell)
                paint.color = colors[PracticeStats.level(log.totalMs(day.toString()))]
                canvas.drawRoundRect(rect, dp(2f), dp(2f), paint)
                hits.add(RectF(rect) to day)
            }
        }
        paint.color = textColor
        for ((row, label) in listOf(1 to "一", 3 to "三", 5 to "五")) {
            canvas.drawText(label, 0f, topSpace + row * (cell + gap) + dp(10f), paint)
        }
        hitDays = hits
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val hit = hitDays.lastOrNull { it.first.contains(event.x, event.y) } ?: return true
        onDayClick?.invoke(hit.second.toString())
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
