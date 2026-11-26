package com.vampuck.pipa_trainer.practice

import com.vampuck.pipa_trainer.sync.CloudSnapshot
import com.vampuck.pipa_trainer.sync.Delta
import com.vampuck.pipa_trainer.sync.SyncState

/** One immutable choice is used by the totals, heatmap and day detail together. */
data class ActivityViewData(val log: PracticeLog, val isCloud: Boolean) {
    fun totalMs(day: String, source: PracticeSource? = null): Long = log.totalMs(day, source)
    fun totalMsSince(firstDay: String): Long = log.totalMsSince(firstDay)
    fun streak(today: String): Int = PracticeStats.streak(log, today)

    companion object {
        fun choose(
            local: PracticeLog,
            userId: String?,
            cloud: CloudSnapshot?,
            state: SyncState,
            deltas: List<Delta> = emptyList()
        ): ActivityViewData {
            val showCloud = cloud != null && userId != null && cloud.userId == userId &&
                (state == SyncState.OK || state == SyncState.OFFLINE)
            if (!showCloud) return ActivityViewData(local, false)
            val log = requireNotNull(cloud).toPracticeLog()
            for (delta in deltas) {
                log.put("pending:${delta.day}:${delta.source.key}", delta.source, delta.day, delta.ms)
            }
            return ActivityViewData(log, true)
        }
    }
}
