package com.vampuck.pipa_trainer.dsp

import kotlin.math.roundToInt

/** Turns raw metrics into a human evaluation with grade + advice (English). */
object Evaluation {

    data class Report(
        val grade: String,          // A / B / C / D
        val gradeColor: Int,        // resource-independent hint: 0 good,1 warn,2 bad
        val headline: String,
        val bullets: List<String>,
        val fingerBullets: List<String>
    )

    fun evennessGrade(cv: Double): Pair<String, Int> = when {
        cv <= 0.12 -> "A" to 0
        cv <= 0.20 -> "B" to 0
        cv <= 0.30 -> "C" to 1
        else -> "D" to 2
    }

    fun build(
        strokesPerMin: Double,
        strokesPerSec: Double,
        cv: Double,
        modalCv: Double,
        fingerProfile: DoubleArray,
        totalStrokes: Int,
        durationSec: Double
    ): Report {
        val (grade, color) = evennessGrade(modalCv.takeIf { it > 0 } ?: cv)
        val headline = "About ${strokesPerMin.roundToInt()} strokes/min " +
            "(${"%.1f".format(strokesPerSec)}/s, ~${"%.1f".format(strokesPerSec / 5)} roll-cycles/s)"

        val bullets = ArrayList<String>()
        bullets.add("Detected $totalStrokes strokes over ${"%.1f".format(durationSec)}s")
        bullets.add("Evenness CV = ${"%.3f".format(cv)} (modal-band ${"%.3f".format(modalCv)}) — grade $grade")
        bullets.add(evennessComment(modalCv.takeIf { it > 0 } ?: cv))

        // finger profile
        val fingerBullets = ArrayList<String>()
        if (fingerProfile.size == 5) {
            val names = listOf("pos1", "pos2", "pos3", "pos4", "pos5")
            val pct = fingerProfile.map { (it * 100).roundToInt() }
            fingerBullets.add("Per-cycle loudness: " +
                names.indices.joinToString("  ") { "${names[it]}=${pct[it]}%" })
            val strongest = fingerProfile.indices.maxByOrNull { fingerProfile[it] } ?: 0
            val weakest = fingerProfile.indices.minByOrNull { fingerProfile[it] } ?: 0
            val ratio = if (fingerProfile[weakest] > 0)
                fingerProfile[strongest] / fingerProfile[weakest] else 0.0
            fingerBullets.add("Strongest position ${strongest + 1}, weakest ${weakest + 1} " +
                "(${"%.2f".format(ratio)}x, ${"%.1f".format(LunzhiAnalyzer.toDb(ratio))} dB)")
            if (ratio < 1.2) {
                fingerBullets.add("Position-to-position differences are small — your unevenness is mostly random stroke-to-stroke, not a fixed weak finger. Focus on overall consistency.")
            } else {
                fingerBullets.add("Position ${weakest + 1} is consistently lighter — likely a weaker finger (ring/little finger for late positions). Work on evening its attack.")
            }
        }

        return Report(grade, color, headline, bullets, fingerBullets)
    }

    private fun evennessComment(cv: Double): String = when {
        cv <= 0.12 -> "Excellent, near-professional evenness."
        cv <= 0.20 -> "Good, but tighten the timing further for a smoother roll."
        cv <= 0.30 -> "Fair — noticeable timing scatter; aim for steadier intervals."
        else -> "Uneven — strokes are clumping/gapping. Slow down and focus on equal spacing."
    }
}
