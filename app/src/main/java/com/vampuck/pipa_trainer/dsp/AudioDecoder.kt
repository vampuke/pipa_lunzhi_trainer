package com.vampuck.pipa_trainer.dsp

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the FIRST audio track of any file (audio OR video container) to mono
 * Float PCM, downsampled to ~22 kHz.
 *
 * Memory-safe: growing primitive FloatArray (no boxing) + on-the-fly box-filter
 * downsampling + a hard duration cap.
 *
 * Correctness: follows the decoder's *output* format (rate / channels / PCM
 * encoding) rather than trusting the container's input format — some decoders
 * emit float PCM or a different sample rate, which would otherwise corrupt the
 * time base (and the reported duration).
 */
object AudioDecoder {

    private const val TARGET_RATE = 22050
    private const val MAX_SECONDS = 30 * 60

    data class Pcm(
        val samples: FloatArray,
        val sampleRate: Int,
        val truncated: Boolean,
        /** Real media duration from container metadata (seconds), 0 if unknown. */
        val containerDurationSec: Double
    )

    private class FloatList(initial: Int) {
        var arr = FloatArray(initial.coerceAtLeast(1024))
        var size = 0
        fun add(v: Float) {
            if (size == arr.size) arr = arr.copyOf(arr.size shl 1)
            arr[size++] = v
        }
        fun toArray() = arr.copyOf(size)
    }

    /** Box-filter accumulator: emits one downsampled sample every [factor] inputs. */
    private class Down(var factor: Int) {
        private var acc = 0.0
        private var count = 0
        fun push(v: Double, out: FloatList): Boolean {
            acc += v; count++
            if (count >= factor) { out.add((acc / count).toFloat()); acc = 0.0; count = 0; return true }
            return false
        }
    }

    fun decode(context: Context, uri: Uri): Pcm {
        val extractor = MediaExtractor()
        context.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
            if (pfd == null) throw IllegalStateException("无法打开文件")
            extractor.setDataSource(pfd.fileDescriptor)
        }
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) { trackIndex = i; format = f; break }
        }
        require(trackIndex >= 0 && format != null) { "该文件里没有音频轨" }
        extractor.selectTrack(trackIndex)

        val inRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        // container-declared duration (microseconds); used to correct the timeline
        val containerDur = if (format.containsKey(MediaFormat.KEY_DURATION))
            format.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0 else 0.0

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        // output-format state (updated on INFO_OUTPUT_FORMAT_CHANGED)
        var outRate = inRate
        var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1) else 1
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var maxOut = MAX_SECONDS * outRate
        var down = Down(((outRate + TARGET_RATE / 2) / TARGET_RATE).coerceAtLeast(1))

        val out = FloatList(minOf(maxOut, outRate * 60))
        val info = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        var truncated = false

        loop@ while (!sawOutputEOS) {
            if (!sawInputEOS) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(inBuf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEOS = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val of = codec.outputFormat
                if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                    outRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                if (of.containsKey(MediaFormat.KEY_PCM_ENCODING))
                    encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                maxOut = MAX_SECONDS * outRate
                down = Down(((outRate + TARGET_RATE / 2) / TARGET_RATE).coerceAtLeast(1))
            } else if (outIdx >= 0) {
                if (info.size > 0) {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    buf.order(ByteOrder.LITTLE_ENDIAN)
                    if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        val fb = buf.asFloatBuffer()
                        val n = fb.remaining()
                        var i = 0
                        if (channels <= 1) {
                            while (i < n) {
                                if (down.push(fb.get(i).toDouble(), out) && out.size >= maxOut) { truncated = true; break }
                                i++
                            }
                        } else {
                            while (i + channels <= n) {
                                var m = 0.0
                                for (c in 0 until channels) m += fb.get(i + c)
                                if (down.push(m / channels, out) && out.size >= maxOut) { truncated = true; break }
                                i += channels
                            }
                        }
                    } else {
                        val sb = buf.asShortBuffer()
                        val n = sb.remaining()
                        var i = 0
                        if (channels <= 1) {
                            while (i < n) {
                                if (down.push(sb.get(i) / 32768.0, out) && out.size >= maxOut) { truncated = true; break }
                                i++
                            }
                        } else {
                            while (i + channels <= n) {
                                var m = 0.0
                                for (c in 0 until channels) m += sb.get(i + c) / 32768.0
                                if (down.push(m / channels, out) && out.size >= maxOut) { truncated = true; break }
                                i += channels
                            }
                        }
                    }
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                if (truncated) break@loop
            }
        }
        try { codec.stop() } catch (_: Throwable) {}
        codec.release()
        extractor.release()

        return Pcm(out.toArray(), outRate, truncated, containerDur)
    }
}
