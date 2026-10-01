package com.vampuck.pipa_trainer

import com.vampuck.pipa_trainer.dsp.Evaluation
import com.vampuck.pipa_trainer.dsp.LunzhiAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The evaluation must say something *specific and true* about each session.
 *
 * Every scenario below has a known ground truth (built from onset times only, so
 * the timing is exact), and the assertions pin both halves: the right diagnosis
 * fires, and the wrong claim does not. Several of these fail against the old
 * evaluator:
 *  - a take missing one stroke in ten scored 97/优秀 and was told "颗粒非常均匀",
 *    because evenness was read from modalCv, which excludes exactly the intervals
 *    a missing stroke produces;
 *  - a 97-point, 2%-jitter take was told "主要问题是击与击之间的随机忽轻忽重";
 *  - a steady roll with four stalls was called "越弹越稳" from two windows.
 */
class EvaluationTest {

    // ---------------- scenario builders ----------------

    private fun grid(
        n: Int, base: Double, jitter: Double, seed: Long,
        dropRate: Double = 0.0, stalls: List<Pair<Int, Double>> = emptyList(),
        ramp: Double = 1.0, pad: Boolean = false
    ): DoubleArray {
        val rnd = java.util.Random(seed)
        val intervals = DoubleArray(n) { i ->
            base * (1.0 + (rnd.nextDouble() - 0.5) * 2 * jitter) *
                (1.0 + (ramp - 1.0) * i / (n - 1).coerceAtLeast(1))
        }
        for ((idx, mult) in stalls) if (idx in intervals.indices) intervals[idx] *= mult
        val t = ArrayList<Double>()
        var acc = 0.1
        for (i in 0 until n) {
            if (dropRate > 0 && rnd.nextDouble() < dropRate) { acc += intervals[i]; continue }
            t.add(acc); acc += intervals[i]
        }
        // trends need >= 4 five-second windows
        if (pad) while (t.size < 260) { t.add(acc); acc += base }
        return t.toDoubleArray()
    }

    private fun flat(n: Int, v: Double = 1.0) = DoubleArray(n) { v }

    private fun metrics(
        t: DoubleArray, a: DoubleArray = flat(t.size)
    ) = LunzhiAnalyzer.metrics(t, a)

    // ---------------- evenness nature ----------------

    @Test
    fun tightRollIsUniformAndPraised() {
        val r = Evaluation.build(metrics(grid(200, 0.125, 0.01, 1)))
        assertEquals(Evaluation.EvennessKind.UNIFORM, r.diagnosis.evenness)
        assertTrue("score=${r.score}", r.score >= 85)
        assertTrue(r.summary.contains("均匀"))
    }

    @Test
    fun missingStrokesAreNotScoredAsAPerfectTake() {
        // one stroke in ten dropped: modalCv says 0.006 (flawless), cvRoll says 0.25
        val r = Evaluation.build(metrics(grid(220, 0.125, 0.01, 5, dropRate = 0.10)))
        assertEquals(Evaluation.EvennessKind.DROPS, r.diagnosis.evenness)
        assertTrue("drops=${r.diagnosis.dropCount}", r.diagnosis.dropCount >= 8)
        assertTrue("missing strokes scored ${r.score}", r.score < 70)
        assertTrue(r.summary.contains("漏"))
        assertFalse("must not praise a take full of holes", r.summary.contains("接近专业水准"))
    }

    @Test
    fun steadyRollWithBreaksIsNotCalledUneven() {
        // the roll itself is tight; four intervals are ~2.5-3x (stumbles or
        // detector misses), which must be reported as breaks, not as jitter
        val r = Evaluation.build(metrics(grid(200, 0.125, 0.01, 6,
            stalls = listOf(30 to 2.8, 70 to 3.0, 110 to 2.6, 150 to 2.9))))
        assertTrue("evenness=${r.diagnosis.evenness}",
            r.diagnosis.evenness == Evaluation.EvennessKind.BREAKS ||
                r.diagnosis.evenness == Evaluation.EvennessKind.DROPS)
        assertTrue("stalls=${r.diagnosis.stallCount} drops=${r.diagnosis.dropCount}",
            r.diagnosis.stallCount + r.diagnosis.dropCount >= 3)
        assertTrue("breaks must not be described as sustained unevenness",
            !r.summary.contains("贯穿全程"))
    }

