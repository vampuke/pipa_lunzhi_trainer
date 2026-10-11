package com.vampuck.pipa_trainer

import com.vampuck.pipa_trainer.data.JNote
import com.vampuck.pipa_trainer.data.JScore
import com.vampuck.pipa_trainer.data.JSectionData
import org.junit.Assert.assertEquals
import org.junit.Test

/** 纯逻辑：简谱模型的拍数/拉平/分段下标。 */
class JScoreTest {

    private fun note(d: Int, dur: Double) = JNote(degree = d, dur = dur)

    @Test
    fun `totalBeats sums every note duration across sections`() {
        val s = JScore(
            key = "1=D 4/4", beatsPerBar = 4,
            sections = listOf(
                JSectionData("甲", listOf(note(1, 1.0), note(2, 0.5), note(3, 0.5))),
                JSectionData("乙", listOf(note(5, 2.0), note(6, 1.0)))
            )
        )
        assertEquals(5.0, s.totalBeats, 1e-9)
        assertEquals(2.0, s.sections[0].beats, 1e-9)
    }

    @Test
    fun `flatNotes concatenates sections in order`() {
        val s = JScore(
            key = "k", beatsPerBar = 4,
            sections = listOf(
                JSectionData("甲", listOf(note(1, 1.0), note(2, 1.0))),
                JSectionData("乙", listOf(note(3, 1.0)))
            )
        )
        assertEquals(listOf(1, 2, 3), s.flatNotes.map { it.degree })
    }

    @Test
    fun `sectionStartIndices points at each section head in flatNotes`() {
        val s = JScore(
            key = "k", beatsPerBar = 4,
            sections = listOf(
                JSectionData("甲", listOf(note(1, 1.0), note(2, 1.0))),   // 0,1
                JSectionData("乙", listOf(note(3, 1.0))),                  // 2
                JSectionData("丙", listOf(note(4, 1.0), note(5, 1.0)))     // 3,4
            )
        )
        assertEquals(listOf(0, 2, 3), s.sectionStartIndices())
    }

    @Test
    fun `empty score has zero beats and no indices offset`() {
        val s = JScore(key = "k", beatsPerBar = 4, sections = emptyList())
        assertEquals(0.0, s.totalBeats, 1e-9)
        assertEquals(emptyList<Int>(), s.sectionStartIndices())
        assertEquals(emptyList<JNote>(), s.flatNotes)
    }
}
