package com.vampuck.pipa_trainer.dsp

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Timbre test: "is this frame a plucked string?".
 *
 * The noise gate in [StreamingAnalyzer] is a *level* test, and level alone
 * cannot separate a loud room from an instrument. Timbre can:
 *
 *  - **plucked string** — a harmonic series over a fundamental in the
 *    instrument's range (pipa fundamentals are ~110-900 Hz), so most of the
 *    spectrum's energy lands on integer multiples of one frequency;
 *  - **room noise** — broadband, no such series, so any candidate fundamental
 *    explains only a small share of the energy;
 *  - **metronome click** — [com.vampuck.pipa_trainer.audio.Metronome] synthesises
 *    a 3200 Hz + 6400 Hz burst with no energy at all below ~2.5 kHz, so the
 *    pitch-range search finds nothing and the ratio collapses. This is a second
 *    line of defence behind the timestamp mask, and it survives the mask being
 *    a few milliseconds off (speaker latency, room reflections).
 *
 * The score compares the spectral peaks sitting *on* the best harmonic series
 * with the peaks sitting half-way *between* them. Both are maxima over the same
 * number of bins, so the estimator is unbiased: broadband noise scores ~0.5 no
 * matter how loud it is, and a plucked string scores well above that. It is
 * scale invariant, so it does not care about loudness or microphone gain.
 *
 * (Measuring "energy on harmonics / total energy" instead does *not* work: a
 * maximum over a few bins already returns ~1.8x the mean, which pushed pure
 * noise up to 0.13 — overlapping the 0.12-0.22 range of real strokes.)
 */
object Timbre {

    /** Pipa fundamentals sit in here; anything outside cannot be the fundamental. */
    const val F0_MIN_HZ = 90.0
    const val F0_MAX_HZ = 1400.0

    /** Harmonics above this carry little energy and cost accuracy to chase. */
    const val BAND_MAX_HZ = 5000.0

    private const val PARTIALS = 8

    /**
     * @param mag magnitude spectrum, bins 0..win/2
     * @return 0..1. ~0.5 for anything with no harmonic series (broadband noise,
     *         and the metronome click, whose energy is all above the pitch
     *         range); well above 0.5 for a plucked string.
     */
    fun harmonicity(mag: DoubleArray, sampleRate: Int, win: Int): Double {
        if (mag.size < 8) return 0.5
        val binHz = sampleRate.toDouble() / win
        if (binHz <= 0.0) return 0.5

        val bandHi = (BAND_MAX_HZ / binHz).roundToInt().coerceIn(1, mag.size - 1)
        var total = 0.0
        for (k in 1..bandHi) total += mag[k]
        if (total <= 0.0) return 0.5

        val lo = max(1, (F0_MIN_HZ / binHz).toInt())
        val hi = (F0_MAX_HZ / binHz).roundToInt().coerceAtMost(bandHi)
        if (hi <= lo) return 0.5

        // Candidate fundamental = strongest bin inside the instrument's range.
        var p = lo
        var best = mag[lo]
        for (k in lo..hi) if (mag[k] > best) { best = mag[k]; p = k }
        val f0 = p * binHz

        // Compare the peaks sitting ON the harmonic series with the peaks sitting
        // half-way BETWEEN harmonics. Both are maxima over the same number of
        // bins, so the estimator is unbiased: broadband noise scores ~0.5 however
        // loud it is, while a plucked string scores far above that. (Simply
        // measuring "energy on harmonics / total energy" does not work — taking a
        // maximum over a few bins already returns ~1.8x the mean, which pushed
        // pure noise to 0.13, overlapping the real strokes' 0.12-0.22 range.)
        var harm = 0.0
        var inter = 0.0
        for (h in 1..PARTIALS) {
            val f = f0 * h
            if (f > BAND_MAX_HZ) break
            val tol = max(1, (0.03 * f / binHz).roundToInt())
            harm += peakNear(mag, f, binHz, tol, bandHi)
            inter += peakNear(mag, f + f0 * 0.5, binHz, tol, bandHi)
        }
        if (harm + inter <= 0.0) return 0.5
        return (harm / (harm + inter)).coerceIn(0.0, 1.0)
    }

    private fun peakNear(mag: DoubleArray, freq: Double, binHz: Double, tol: Int, bandHi: Int): Double {
        val c = (freq / binHz).roundToInt()
        var m = 0.0
        for (k in (c - tol).coerceAtLeast(0)..(c + tol).coerceAtMost(bandHi)) {
            if (mag[k] > m) m = mag[k]
        }
        return m
    }
}
