package com.vampuck.pipa_trainer.practice

object PracticeFormat {
    fun duration(ms: Long): String {
        val seconds = ms.coerceAtLeast(0L) / 1_000L
        if (seconds == 0L) return "0 分钟"
        return listOf(seconds / 3_600L to "小时", seconds % 3_600L / 60L to "分钟",
            seconds % 60L to "秒").filter { it.first > 0L }
            .joinToString(" ") { (value, unit) -> "$value $unit" }
    }

    fun fullDate(day: String): String = PracticeDay.displayDate(day)
}
