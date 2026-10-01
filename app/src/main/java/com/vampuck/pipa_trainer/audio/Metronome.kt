package com.vampuck.pipa_trainer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Arrays
import kotlin.concurrent.thread
import kotlin.math.exp
import kotlin.math.sin

/**
 * Sample-accurate metronome.
 *
 * The old implementation slept on a worker thread and restarted a MODE_STATIC
 * track per beat, which drifted by tens of milliseconds and accumulated error
 * (audibly uneven). This one streams silence+clicks through a MODE_STREAM
 * AudioTrack: every beat is placed at an exact sample index, so the hardware
 * clock guarantees even spacing (jitter <= 1 sample).
 *
 * [Listener.onClick] is invoked when a beat is *scheduled*, with the estimated
 * wall-clock time at which it will be audible.
 */
class Metronome {

    interface Listener {
        /**
         * @param beatIndex   0-based beat counter
         * @param audibleNanos System.nanoTime()-based estimate of when the click
         *                     reaches the speaker (scheduled time + output latency)
         */
        fun onClick(beatIndex: Long, audibleNanos: Long)
    }

    @Volatile private var bpm: Double = 80.0
    @Volatile private var running = false
    private var worker: Thread? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile var listener: Listener? = null

    fun setBpm(newBpm: Double) { bpm = newBpm.coerceIn(20.0, 240.0) }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        worker = thread(name = "metronome", isDaemon = true) { run() }
    }

    @Synchronized
    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        // Only *stop* here to unblock a pending write(). The worker thread owns
        // the release, so the same AudioTrack is never released twice.
        try { track?.stop() } catch (_: Throwable) {}
    }

    /** ~14ms high-frequency click: short enough that masking can be tight. */
    private fun makeClick(sr: Int): ShortArray {
        val dur = 0.014
        val n = (sr * dur).toInt()
        val pcm = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val env = exp(-t * 220.0)
            val s = (sin(2 * Math.PI * 3200.0 * t) * 0.75 + sin(2 * Math.PI * 6400.0 * t) * 0.25) * env
            pcm[i] = (s * 24000.0).toInt().coerceIn(-32768, 32767).toShort()
        }
        return pcm
    }

    private fun run() {
        val sr = 44100
        val click = makeClick(sr)
        val block = 1024

        val minBuf = AudioTrack.getMinBufferSize(
            sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = maxOf(minBuf, block * 2 * 2)
        val at = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sr)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Throwable) { return }
        track = at
        try { at.play() } catch (_: Throwable) { return }

        // conservative estimate of the output path latency (buffer half-fill)
        val latencyNs = ((bufBytes / 2).toLong() * 1_000_000_000L / (sr * 2)).coerceIn(
            5_000_000L, 120_000_000L
        )

        val buf = ShortArray(block)
        val scheduled = ArrayList<Long>()   // absolute sample indices awaiting playback
        var pos = 0L                        // absolute index of the next sample to write
        var nextBeat = 0L
        var beatIndex = 0L
        val startNs = System.nanoTime()

        try {
            while (running && !Thread.currentThread().isInterrupted) {
                val beatSamples = (sr * 60.0 / bpm).toLong().coerceAtLeast(1)

                // schedule every beat that starts within this block
                while (nextBeat < pos + block) {
                    scheduled.add(nextBeat)
                    val audible = startNs + nextBeat * 1_000_000_000L / sr + latencyNs
                    listener?.onClick(beatIndex, audible)
                    beatIndex++
                    nextBeat += beatSamples
                }

                Arrays.fill(buf, 0.toShort())
                val it = scheduled.iterator()
                while (it.hasNext()) {
                    val b = it.next()
                    val off = (b - pos).toInt()
                    for (k in click.indices) {
                        val i = off + k
                        if (i in 0 until block) {
                            val v = buf[i] + click[k]
                            buf[i] = v.coerceIn(-32768, 32767).toShort()
                        }
                    }
                    if (b + click.size <= pos + block) it.remove()
                }

                val written = at.write(buf, 0, block)   // blocks when the buffer is full
                if (written < 0) break
                pos += block
            }
        } catch (_: Throwable) {
            // interrupted / device error -> exit
        }
        try { at.stop() } catch (_: Throwable) {}
        try { at.release() } catch (_: Throwable) {}
        if (track === at) track = null
    }
}
