package com.vampuck.pipa_trainer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Arrays
import java.util.concurrent.atomic.AtomicLong
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
 * It supports:
 *  - [setSubdivision]: 1 = 四分音符整拍，2 = 八分音符分拍（每拍中间多一记弱音）。
 *  - [setLunzhi]: 轮指专用——四分拍，每拍均匀响 5 次，每拍的第 1 次重音。
 *  - [setAccentFirst]: true = 每小节(4 拍)第一拍用重音、其余相同；
 *                      false = 每拍相同（不强调任何一拍）。
 *
 * Three timbres are mixed: an *accent* (brighter/louder downbeat), a *normal*
 * beat (identical to the original single click, so existing callers are
 * unchanged when accent/subdivision are left at their defaults), and a soft
 * *sub* tick for off-beats.
 *
 * [Listener.onClick] is invoked for every audible click (main beat or
 * subdivision) with the estimated wall-clock time at which it will be audible —
 * callers that mask metronome bleed need all of them, not just downbeats.
 * [onBeat] is an optional UI callback reporting which indicator slot fired:
 * (slotIndex, slotCount, accent). slotCount depends on the mode — 4 for 整拍,
 * 8 for 分拍, 5 for 轮指 — so the UI can lay out the right number of dots.
 */
class Metronome {

    interface Listener {
        /**
         * 注意：回调运行在节拍器的**工作线程**上，不是主线程——回调里碰 UI 必须
         * 自己 post 到主线程（[onBeat] 同理）。
         *
         * @param beatIndex   0-based counter over every audible click
         * @param audibleNanos System.nanoTime()-based estimate of when the click
         *                     reaches the speaker (scheduled time + output latency)
         */
        fun onClick(beatIndex: Long, audibleNanos: Long)
    }

    /**
     * Optional UI beat callback, invoked from the worker thread for every click.
     * @param slotIndex 0-based index of the lit indicator within its cycle
     * @param slotCount how many indicators the current mode uses
     *                  (4 整拍 / 8 分拍 / 5 轮指) — the UI lays out this many dots
     * @param accent    this click is an accented (strong) click
     */
    @Volatile var onBeat: ((slotIndex: Int, slotCount: Int, accent: Boolean) -> Unit)? = null

    @Volatile private var bpm: Double = 80.0

    /** 模式：0 = 四分整拍，1 = 八分分拍，2 = 轮指（每拍 5 响）。 */
    @Volatile private var mode: Int = MODE_QUARTER

    /** 是否强调每小节第一拍；false = 每拍相同（整拍/分拍模式下不强调任何拍）。 */
    @Volatile private var accentFirst: Boolean = false

    /** 每小节拍数，固定 4（四声）。 */
    private val beatsPerBar: Int = 4

    @Volatile private var running = false
    private var worker: Thread? = null

    /**
     * 每次 start() 自增。工作线程退出时只有在「自己仍是当前这一代」的情况下才允许
     * 把 [running] 清零——否则上一轮线程的收尾会掐断刚起播的新一轮（与 TonePlayer
     * 曾经踩过的坑同一类）。
     */
    private val generation = AtomicLong(0)

    /**
     * 上一次的工作线程。stop() 只把 [track] 停下来，真正 release 是工作线程在做，
     * 那要花几到几十毫秒；这段时间里如果直接开新一轮，两个 AudioTrack 会重叠一小会儿，
     * 听感上就是开头多出一下。所以 start() 前先等它退干净（不在锁内 join，
     * 避免与 onBeat/onClick 回调互等）。
     */
    @Volatile private var lastWorker: Thread? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile var listener: Listener? = null

    val isRunning: Boolean get() = running

    fun setBpm(newBpm: Double) { bpm = newBpm.coerceIn(20.0, 240.0) }
    /** 兼容旧调用：1 = 整拍，2 = 分拍。 */
    fun setSubdivision(n: Int) { mode = if (n >= 2) MODE_EIGHTH else MODE_QUARTER }
    /** 直接设模式：MODE_QUARTER / MODE_EIGHTH / MODE_LUNZHI。 */
    fun setMode(m: Int) { mode = m.coerceIn(MODE_QUARTER, MODE_LUNZHI) }
    fun setAccentFirst(on: Boolean) { accentFirst = on }

