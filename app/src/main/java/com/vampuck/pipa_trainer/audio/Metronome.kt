package com.vampuck.pipa_trainer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Software metronome: plays a short "click" on each beat using AudioTrack
 * in low-latency STREAM mode. No file resources needed — synthesized on
 * the fly as a decaying 2-band tone burst.
 *
 * Playback is routed with USAGE_MEDIA + content-type MUSIC so the system
 * takes care of routing alongside other apps (and won't compete with the
 * mic input for processor priority).
 *
 * The metronome runs on its own background thread; callbacks deliver
 * each click timestamp so callers can feed it to the onset suppressor.
 */
class Metronome {

    interface Listener {
        /**
         * Called on the metronome's worker thread at every beat.
         * [absoluteNanos] is System.nanoTime() at the click so callers can map
         * it onto their own clock (e.g. the audio capture timeline).
         */
        fun onClick(beatIndex: Long, absoluteNanos: Long)
    }

    @Volatile private var bpm: Double = 80.0
    @Volatile private var running = false
    private var worker: Thread? = null
    private var track: AudioTrack? = null
    var listener: Listener? = null

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
        try { track?.let { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop() } } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}
        track = null
    }

    private fun run() {
        // synthesize a SHORT click (~28ms) so masking around it can be tight
        val sampleRate = 44100
        val dur = 0.028
        val n = (sampleRate * dur).toInt()
        val pcm = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val env = exp(-t * 120.0)
            val s = (sin(2 * Math.PI * 1200.0 * t) * 0.6 + sin(2 * Math.PI * 2400.0 * t) * 0.4) * env
            pcm[i] = (s * 26000.0).toInt().coerceIn(-32768, 32767).toShort()
        }

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, n * 4)
        val at = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track = at
        at.write(pcm, 0, n)
        at.setNotificationMarkerPosition(0)
        at.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack) {}
            override fun onPeriodicNotification(t: AudioTrack) {}
        })
        at.play()

        val startNs = System.nanoTime()
        var beatIndex = 0L
        var nextBeatNs = startNs
        try {
            while (running && !Thread.currentThread().isInterrupted) {
                val intervalNs = (60_000_000_000L / bpm).toLong()
                val now = System.nanoTime()
                val wait = nextBeatNs - now
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                // re-check
                if (!running || Thread.currentThread().isInterrupted) break

                // rewind & replay
                try {
                    if (at.playState != AudioTrack.PLAYSTATE_PLAYING) at.play()
                    at.pause()
                    at.reloadStaticData()
                    at.play()
                } catch (e: Throwable) { /* ignore transient device errors */ }

                // report the scheduled beat time as an absolute timestamp so the
                // caller can map it onto its own (audio) clock
                listener?.onClick(beatIndex, System.nanoTime())
                beatIndex++
                nextBeatNs += intervalNs
                // If we fell behind (system pause), catch up to current time.
                val nowAfter = System.nanoTime()
                if (nextBeatNs < nowAfter) nextBeatNs = nowAfter + intervalNs - (nowAfter - nextBeatNs) % intervalNs
            }
        } catch (_: InterruptedException) {
            // exit
        }
    }
}