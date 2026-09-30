package com.vampuck.pipa_trainer.dsp

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes an arbitrary audio file (via content Uri) to mono Float PCM.
 * Uses MediaExtractor + MediaCodec so any codec the device supports works
 * (m4a/aac, mp3, wav, ogg...). Downmixes to mono and returns the native
 * sample rate.
 */
object AudioDecoder {

    data class Pcm(val samples: FloatArray, val sampleRate: Int)

    fun decode(context: Context, uri: Uri): Pcm {
        val extractor = MediaExtractor()
        context.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
            extractor.setDataSource(pfd!!.fileDescriptor)
        }
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) { trackIndex = i; format = f; break }
        }
        require(trackIndex >= 0 && format != null) { "No audio track found" }
        extractor.selectTrack(trackIndex)

        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val out = ArrayList<Float>(sampleRate * 60)
        val info = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false

        while (!sawOutputEOS) {
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
                    appendPcm16(outBuf, channels, out)
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
            }
        }
        codec.stop(); codec.release(); extractor.release()

        val arr = FloatArray(out.size)
        for (i in arr.indices) arr[i] = out[i]
        return Pcm(arr, sampleRate)
    }

    /** Decoder output is 16-bit little-endian PCM. Downmix to mono float. */
    private fun appendPcm16(buf: ByteBuffer, channels: Int, out: ArrayList<Float>) {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val sb = buf.asShortBuffer()
        val n = sb.remaining()
        var i = 0
        if (channels <= 1) {
            while (i < n) { out.add(sb.get(i) / 32768f); i++ }
        } else {
            while (i + channels <= n) {
                var acc = 0f
                for (c in 0 until channels) acc += sb.get(i + c) / 32768f
                out.add(acc / channels)
                i += channels
            }
        }
    }
}