    @Test
    fun sustainedJitterIsCalledSustained() {
        val r = Evaluation.build(metrics(grid(200, 0.125, 0.20, 3)))
        assertEquals(Evaluation.EvennessKind.SUSTAINED, r.diagnosis.evenness)
        assertTrue(r.summary.contains("贯穿全程"))
    }

    @Test
    fun goodTakeIsNeverToldItIsUnevenStrokeToStroke() {
        // regression: the old text said "主要问题是击与击之间的随机忽轻忽重"
        // on a 2% jitter take, purely because the loudness spread was small
        val r = Evaluation.build(metrics(grid(200, 0.125, 0.02, 2)))
        assertEquals(Evaluation.EvennessKind.UNIFORM, r.diagnosis.evenness)
        assertFalse(r.summary.contains("忽轻忽重"))
        assertFalse(r.summary.contains("忽快忽慢"))
    }

    // ---------------- trend ----------------

    @Test
    fun fatigueIsDetectedAsWorsening() {
        val rnd = java.util.Random(8)
        val t = ArrayList<Double>()
        var acc = 0.1
        for (i in 0 until 260) {
            val j = if (i < 130) 0.01 else 0.20
            acc += 0.125 * (1.0 + (rnd.nextDouble() - 0.5) * 2 * j)
            t.add(acc)
        }
        val r = Evaluation.build(metrics(t.toDoubleArray()))
        assertEquals(Evaluation.Trend.WORSE, r.diagnosis.trend)
        assertTrue("summary=${r.summary}", r.summary.contains("前段约") && r.summary.contains("后段升到"))
        assertFalse("must not call a back-half collapse 'sustained'",
            r.summary.contains("贯穿全程"))
    }

    @Test
    fun warmingUpIsDetectedAsImproving() {
        val rnd = java.util.Random(9)
        val t = ArrayList<Double>()
        var acc = 0.1
        for (i in 0 until 260) {
            val j = if (i < 130) 0.20 else 0.01
            acc += 0.125 * (1.0 + (rnd.nextDouble() - 0.5) * 2 * j)
            t.add(acc)
        }
        val r = Evaluation.build(metrics(t.toDoubleArray()))
        assertEquals(Evaluation.Trend.BETTER, r.diagnosis.trend)
        assertTrue("summary=${r.summary}", r.summary.contains("前段约") && r.summary.contains("后段收到"))
        assertFalse("must not call a warm-up curve 'sustained'", r.summary.contains("贯穿全程"))
    }

    @Test
    fun steadyRollWithRandomBreaksIsNotGivenATrend() {
        // regression: window CVs came out [0.24,0.30,0.22,0.28,0.01] and the
        // old first-vs-last comparison claimed the player was settling down
        val r = Evaluation.build(metrics(grid(220, 0.125, 0.01, 7,
            stalls = listOf(40 to 2.2, 90 to 3.0, 130 to 2.4, 170 to 2.6))))
        assertEquals(Evaluation.Trend.FLAT, r.diagnosis.trend)
        assertFalse(r.summary.contains("越弹越稳"))
        assertFalse(r.summary.contains("越弹越散"))
    }

    @Test
    fun tempoDriftIsDetected() {
        val faster = Evaluation.build(metrics(grid(220, 0.14, 0.02, 10, ramp = 0.6)))
        assertEquals(Evaluation.TempoDrift.FASTER, faster.diagnosis.tempoDrift)
        assertTrue(faster.summary.contains("越弹越快"))

        val slower = Evaluation.build(metrics(grid(220, 0.14, 0.02, 11, ramp = 1.7)))
        assertEquals(Evaluation.TempoDrift.SLOWER, slower.diagnosis.tempoDrift)
        assertTrue(slower.summary.contains("越弹越拖"))
    }

    // ---------------- tempo band ----------------

