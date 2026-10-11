package com.vampuck.pipa_trainer

import com.vampuck.pipa_trainer.data.PieceSection
import com.vampuck.pipa_trainer.data.PracticePiece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯逻辑：跟练曲目总时长估算（含 bpm=0 的兜底，防 Int.MAX_VALUE 爆表）。 */
class PracticePieceTest {

    private fun piece(vararg beats: Int) = PracticePiece(
        id = "x", title = "t", composerOrStyle = "s", refBpm = 60,
        difficulty = "d", blurb = "b",
        sections = beats.map { PieceSection("sec", it) }
    )

    @Test
    fun `totalSeconds is beats times 60 over bpm`() {
        // 120 拍 @ 60bpm = 120 秒
        assertEquals(120, piece(60, 60).totalSeconds(60))
        // 同样拍数更快速度 = 更短
        assertEquals(60, piece(60, 60).totalSeconds(120))
    }

    @Test
    fun `bpm zero does not overflow to Int MAX_VALUE`() {
        // 60 拍 / coerce(1) = 3600 秒，而不是 Infinity -> Int.MAX_VALUE
        val s = piece(60).totalSeconds(0)
        assertTrue("s=$s", s in 0..24 * 3600)
        assertEquals(3600, s)
    }

    @Test
    fun `totalSeconds is capped at 24h`() {
        // 极大拍数即便正常 bpm 也会被夹到一天
        val s = piece(Int.MAX_VALUE / 2).totalSeconds(1)
        assertEquals(24 * 3600, s)
    }

    @Test
    fun `totalBeats sums sections`() {
        assertEquals(140, piece(60, 80).totalBeats)
    }
}