    fun start() {
        lastWorker?.let { w ->
            if (w.isAlive) {
                w.interrupt()
                // 两段等待：正常收尾（release 一个 AudioTrack）在几十毫秒内完成，
                // 400ms 还没退说明音频设备卡住了，再等一段以免两个 track 重叠。
                for (budget in intArrayOf(400, 600)) {
                    try { w.join(budget.toLong()) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    if (!w.isAlive) break
                }
            }
        }
        lastWorker = null
        synchronized(this) {
            if (running) return
            running = true
            val gen = generation.incrementAndGet()
            worker = thread(name = "metronome", isDaemon = true) { run(gen) }
        }
    }

    @Synchronized
    fun stop() {
        running = false
        generation.incrementAndGet()
        val w = worker
        worker = null
        lastWorker = w
        w?.interrupt()
        // Only *stop* here to unblock a pending write(). The worker thread owns
        // the release, so the same AudioTrack is never released twice.
        try { track?.stop() } catch (_: Throwable) {}
    }

    /**
     * One short decaying click.
     *
     * @param dur   length in seconds (short enough that masking can stay tight)
     * @param f1    primary partial, @param f2 upper partial
     * @param decay exponential envelope rate (larger = snappier)
     * @param amp   peak amplitude 0..1
     */
    private fun makeClick(
        sr: Int, dur: Double, f1: Double, f2: Double, decay: Double, amp: Double
    ): ShortArray {
        val n = (sr * dur).toInt()
        val pcm = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val env = exp(-t * decay)
            val s = (sin(2 * Math.PI * f1 * t) * 0.75 + sin(2 * Math.PI * f2 * t) * 0.25) * env
            pcm[i] = (s * amp * 32000.0).toInt().coerceIn(-32768, 32767).toShort()
        }
        return pcm
    }

    /** A scheduled click: absolute sample index + which timbre + UI slot info. */
    private class Evt(
        val sample: Long, val type: Int,
        val slotIndex: Int, val slotCount: Int, val accent: Boolean
    ) { var uiFired = false }

    private fun run(gen: Long) {
        val sr = 44100

        // normal: identical to the original single click (3200/6400, ~0.75 peak)
        // so callers that leave accent/subdivision at defaults sound unchanged.
        val normal = makeClick(sr, 0.014, 3200.0, 6400.0, 220.0, 0.75)
        // accent: higher + louder "ding" so the downbeat is unmistakable.
        val accent = makeClick(sr, 0.016, 4000.0, 8000.0, 180.0, 0.95)
        // sub: soft low "and" tick, clearly weaker than a main beat.
        val sub = makeClick(sr, 0.011, 1800.0, 3600.0, 260.0, 0.38)
        val clicks = arrayOf(accent, normal, sub)   // index == Evt.type

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
        } catch (_: Throwable) { if (generation.get() == gen) running = false; return }
        track = at

        // conservative estimate of the output path latency (buffer half-fill)
        val latencyNs = ((bufBytes / 2).toLong() * 1_000_000_000L / (sr * 2)).coerceIn(
            5_000_000L, 120_000_000L
        )

        val buf = ShortArray(block)
        val scheduled = ArrayList<Evt>()   // clicks awaiting playback
        var pos = 0L                       // absolute index of the next sample to write
        // 第一击不从 0 号采样开始：留一小段起始静音，起播瞬间的任何毛刺/重复
        // 都落在静音上，而不是落在第一声上。
        var nextBeat = (sr * START_SILENCE_SEC).toLong()   // sample index of the next MAIN beat
        var beatIndex = 0L                 // main-beat counter (for bar position)
        var clickIndex = 0L                // audible-click counter (for the Listener)
        var started = false                // play() 只在第一块写进去之后调用
        val startNs = System.nanoTime()