    @Test
    fun tempoBandsFollowTheMeasuredMapping() {
        fun band(interval: Double): Evaluation.TempoBand {
            val n = (30.0 / interval).toInt().coerceAtLeast(40)
            return Evaluation.build(metrics(grid(n, interval, 0.02, 17))).diagnosis.tempoBand
        }
        assertEquals(Evaluation.TempoBand.VERY_SLOW, band(0.30))  // 200 音/分
        assertEquals(Evaluation.TempoBand.SLOW, band(0.20))       // 300
        assertEquals(Evaluation.TempoBand.MEDIUM, band(0.14))     // 429
        assertEquals(Evaluation.TempoBand.MEDIUM, band(0.125))    // 480
        assertEquals(Evaluation.TempoBand.FAST, band(0.10))       // 600
        assertEquals(Evaluation.TempoBand.VERY_FAST, band(0.08))  // 750
    }

    // ---------------- position profile ----------------

    @Test
    fun singleWeakPositionIsNamed() {
        val t = grid(200, 0.125, 0.03, 12)
        val a = DoubleArray(t.size) { if (it % 5 == 4) 0.6 else 1.0 }
        val r = Evaluation.build(metrics(t, a))
        assertEquals(Evaluation.PositionShape.SINGLE_WEAK, r.diagnosis.positionShape)
        assertEquals(4, r.diagnosis.weakestPosition)
        assertTrue(r.summary.contains("第5击"))
        assertTrue("must ascribe it to one fixed position",
            r.summary.contains("固定某一位弱"))
        assertFalse("must not revive the old blanket claim",
            r.summary.contains("主要问题是"))
    }

    @Test
    fun weakFirstStrokeIsCalledOutAsTheStartOfTheRoll() {
        val t = grid(200, 0.125, 0.03, 13)
        val a = DoubleArray(t.size) { if (it % 5 == 0) 0.6 else 1.0 }
        val r = Evaluation.build(metrics(t, a))
        assertEquals(Evaluation.PositionShape.FIRST_WEAK, r.diagnosis.positionShape)
        assertTrue(r.summary.contains("第1击"))
    }

    @Test
    fun decliningProfileIsCalledDeclining() {
        val t = grid(200, 0.125, 0.03, 14)
        val gains = doubleArrayOf(1.0, 0.92, 0.84, 0.76, 0.68)
        val a = DoubleArray(t.size) { gains[it % 5] }
        val r = Evaluation.build(metrics(t, a))
        assertEquals(Evaluation.PositionShape.DECLINE, r.diagnosis.positionShape)
        assertTrue(r.summary.contains("递减"))
    }

    @Test
    fun equalProfileIsCalledEven() {
        val t = grid(200, 0.125, 0.03, 16)
        val r = Evaluation.build(metrics(t, flat(t.size)))
        assertEquals(Evaluation.PositionShape.EVEN, r.diagnosis.positionShape)
    }

    // ---------------- advice ----------------

    @Test
    fun adviceCarriesConcreteNumbers() {
        val r = Evaluation.build(metrics(grid(200, 0.125, 0.20, 3)))
        assertTrue("advice=${r.advice}", r.advice.any { it.contains("音/分") && it.contains("ms/击") })
    }

    @Test
    fun differentSessionsProduceDifferentWording() {
        val texts = listOf(
            metrics(grid(200, 0.125, 0.01, 1)),
            metrics(grid(200, 0.125, 0.20, 3)),
            metrics(grid(220, 0.125, 0.01, 5, dropRate = 0.10)),
            metrics(grid(200, 0.125, 0.01, 7, stalls = listOf(40 to 3.0, 120 to 3.0))),
            metrics(grid(220, 0.14, 0.02, 10, ramp = 0.6))
        ).map { Evaluation.build(it) }
        val summaries = texts.map { it.summary }.toSet()
        assertEquals("summaries must differ per session", texts.size, summaries.size)
        // and the headline/diagnosis space must differ too
        val kinds = texts.map { it.diagnosis.evenness }.toSet()
        assertTrue("only ${kinds.size} distinct evenness kinds", kinds.size >= 4)
    }

    @Test
    fun tooFewStrokesIsFlaggedAsUnreliable() {
        val t = grid(20, 0.20, 0.02, 20, pad = false)
        val r = Evaluation.build(metrics(t))
        assertTrue("strokes=${t.size}", t.size < 30)
        assertFalse(r.diagnosis.reliable)
        assertTrue(r.summary.contains("仅供参考"))
    }
}
