package com.vampuck.pipa_trainer.dsp

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the FIRST audio track of any file (audio OR video container, e.g. an
 * mp4's embedded AAC) to mono Float PCM, downsampled to ~22 kHz.
 *
 * Memory-safe design:
 *  - uses a growing primitive FloatArray, NOT ArrayList<Float> (boxed floats
 *    cost ~16 bytes each and blow the heap on anything longer than a few sec).
 *  - downsamples on the fly to [TARGET_RATE] so we store ~half the samples.
 *  - hard cap of [MAX_SECONDS] with a truncation flag.
 */
object AudioDecoder {

    private const val TARGET_RATE = 22050
    private const val MAX_SECONDS = 30 * 60   // 30 minutes hard cap

    data class Pcm(val samples: FloatArray, val sampleRate: Int, val truncated: Boolean)

    /** Minimal growable primitive float buffer (avoids boxing). */
    private class FloatList(initial: Int) {
        var arr = FloatArray(initial.coerceAtLeast(1024))
        var size = 0
        fun add(v: Float) {
            if (size == arr.size) arr = arr.copyOf(arr.size shl 1)
            arr[size++] = v
        }
        fun toArray() = arr.copyOf(size)
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

        val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1) else 1
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        // integer downsample factor to land near TARGET_RATE
        val factor = ((srcRate + TARGET_RATE / 2) / TARGET_RATE).coerceAtLeast(1)
        val outRate = srcRate / factor
        val maxOut = MAX_SECONDS * outRate

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val out = FloatList(minOf(maxOut, outRate * 60))
        val info = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        var truncated = false

        // on-the-fly box-filter downsampler state
        var acc = 0f
        var accCount = 0

        loop@ while (!sawOutputEOS) {
            if (!sawInputEOS) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(inBuf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEOS = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            if (outIdx >= 0) {
                if (info.size > 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    // feed samples through the downsampler
                    outBuf.order(ByteOrder.LITTLE_ENDIAN)
                    val sb = outBuf.asShortBuffer()
                    val n = sb.remaining()
                    var i = 0
                    if (channels <= 1) {
                        while (i < n) {
                            acc += sb.get(i) / 32768f; accCount++
                            if (accCount == factor) {
                                out.add(acc / factor); acc = 0f; accCount = 0
                                if (out.size >= maxOut) { truncated = true; codec.releaseOutputBuffer(outIdx, false); break@loop }
                            }
                            i++
                        }
                    } else {
                        while (i + channels <= n) {
                            var m = 0f
                            for (c in 0 until channels) m += sb.get(i + c) / 32768f
                            acc += m / channels; accCount++
                            if (accCount == factor) {
                                out.add(acc / factor); acc = 0f; accCount = 0
                                if (out.size >= maxOut) { truncated = true; codec.releaseOutputBuffer(outIdx, false); break@loop }
                            }
                            i += channels
                        }
                    }
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
            }
        }
        try { codec.stop() } catch (_: Throwable) {}
        codec.release()
        extractor.release()

        return Pcm(out.toArray(), outRate, truncated)
    }
}
