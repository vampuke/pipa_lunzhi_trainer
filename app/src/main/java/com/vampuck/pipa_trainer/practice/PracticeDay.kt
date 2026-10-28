package com.vampuck.pipa_trainer.practice

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** API-24-safe local calendar. Date labels are always ISO yyyy-MM-dd. */
object PracticeDay {
    private fun format() = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    fun today(): String = at(System.currentTimeMillis())
    fun at(epochMs: Long): String = format().format(Date(epochMs))
    fun parse(day: String): Date? {
        if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(day)) return null
        val f = format().apply { isLenient = false }
        return try { f.parse(day)?.takeIf { f.format(it) == day } }
        catch (_: ParseException) { null }
    }
    fun isValid(day: String) = parse(day) != null
    fun shift(day: String, days: Int): String {
        val date = parse(day) ?: return day
        return Calendar.getInstance().run {
            time = date; add(Calendar.DAY_OF_YEAR, days); at(timeInMillis)
        }
    }
    fun daysBetween(first: String, last: String): Int {
        val a = parse(first) ?: return 0
        val b = parse(last) ?: return 0
        return TimeUnit.MILLISECONDS.toDays(b.time - a.time).toInt()
    }
    fun month(day: String): String = day.substring(5, 7).toInt().toString()
    fun weekdayLabel(day: String): String {
        val date = parse(day) ?: return day
        val index = Calendar.getInstance().run { time = date; get(Calendar.DAY_OF_WEEK) }
        return arrayOf("日", "一", "二", "三", "四", "五", "六")[index - 1]
    }
    fun mondayOffset(day: String): Int {
        val date = parse(day) ?: return 0
        // Calendar Monday=2..Sunday=1 -> days since Monday 0..6.
        return (Calendar.getInstance().run { time = date; get(Calendar.DAY_OF_WEEK) } + 5) % 7
    }
    fun displayDate(day: String): String =
        "${day.substring(5, 7).toInt()}月${day.substring(8, 10).toInt()}日 · 星期${weekdayLabel(day)}"
    fun nextDayStart(epochMs: Long): Long = Calendar.getInstance().apply {
        timeInMillis = epochMs
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
}
