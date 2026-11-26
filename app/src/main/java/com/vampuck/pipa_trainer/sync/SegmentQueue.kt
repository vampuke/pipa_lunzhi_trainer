package com.vampuck.pipa_trainer.sync

import com.vampuck.pipa_trainer.practice.PracticeLog
import com.vampuck.pipa_trainer.practice.PracticeDay
import com.vampuck.pipa_trainer.practice.PracticeSource

data class UploadSegment(
    val id: String,
    val day: String,
    val source: PracticeSource,
    val elapsedMs: Long
)

data class SegmentPlan(
    val uploadable: List<UploadSegment>,
    val skipped: List<UploadSegment>,
    val localOnly: List<UploadSegment>
)

object SegmentQueue {
    const val BATCH_SIZE = 200

    fun segmentId(deviceId: String, localKey: String): String = "d:$deviceId:$localKey"

    fun fromEntries(deviceId: String, entries: List<Pair<String, PracticeLog.Entry>>): List<UploadSegment> =
        entries.map { (key, entry) ->
            UploadSegment(segmentId(deviceId, key), entry.day, entry.source, entry.elapsedMs)
        }

    /** A claim belongs to one account forever, including after logout. */
    fun unclaimed(
        entries: List<UploadSegment>,
        userId: String,
        claimedBy: (String) -> String?
    ): List<UploadSegment> {
        require(userId.isNotBlank())
        return entries.filter { claimedBy(it.id) == null }
    }

    fun batches(entries: List<UploadSegment>): List<List<UploadSegment>> = entries.chunked(BATCH_SIZE)

    fun plan(entries: List<UploadSegment>, utcToday: String): SegmentPlan {
        require(PracticeDay.isValid(utcToday))
        val (skipped, positive) = entries.partition { it.elapsedMs <= 0L }
        val from = PracticeDay.shift(utcToday, -400)
        val to = PracticeDay.shift(utcToday, 1)
        val (uploadable, localOnly) = positive.partition {
            PracticeDay.isValid(it.day) && it.day >= from && it.day <= to &&
                it.elapsedMs <= 86_400_000L && it.id.length in 1..160 && it.id.all { char -> char in ' '..'~' }
        }
        return SegmentPlan(uploadable, skipped, localOnly)
    }

    fun needsRefresh(entry: UploadSegment, userId: String, owner: String?, uploadedMs: Long, skipped: Boolean): Boolean =
        owner == userId && !skipped && entry.elapsedMs > uploadedMs

    /** A failed/partial HTTP request never acknowledges a batch; replay is safe on the server. */
    fun upload(
        entries: List<UploadSegment>,
        send: (List<UploadSegment>) -> Unit,
        accepted: (List<UploadSegment>) -> Unit
    ): Int {
        var count = 0
        for (batch in batches(entries)) {
            send(batch)
            accepted(batch)
            count += batch.size
        }
        return count
    }
}