        try {
            while (running && !Thread.currentThread().isInterrupted) {
                val beatSamples = (sr * 60.0 / bpm).toLong().coerceAtLeast(1)
                val m = mode
                val accentOn = accentFirst

                // Schedule every MAIN beat that starts within this block, plus the
                // in-beat clicks its mode adds (分拍 1 off-beat / 轮指 4 extra).
                while (nextBeat < pos + block) {
                    val beatInBar = (beatIndex % beatsPerBar).toInt()
                    when (m) {
                        MODE_QUARTER -> {
                            // 4 indicators. Accent only beat 0 when accentFirst; else every beat identical.
                            val accent = accentOn && beatInBar == 0
                            scheduleClick(scheduled, nextBeat, if (accent) 0 else 1,
                                beatInBar, 4, accent, startNs, sr, latencyNs, clickIndex)
                            clickIndex++
                        }
                        MODE_EIGHTH -> {
                            // 8 响（每拍 2 个）。「每拍相同」八响同一个声音，不再有弱音；
                            // 「强调首拍」只有整小节的第 1 响用重音，其余七响相同。
                            val slot = beatInBar * 2
                            val accent = accentOn && beatInBar == 0
                            scheduleClick(scheduled, nextBeat, if (accent) 0 else 1,
                                slot, 8, accent, startNs, sr, latencyNs, clickIndex)
                            clickIndex++
                            val sSample = nextBeat + beatSamples / 2
                            scheduleClick(scheduled, sSample, 1, slot + 1, 8, false,
                                startNs, sr, latencyNs, clickIndex)
                            clickIndex++
                        }
                        MODE_LUNZHI -> {
                            // 轮指：每拍均匀 5 响。「强调首拍」每拍第 1 响重音、其余四响相同；
                            // 「每拍相同」五响完全一样。无指示器，仅发声。
                            val subSamples = (beatSamples / 5).coerceAtLeast(1)
                            for (k in 0 until 5) {
                                val sSample = nextBeat + k.toLong() * subSamples
                                val accent = accentOn && k == 0
                                scheduleClick(scheduled, sSample, if (accent) 0 else 1,
                                    k, 5, accent, startNs, sr, latencyNs, clickIndex)
                                clickIndex++
                            }
                        }
                    }
                    beatIndex++
                    nextBeat += beatSamples
                }

                Arrays.fill(buf, 0.toShort())
                val it = scheduled.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    val wave = clicks[e.type]
                    val off = (e.sample - pos).toInt()
                    // UI 点亮必须发生在该击「真正写入输出」的这一刻，而不是排程时——
                    // 一个 block 会一次排好多击（还含 look-ahead），若排程即上报，圆点会
                    // 整批抢跑、与声音错位。这里当 e.sample 落入当前 block 时才上报一次。
                    if (!e.uiFired && off in 0 until block) {
                        e.uiFired = true
                        onBeat?.invoke(e.slotIndex, e.slotCount, e.accent)
                    }
                    for (k in wave.indices) {
                        val i = off + k
                        if (i in 0 until block) {
                            val v = buf[i] + wave[k]
                            buf[i] = v.coerceIn(-32768, 32767).toShort()
                        }
                    }
                    if (e.sample + wave.size <= pos + block) it.remove()
                }

                val written = at.write(buf, 0, block)   // blocks when the buffer is full
                if (written <= 0) break
                pos += written
                if (!started) {
                    // 先把第一块（起始静音）写进缓冲再起播。原来是 play() 在写之前，
                    // 空缓冲起播时设备会把第一块重复/毛刺出来——听感就是每次开始
                    // 「第一声」响两次。先喂一块再播，起播点落在静音里。
                    started = true
                    try { at.play() } catch (_: Throwable) { break }
                }
            }
        } catch (_: Throwable) {
            // interrupted / device error -> exit
        } finally {
            try { at.stop() } catch (_: Throwable) {}
            try { at.release() } catch (_: Throwable) {}
            if (track === at) track = null
            // 工作线程也可能自己退出（设备出错、write() <= 0）。这里必须把 running
            // 复位，否则 isRunning 永久为 true，下一次 start() 会卡在
            // `if (running) return` 上——界面显示在响、实际没有声音。用 generation
            // 兜住：上一轮线程不能把新一轮的标志清掉。
            if (generation.get() == gen) running = false
        }
    }

    private fun scheduleClick(
        scheduled: ArrayList<Evt>, sample: Long, type: Int, slotIndex: Int, slotCount: Int,
        accent: Boolean, startNs: Long, sr: Int, latencyNs: Long, clickIndex: Long
    ) {
        scheduled.add(Evt(sample, type, slotIndex, slotCount, accent))
        // 节拍遮蔽需要「预定时间 + 延迟」的听觉时刻，仍在排程时上报（它只喂给算法做遮蔽）。
        val audible = startNs + sample * 1_000_000_000L / sr + latencyNs
        listener?.onClick(clickIndex, audible)
    }

    companion object {
        const val MODE_QUARTER = 0
        const val MODE_EIGHTH = 1
        const val MODE_LUNZHI = 2

        /**
         * 起播静音（秒）：第一击不从 0 号采样开始，起播瞬间设备可能把第一块缓冲
         * 重复/毛刺出来，留一段静音就不会落成「第一声响两次」。
         */
        const val START_SILENCE_SEC = 0.09
    }
}
