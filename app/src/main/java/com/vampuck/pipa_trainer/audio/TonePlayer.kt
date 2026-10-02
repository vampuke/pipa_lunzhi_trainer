package com.vampuck.pipa_trainer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * 参考音：调音时先听一下目标音高。
 *
 * 波形由 [ToneGen] 生成——它会跳过扬声器发不出来的低频泛音，否则低音弦的
 * 参考音会又闷又轻。
 *
 * 每次 [play] 都有一个自增的 generation。工作线程只认自己那一次 generation：
 * 上一轮线程退出时的清理不会再掐断新一轮（曾经用一个共享的 `playing` 布尔量，
 * 旧线程的 `finally` 会把新线程的标志抹掉——1.8 秒内连点两根弦，第二声就不响）。
 * AudioTrack 也由工作线程独占释放，[stop] 只做「作废 generation + 打断」，
 * 不会去 release 另一个线程正在写的对象。
 */
class TonePlayer {

    private val generation = AtomicLong(0)

    @Volatile private var track: AudioTrack? = null
    private var worker: Thread? = null

    fun play(hz: Double, seconds: Double = 1.8) {
        stop()
        val gen = generation.incrementAndGet()
        worker = thread(name = "ref-tone", isDaemon = true) {
            val sr = 44100
            val pcm = ToneGen.pcm(hz, seconds, sr)

            val minBuf = AudioTrack.getMinBufferSize(
                sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
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
                    .setBufferSizeInBytes(maxOf(minBuf, 4096))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (_: Throwable) { return@thread }
            if (gen != generation.get()) {
                // A newer play()/stop() already superseded us — never publish it.
                try { at.release() } catch (_: Throwable) {}
                return@thread
            }
            track = at
            try {
                at.play()
                var off = 0
                val chunk = 2048
                while (gen == generation.get() && off < pcm.size) {
                    val len = min(chunk, pcm.size - off)
                    val written = at.write(pcm, off, len)
                    if (written <= 0) break
                    off += written
                }
                // Let the buffer drain so the tail is not clipped. Bounded and
                // interruptible (stop() interrupts this thread).
                if (gen == generation.get()) Thread.sleep(120)
            } catch (_: Throwable) {
                // stopped / device error -> just release below
            } finally {
                if (track === at) track = null
                try { at.stop() } catch (_: Throwable) {}
                try { at.release() } catch (_: Throwable) {}
            }
        }
    }

    /** Stop the current tone. Idempotent; safe to call from any thread. */
    fun stop() {
        generation.incrementAndGet()
        worker?.interrupt()
        worker = null
    }
}
