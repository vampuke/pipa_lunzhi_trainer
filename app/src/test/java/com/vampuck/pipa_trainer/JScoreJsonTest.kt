package com.vampuck.pipa_trainer

import com.vampuck.pipa_trainer.data.JScoreJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯逻辑：简谱 JSON 解析的边界与往返。
 * 依赖 testImplementation org.json（Android 自带的 org.json 在单测里是抛异常的桩）。
 */
class JScoreJsonTest {

    @Test
    fun `parses a minimal valid piece`() {
        val p = JScoreJson.parse(
            """{"id":"a","sections":[{"name":"S","notes":[{"d":1,"dur":1},{"d":0,"dur":2}]}]}"""
        )
        assertEquals("a", p.piece.id)
        assertEquals(2, p.score.sections[0].notes.size)
        assertEquals(3.0, p.score.totalBeats, 1e-9)
    }

    @Test
    fun `clamps out-of-range degree`() {
        // d=9 越界 -> require 抛异常
        assertThrows(Throwable::class.java) {
            JScoreJson.parse("""{"id":"a","sections":[{"name":"S","notes":[{"d":9}]}]}""")
        }
    }

    @Test
    fun `coerces non-finite and non-positive duration to 1`() {
        val p = JScoreJson.parse(
            """{"id":"a","sections":[{"name":"S","notes":[{"d":1,"dur":-5},{"d":1,"dur":0}]}]}"""
        )
        assertEquals(1.0, p.score.sections[0].notes[0].dur, 1e-9)
        assertEquals(1.0, p.score.sections[0].notes[1].dur, 1e-9)
    }

    @Test
    fun `clamps octave beatsPerBar and refBpm`() {
        val p = JScoreJson.parse(
            """{"id":"a","beatsPerBar":99,"refBpm":9999,
               "sections":[{"name":"S","notes":[{"d":1,"oct":9}]}]}"""
        )
        assertEquals(16, p.score.beatsPerBar)
        assertEquals(300, p.piece.refBpm)
        assertEquals(3, p.score.sections[0].notes[0].octave)
    }

    @Test
    fun `empty id or empty sections is rejected`() {
        assertThrows(Throwable::class.java) {
            JScoreJson.parse("""{"id":"","sections":[{"name":"S","notes":[{"d":1}]}]}""")
        }
        assertThrows(Throwable::class.java) {
            JScoreJson.parse("""{"id":"a","sections":[]}""")
        }
    }

    @Test
    fun `export then parse round-trips the notes`() {
        val original = JScoreJson.parse(
            """{"id":"rt","title":"往返","beatsPerBar":3,"refBpm":72,
               "key":"1=G 3/4",
               "sections":[{"name":"S","notes":[
                 {"d":5,"dur":1,"fg":"tan"},{"d":6,"oct":1,"dur":0.5,"tr":true}
               ]}]}"""
        )
        val json = JScoreJson.export(original.piece, original.score)
        val again = JScoreJson.parse(json)
        assertEquals("rt", again.piece.id)
        assertEquals(3, again.score.beatsPerBar)
        val n0 = again.score.sections[0].notes[0]
        val n1 = again.score.sections[0].notes[1]
        assertEquals(5, n0.degree); assertEquals("tan", n0.finger)
        assertEquals(1, n1.octave); assertTrue(n1.tremolo)
        assertEquals(0.5, n1.dur, 1e-9)
    }
}
