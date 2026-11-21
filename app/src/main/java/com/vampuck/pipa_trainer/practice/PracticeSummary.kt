package com.vampuck.pipa_trainer.practice

/** Daily details with labels supplied by the Android resources. */
object PracticeSummary {
    fun day(log: PracticeLog, day: String, totalLabel: String,
            labels: Map<PracticeSource, Pair<String, String>>, localNote: String): String =
        "$totalLabel：${PracticeFormat.duration(log.totalMs(day))}\n\n" +
            PracticeSource.values().joinToString("\n\n") { source ->
                val (label, note) = labels.getValue(source)
                "$label：${PracticeFormat.duration(log.totalMs(day, source))}\n$note"
            } + "\n\n$localNote"
}
