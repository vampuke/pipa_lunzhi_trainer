package com.vampuck.pipa_trainer.sync

import android.content.Context

/** Account and upload metadata only; practice_activity is never edited here. */
class ClaimStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("practice_sync", Context.MODE_PRIVATE)
    val userId: String? get() = prefs.getString("user_id", null)
    val username: String? get() = prefs.getString("username", null)
    val lastSyncAt: Long get() = prefs.getLong("last_sync_at", 0L)
    val localOnlySegments: Int get() = prefs.getInt("local_only_segments", 0)

    fun claimedBy(segmentId: String): String? = prefs.getString("claimed:$segmentId", null)

    /** A legacy claim without upload metadata must not be counted again. */
    fun uploadedMs(segmentId: String): Long = prefs.getLong("uploaded_ms:$segmentId", Long.MAX_VALUE)

    fun skipped(segmentId: String): Boolean = prefs.getBoolean("skipped:$segmentId", false)

    @Synchronized
    fun claim(segmentId: String, userId: String) {
        require(userId.isNotBlank())
        val owner = claimedBy(segmentId)
        if (owner == null || owner == userId) prefs.edit().putString("claimed:$segmentId", userId).apply()
    }

    fun unclaimed(entries: List<UploadSegment>, userId: String): List<UploadSegment> =
        SegmentQueue.unclaimed(entries, userId, ::claimedBy)

    /** A checkpoint may grow while a delayed sync is in flight. Only its owner can refresh it. */
    fun changedOwnedEntries(entries: List<UploadSegment>, userId: String): List<UploadSegment> = entries.filter {
        SegmentQueue.needsRefresh(it, userId, claimedBy(it.id),
            uploadedMs(it.id), skipped(it.id))
    }

    @Synchronized
    fun claimSkipped(segment: UploadSegment, userId: String) {
        if (claimedBy(segment.id) != null) return
        prefs.edit().putString("claimed:${segment.id}", userId)
            .putBoolean("skipped:${segment.id}", true).apply()
    }

    @Synchronized
    fun accepted(segments: List<UploadSegment>, userId: String) {
        val editor = prefs.edit()
        val merging = prefs.getBoolean("merge_pending:$userId", false)
        val mergedDays = prefs.getStringSet("merge_days:$userId", emptySet()).orEmpty().toMutableSet()
        for (segment in segments) {
            val owner = claimedBy(segment.id)
            if (owner != null && owner != userId) continue
            editor.putString("claimed:${segment.id}", userId)
                .putLong("uploaded_ms:${segment.id}", maxOf(prefs.getLong("uploaded_ms:${segment.id}", 0L), segment.elapsedMs))
            if (owner == null && merging) mergedDays.add(segment.day)
        }
        if (merging) editor.putStringSet("merge_days:$userId", mergedDays)
        editor.apply()
    }

    @Synchronized
    fun setAccount(session: AccountSession) {
        val editor = prefs.edit()
        if (userId != session.userId) {
            editor.remove("cloud_summary").remove("last_sync_at").remove("local_only_segments")
        }
        editor.putString("user_id", session.userId).putString("username", session.username)
        if (!prefs.getBoolean("merge_done:${session.userId}", false)) {
            editor.putBoolean("merge_pending:${session.userId}", true)
        }
        editor.apply()
    }

    @Synchronized
    fun saveSnapshot(snapshot: CloudSnapshot, localOnlySegments: Int) {
        require(snapshot.userId == userId)
        val editor = prefs.edit().putString("cloud_summary", CloudCache.encode(snapshot.days))
            .putLong("last_sync_at", snapshot.fetchedAtMs).putInt("local_only_segments", localOnlySegments)
        if (prefs.getBoolean("merge_pending:${snapshot.userId}", false)) {
            val count = prefs.getStringSet("merge_days:${snapshot.userId}", emptySet()).orEmpty().size
            editor.putBoolean("merge_done:${snapshot.userId}", true)
                .remove("merge_pending:${snapshot.userId}").remove("merge_days:${snapshot.userId}")
            if (count > 0) editor.putInt("merge_notice:${snapshot.userId}", count)
        }
        editor.apply()
    }

    fun snapshot(): CloudSnapshot? {
        val id = userId ?: return null
        val time = lastSyncAt.takeIf { it > 0L } ?: return null
        val days = CloudCache.decode(prefs.getString("cloud_summary", null) ?: return null) ?: return null
        return CloudSnapshot(id, time, days)
    }

    @Synchronized
    fun takeMergeNotice(userId: String): Int {
        val count = prefs.getInt("merge_notice:$userId", 0)
        if (count > 0) prefs.edit().remove("merge_notice:$userId").apply()
        return count
    }

    @Synchronized
    fun clearAccount() {
        prefs.edit().remove("user_id").remove("username").remove("last_sync_at")
            .remove("cloud_summary").remove("local_only_segments").apply()
    }
}
