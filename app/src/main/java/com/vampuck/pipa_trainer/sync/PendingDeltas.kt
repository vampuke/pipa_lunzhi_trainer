package com.vampuck.pipa_trainer.sync

import com.vampuck.pipa_trainer.practice.PracticeDay
import com.vampuck.pipa_trainer.practice.PracticeSource

data class Delta(val day: String, val source: PracticeSource, val ms: Long)

/** Local practice not yet acknowledged by the current account's uploads. */
object PendingDeltas {
    fun fromSegments(
        entries: List<UploadSegment>,
        userId: String,
        claimedBy: (String) -> String?,
        uploadedMs: (String) -> Long,
        skipped: (String) -> Boolean
    ): List<Delta> {
        require(userId.isNotBlank())
        val totals = linkedMapOf<Pair<String, PracticeSource>, Long>()
        for (entry in entries) {
            if (entry.elapsedMs <= 0L || !PracticeDay.isValid(entry.day) || skipped(entry.id)) continue
            val owner = claimedBy(entry.id)
            if (owner != null && owner != userId) continue
            val ms = if (owner == null) entry.elapsedMs else {
                val uploaded = uploadedMs(entry.id)
                if (entry.elapsedMs <= uploaded) continue
                entry.elapsedMs - uploaded
            }
            if (ms <= 0L) continue
            val key = entry.day to entry.source
            totals[key] = (totals[key] ?: 0L) + ms
        }
        return totals.map { (key, ms) -> Delta(key.first, key.second, ms) }
    }
}
