package com.vampuck.pipa_trainer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * 参考音：调音时先听一下目标音高。
 *
 * 波形由 [ToneGen] 生成——它会跳过扬声器发不出来的低频泛音，否则低音弦的
 * 参考音会又闷又轻。
 */
class TonePlayer {

    @Volatile private var playing = false
    private var track: AudioTrack? = null
    private var worker: Thread? = null

    fun play(hz: Double, seconds: Double = 1.8) {
        stop()
        playing = true
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
            track = at
            try {
                at.play()
                var off = 0
                val chunk = 2048
                while (playing && off < pcm.size) {
                    val len = min(chunk, pcm.size - off)
                    val written = at.write(pcm, off, len)
                    if (written <= 0) break
                    off += written
                }
                // 让缓冲区里的尾巴放完再收
                if (playing) Thread.sleep(120)
            } catch (_: Throwable) {
            } finally {
                playing = false
            }
        }
    }

    fun stop() {
        playing = false
        worker?.interrupt()
        worker = null
        try { track?.pause() } catch (_: Throwable) {}
        try { track?.flush() } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}
        track = null
    }
}
