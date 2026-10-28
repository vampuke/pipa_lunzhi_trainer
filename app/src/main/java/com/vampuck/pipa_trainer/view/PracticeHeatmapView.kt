package com.vampuck.pipa_trainer.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.vampuck.pipa_trainer.R
import com.vampuck.pipa_trainer.practice.PracticeDay
import com.vampuck.pipa_trainer.practice.PracticeLog
import com.vampuck.pipa_trainer.practice.PracticeStats

/** GitHub-style year grid. Horizontal drags belong to the parent scroll view. */
class PracticeHeatmapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val cell = dp(12f)
    private val gap = dp(3f)
    private val labelWidth = dp(24f)
    private val topSpace = dp(20f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val colors = intArrayOf(R.color.activity_0, R.color.activity_1,
        R.color.activity_2, R.color.activity_3, R.color.activity_4).map { context.getColor(it) }
    private val textColor = context.getColor(R.color.text_secondary)
    private var log = PracticeLog()
    private var today = PracticeDay.today()
    private var firstMonday = PracticeDay.shift(today, -364)
    private var weeks = 53
    private var hitDays = emptyList<Pair<RectF, String>>()
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    var onDayClick: ((String) -> Unit)? = null

    init {
        minimumHeight = (topSpace + 7 * (cell + gap) + dp(4f)).toInt()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun submit(log: PracticeLog, today: String = PracticeDay.today()) {
        this.log = log
        this.today = today
        val first = PracticeDay.shift(today, -364)
        // Monday-first columns, without java.time (the app supports API 24+).
        firstMonday = PracticeDay.shift(first, -(PracticeDay.mondayOffset(first)))
        weeks = (PracticeDay.daysBetween(firstMonday, today) + 1 + 6) / 7
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
        val hits = ArrayList<Pair<RectF, String>>()
        paint.textSize = dp(9f)
        paint.typeface = android.graphics.Typeface.DEFAULT
        var previousMonth = ""
        for (week in 0 until weeks) {
            val monday = PracticeDay.shift(firstMonday, week * 7)
            if (monday <= today && PracticeDay.month(monday) != previousMonth) {
                paint.color = textColor
                canvas.drawText("${PracticeDay.month(monday)}月",
                    labelWidth + week * (cell + gap), dp(10f), paint)
                previousMonth = PracticeDay.month(monday)
            }
            for (row in 0 until 7) {
                val day = PracticeDay.shift(monday, row)
                if (day < firstMonday || day > today) continue
                val left = labelWidth + week * (cell + gap)
                val top = topSpace + row * (cell + gap)
                rect.set(left, top, left + cell, top + cell)
                paint.color = colors[PracticeStats.level(log.totalMs(day))]
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
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(event.x - downX) > touchSlop &&
                    kotlin.math.abs(event.x - downX) > kotlin.math.abs(event.y - downY)) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (kotlin.math.abs(event.x - downX) <= touchSlop &&
                    kotlin.math.abs(event.y - downY) <= touchSlop) {
                    val hit = hitDays.lastOrNull { it.first.contains(event.x, event.y) }
                    if (hit != null) { onDayClick?.invoke(hit.second); performClick() }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    private fun dp(value: Float) = value * resources.displayMetrics.density
}
