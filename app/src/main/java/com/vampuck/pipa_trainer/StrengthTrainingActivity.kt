package com.vampuck.pipa_trainer

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.vampuck.pipa_trainer.audio.Metronome
import com.vampuck.pipa_trainer.audio.TonePlayer
import com.vampuck.pipa_trainer.data.TrainingConfigStore
import com.vampuck.pipa_trainer.databinding.ActivityStrengthTrainingBinding
import com.vampuck.pipa_trainer.databinding.ItemRoundPlanBinding
import com.vampuck.pipa_trainer.dsp.StreamingAnalyzer
import com.vampuck.pipa_trainer.training.TrainingConfig
import com.vampuck.pipa_trainer.training.TrainingPlan
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 指力训练：分次练习轮指。
 *
 * 开始前先排好计划——每次训练有**独立的速度**（音/秒）与时长（默认 2:00），
 * 次数可任意增删；每次之间自动休息 30 秒（可调）。点「开始训练」后倒数 5 秒
 * 进入第一次，之后「训练 → 休息 → 训练 → …」自动衔接。
 *
 * 训练中：
 *  - 引导音按目标速度走（[guidePerBeat]：每拍一轮 / 每击一响），训练开始自动响、休息自动停；
 *  - 开麦时可以实时看到自己**实际**的速度与均匀度（复用 [StreamingAnalyzer]，
 *    引导音用时间遮蔽剔除，与「实时练习」同一套规则）；
 *  - 可随时暂停 / 跳过当前阶段 / 追加一次 / 取消还没开始的次数 / 结束训练。
 *
 * 时间轴交给纯逻辑的 [TrainingPlan]（有单元测试），界面每次 tick 用
 * [SystemClock.elapsedRealtime] 重算已过去的秒数并映射到阶段，因此暂停、跳过、
 * 增删次数都不会让计时漂移或错位。
 */
class StrengthTrainingActivity : AppCompatActivity(), Metronome.Listener {

    private lateinit var b: ActivityStrengthTrainingBinding

    private val plan = TrainingPlan()
    private val metronome = Metronome()
    private val tone = TonePlayer()

    // ---- 设置项 ----
    private var guidePerBeat = true      // true = 每拍一轮（整拍）；false = 每击一响（轮指模式）
    private var accentFirst = true       // true = 强调每小节首拍；false = 每拍相同
    private var useMic = true
    private var syncingSwitches = false

    // ---- 运行状态 ----
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var paused = false
    private var elapsedMs = 0L
    private var anchorMs = 0L
    private var currentPhase: TrainingPlan.Phase? = null
    private var phaseRound = -1
    private var roundStartMs = 0L
    private var roundPausedMs = 0L
    private var lastRestTick = -1

    // ---- 环境声统计 ----
    private val sampleRate = 44100
    @Volatile private var recording = false
    private var recordThread: Thread? = null
    @Volatile private var analyzer: StreamingAnalyzer? = null
    private var lastStrokes = 0
    private var roundStartStrokes = 0
    private var roundLiveCpsSum = 0.0
    private var roundLiveJitSum = 0.0
    private var roundLiveSamples = 0

    private val results = ArrayList<RoundResult>()

    private var pendingStart = false

    private val micPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!pendingStart) return@registerForActivityResult
        pendingStart = false
        if (!granted) {
            useMic = false
            syncMicSwitch()
            Toast.makeText(this, R.string.need_mic, Toast.LENGTH_LONG).show()
        }
        beginTraining()
    }

    // ---------------- 生命周期 ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityStrengthTrainingBinding.inflate(layoutInflater)
        setContentView(b.root)

        metronome.listener = this
        metronome.setAccentFirst(accentFirst)
        metronome.setMode(Metronome.MODE_QUARTER)

        b.accentGroup.check(if (accentFirst) R.id.btnAccentFirst else R.id.btnAccentSame)
        b.accentGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            accentFirst = checkedId == R.id.btnAccentFirst
            metronome.setAccentFirst(accentFirst)   // 立即生效，训练中改也听得见
            persistDraft()
        }

        b.btnAddRound.setOnClickListener { plan.addRound(); rebuildRoundRows() }
        b.btnRestMinus.setOnClickListener { stepRest(-REST_STEP) }
        b.btnRestPlus.setOnClickListener { stepRest(REST_STEP) }

        b.guideGroup.check(if (guidePerBeat) R.id.btnGuideBeat else R.id.btnGuideStroke)
        b.guideGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || syncingSwitches) return@addOnButtonCheckedListener
            val beat = checkedId == R.id.btnGuideBeat
            if (!beat && useMic) {
                // 每击一响的引导音与击弦同频，遮蔽窗会吃掉大量真实起音，统计必然失真。
                useMic = false
                syncMicSwitch()
                Toast.makeText(this, R.string.strength_mic_off_for_stroke, Toast.LENGTH_LONG).show()
            }
            guidePerBeat = beat
            updateGuideHint()
            persistDraft()
        }

        b.switchMic.setOnCheckedChangeListener { _, on ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            if (on && !guidePerBeat) {
                guidePerBeat = true
                b.guideGroup.check(R.id.btnGuideBeat)
                updateGuideHint()
                Toast.makeText(this, R.string.strength_guide_switched, Toast.LENGTH_LONG).show()
            }
            useMic = on
            updateMicHint()
            persistDraft()
        }

        b.btnStartTraining.setOnClickListener { onStartTraining() }

        b.btnPause.setOnClickListener { togglePause() }
        b.btnSkip.setOnClickListener { skipStage() }
        b.btnRunAdd.setOnClickListener {
            plan.addRound()
            toast(R.string.strength_added)
            render()
        }
        b.btnRunDrop.setOnClickListener { dropLastPending() }
        b.btnEndTraining.setOnClickListener { confirmEndTraining() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (running) confirmEndTraining() else finish()
            }
        })

        syncMicSwitch()
        updateGuideHint()
        updateMicHint()
        b.btnSaveConfig.setOnClickListener { askSaveConfig() }
        restoreLastConfig()
        rebuildRoundRows()
        renderConfigs()
    }

    override fun onStop() {
        super.onStop()
        if (running) {
            Toast.makeText(this, R.string.strength_interrupted, Toast.LENGTH_LONG).show()
            stopEverything()
            b.setupGroup.visibility = View.VISIBLE
            b.runGroup.visibility = View.GONE
            rebuildRoundRows()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopEverything()
    }

    // ---------------- 计划编辑（设置页） ----------------

    private fun rebuildRoundRows() {
        b.roundContainer.removeAllViews()
        plan.rounds.forEachIndexed { i, r ->
            val row = ItemRoundPlanBinding.inflate(layoutInflater, b.roundContainer, false)
            row.roundTitle.text = getString(R.string.strength_round_n, i + 1)
            row.bpmValue.text = getString(R.string.strength_bpm_value, r.bpm)
            row.bpmSub.text = getString(
                R.string.strength_bpm_sub, r.cps, r.strokesPerMin.roundToInt()
            )
            row.durValue.text = mmss(r.durationSec.toDouble())
            row.btnRemove.isEnabled = plan.size > 1
            row.btnBpmMinus.setOnClickListener { stepBpm(i, -BPM_STEP) }
            row.btnBpmPlus.setOnClickListener { stepBpm(i, BPM_STEP) }
            row.bpmValue.setOnClickListener { askBpm(i) }
            row.btnDurMinus.setOnClickListener { stepDur(i, -DUR_STEP) }
            row.btnDurPlus.setOnClickListener { stepDur(i, DUR_STEP) }
            row.durValue.setOnClickListener { askDur(i) }
            row.btnRemove.setOnClickListener { removeRound(i) }
            b.roundContainer.addView(row.root)
        }
        updateSummary()
        persistDraft()
    }

    private fun updateSummary() {
        b.planSummary.text = getString(
            R.string.strength_summary,
            plan.size, mmss(plan.workSec.toDouble()),
            mmss(plan.restTotalSec.toDouble()), mmss(plan.totalSec.toDouble())
        )
    }

    private fun updateGuideHint() {
        b.guideHint.setText(
            if (guidePerBeat) R.string.strength_guide_hint_beat else R.string.strength_guide_hint_stroke
        )
    }

    private fun updateMicHint() {
        b.micHint.setText(if (useMic) R.string.strength_mic_hint else R.string.strength_mic_off_hint)
    }

    private fun syncMicSwitch() {
        syncingSwitches = true
        b.switchMic.isChecked = useMic
        syncingSwitches = false
        updateMicHint()
    }

    private fun roundAt(i: Int): TrainingPlan.Round? = plan.rounds.getOrNull(i)

    private fun stepBpm(i: Int, delta: Int) {
        val r = roundAt(i) ?: return
        r.bpm = (r.bpm + delta).coerceIn(TrainingPlan.MIN_BPM, TrainingPlan.MAX_BPM)
        rebuildRoundRows()
    }

    private fun stepDur(i: Int, delta: Int) {
        val r = roundAt(i) ?: return
        r.durationSec = (r.durationSec + delta)
            .coerceIn(TrainingPlan.MIN_DURATION_SEC, TrainingPlan.MAX_DURATION_SEC)
        rebuildRoundRows()
    }

    private fun stepRest(delta: Int) {
        plan.restSec = (plan.restSec + delta).coerceIn(TrainingPlan.MIN_REST_SEC, TrainingPlan.MAX_REST_SEC)
        b.restValue.text = getString(R.string.strength_rest_value, plan.restSec)
        updateSummary()
        persistDraft()
    }

    private fun removeRound(i: Int) {
        if (!plan.removeRound(i)) {
            toast(R.string.strength_keep_one)
            return
        }
        rebuildRoundRows()
    }

    private fun askBpm(i: Int) {
        val r = roundAt(i) ?: return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(r.bpm.toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.strength_bpm_dialog)
            .setView(input)
            .setNegativeButton(R.string.strength_cancel, null)
            .setPositiveButton(R.string.strength_ok) { _, _ ->
                val v = input.text.toString().trim().toIntOrNull()
                if (v == null) {
                    toast(R.string.strength_bad_number)
                } else {
                    r.bpm = v.coerceIn(TrainingPlan.MIN_BPM, TrainingPlan.MAX_BPM)
                    rebuildRoundRows()
                }
            }
            .show()
    }

    private fun askDur(i: Int) {
        val r = roundAt(i) ?: return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(r.durationSec.toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.strength_dur_dialog)
            .setView(input)
            .setNegativeButton(R.string.strength_cancel, null)
            .setPositiveButton(R.string.strength_ok) { _, _ ->
                val v = input.text.toString().trim().toIntOrNull()
                if (v == null) {
                    toast(R.string.strength_bad_number)
                } else {
                    r.durationSec = v.coerceIn(
                        TrainingPlan.MIN_DURATION_SEC, TrainingPlan.MAX_DURATION_SEC
                    )
                    rebuildRoundRows()
                }
            }
            .show()
    }

    // ---------------- 配置：记住上次 + 多套快捷选择 ----------------

    private fun autoConfigName(): String =
        TrainingConfig.autoName(plan.rounds, plan.restSec, plan.leadInSec)

    private fun currentConfig(name: String): TrainingConfig =
        TrainingConfig.of(plan, name, guidePerBeat, accentFirst, useMic)

    /** 每次改动都存一份「上一次编辑的配置」，下次进来自动恢复。 */
    private fun persistDraft() {
        TrainingConfigStore.saveLast(this, currentConfig(autoConfigName()))
    }

    private fun restoreLastConfig() {
        val c = TrainingConfigStore.last(this) ?: return
        applyConfig(c, announce = false)
        toast(getString(R.string.strength_restored, c.name))
    }

    /** 把一套配置灌进当前计划与开关（不触发监听器，避免互相覆盖）。 */
    private fun applyConfig(c: TrainingConfig, announce: Boolean) {
        c.applyTo(plan)
        guidePerBeat = c.guidePerBeat
        accentFirst = c.accentFirst
        useMic = c.useMic
        if (!guidePerBeat && useMic) useMic = false      // 与手动切换同一条规则
        syncingSwitches = true
        b.guideGroup.check(if (guidePerBeat) R.id.btnGuideBeat else R.id.btnGuideStroke)
        b.accentGroup.check(if (accentFirst) R.id.btnAccentFirst else R.id.btnAccentSame)
        b.switchMic.isChecked = useMic
        syncingSwitches = false
        metronome.setAccentFirst(accentFirst)
        b.restValue.text = getString(R.string.strength_rest_value, plan.restSec)
        updateGuideHint()
        updateMicHint()
        rebuildRoundRows()
        persistDraft()
        if (announce) toast(getString(R.string.strength_config_loaded, c.name))
    }

    private fun renderConfigs() {
        val list = TrainingConfigStore.saved(this)
        b.configRow.removeAllViews()
        if (list.isEmpty()) {
            b.configHint.setText(R.string.strength_configs_empty)
            return
        }
        b.configHint.setText(R.string.strength_configs_hint)
        val density = resources.displayMetrics.density
        val padH = (14 * density).toInt()
        val padV = (8 * density).toInt()
        val chipColor = getColor(R.color.pipa_primary_dark)
        for (c in list) {
            val chip = TextView(this).apply {
                text = c.name
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(chipColor)
                setBackgroundResource(R.drawable.bg_chip_accent)
                setPadding(padH, padV, padH, padV)
                isClickable = true
                isLongClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * density).toInt() }
                setOnClickListener { applyConfig(c, announce = true) }
                setOnLongClickListener { confirmDeleteConfig(c); true }
            }
            b.configRow.addView(chip)
        }
    }

    private fun askSaveConfig() {
        val auto = autoConfigName()
        val input = EditText(this).apply {
            setText(auto)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.strength_config_name_title)
            .setView(input)
            .setNegativeButton(R.string.strength_cancel, null)
            .setPositiveButton(R.string.strength_ok) { _, _ ->
                val name = TrainingConfig.sanitize(input.text.toString()).ifBlank { auto }
                TrainingConfigStore.put(this, currentConfig(name))
                renderConfigs()
                toast(getString(R.string.strength_config_saved, name))
            }
            .show()
    }

    private fun confirmDeleteConfig(c: TrainingConfig) {
        AlertDialog.Builder(this)
            .setTitle(R.string.strength_config_delete_title)
            .setMessage(getString(R.string.strength_config_delete_msg, c.name))
            .setNegativeButton(R.string.strength_cancel, null)
            .setPositiveButton(R.string.strength_config_delete_ok) { _, _ ->
                TrainingConfigStore.delete(this, c.name)
                renderConfigs()
            }
            .show()
    }

    // ---------------- 训练流程 ----------------

    private fun onStartTraining() {
        if (plan.rounds.isEmpty()) return
        if (useMic && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStart = true
            micPerm.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        beginTraining()
    }

    private fun beginTraining() {
        results.clear()
        running = true
        paused = false
        elapsedMs = 0L
        anchorMs = SystemClock.elapsedRealtime()
        currentPhase = null
        phaseRound = -1
        lastStrokes = 0
        roundStartStrokes = 0
        lastRestTick = -1

        b.setupGroup.visibility = View.GONE
        b.runGroup.visibility = View.VISIBLE
        b.micCard.visibility = if (useMic) View.VISIBLE else View.GONE
        b.liveSpeed.setText(R.string.strength_live_placeholder)
        b.liveDetail.text = ""
        b.micHintRun.text = ""
        b.btnPause.setText(R.string.strength_pause)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 记下这次实际开跑的配置：下次进来能在「常用配置」里直接选
        TrainingConfigStore.put(this, currentConfig(autoConfigName()))
        renderConfigs()

        if (useMic) startMic()
        handler.post(ticker)
        render()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val delta = now - anchorMs
            anchorMs = now
            if (paused) {
                if (currentPhase == TrainingPlan.Phase.WORK) roundPausedMs += delta
            } else {
                elapsedMs += delta
            }
            render()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun render() {
        val sec = elapsedMs / 1000.0
        val st = plan.stageAt(sec)
        val changed = st.phase != currentPhase ||
            (st.phase == TrainingPlan.Phase.WORK && st.roundIndex != phaseRound)
        if (changed) {
            val prevPhase = currentPhase
            val prevRound = phaseRound
            currentPhase = st.phase
            phaseRound = st.roundIndex
            if (prevPhase == TrainingPlan.Phase.WORK && prevRound >= 0) {
                captureRoundResult(prevRound, null)
            }
            onEnter(st)
            if (!running) return          // 收尾（总结弹窗）已接管
        }
        paint(st)
    }

    private fun onEnter(st: TrainingPlan.Stage) {
        when (st.phase) {
            TrainingPlan.Phase.WORK -> {
                roundStartStrokes = lastStrokes
                roundLiveCpsSum = 0.0
                roundLiveJitSum = 0.0
                roundLiveSamples = 0
                roundStartMs = elapsedMs
                roundPausedMs = 0L
                startGuideFor(st.roundIndex)
            }
            TrainingPlan.Phase.REST -> {
                metronome.stop()
                lastRestTick = -1
            }
            TrainingPlan.Phase.DONE -> finishTraining()
            TrainingPlan.Phase.LEAD_IN -> { /* 倒数中，什么都不响 */ }
        }
    }

    private fun startGuideFor(i: Int) {
        val r = roundAt(i) ?: return
        metronome.setAccentFirst(accentFirst)
        if (guidePerBeat) {
            metronome.setMode(Metronome.MODE_QUARTER)
            metronome.setBpm(r.bpmD)
            analyzer?.setMetronomeBeat(60.0 / r.bpmD)
        } else {
            metronome.setMode(Metronome.MODE_LUNZHI)
            metronome.setBpm(r.bpmD)
            analyzer?.setMetronomeBeat(60.0 / (r.bpmD * 5))
        }
        metronome.start()
    }

    private fun paint(st: TrainingPlan.Stage) {
        val total = plan.size
        b.runRoundLabel.text = getString(
            R.string.strength_round_of, (st.roundIndex + 1).coerceAtMost(total), total
        )
        b.btnPause.setText(if (paused) R.string.strength_resume else R.string.strength_pause)

        when (st.phase) {
            TrainingPlan.Phase.LEAD_IN -> {
                b.runPhaseLabel.setText(R.string.strength_phase_ready)
                b.countdownValue.text = ceil(st.remainingSec).toInt().coerceAtLeast(1).toString()
                b.countdownLabel.setText(R.string.strength_leadin_label)
                b.runProgress.progress = progressOf(st)
                roundAt(0)?.let {
                    b.targetSpeed.text = getString(R.string.strength_target_first, it.bpm, it.cps)
                }
                b.nextSpeed.text = ""
            }
            TrainingPlan.Phase.WORK -> {
                val r = roundAt(st.roundIndex)
                b.runPhaseLabel.setText(R.string.strength_phase_work)
                b.countdownValue.text = mmss(st.remainingSec)
                b.countdownLabel.setText(R.string.strength_remaining)
                b.runProgress.progress = progressOf(st)
                if (r != null) {
                    b.targetSpeed.text = getString(
                        R.string.strength_target, r.bpm, r.cps, r.strokesPerMin.roundToInt()
                    )
                }
                b.nextSpeed.text = if (st.roundIndex < total - 1) {
                    getString(R.string.strength_next_rest, plan.restSec, st.roundIndex + 2,
                        plan.rounds[st.roundIndex + 1].bpm)
                } else {
                    getString(R.string.strength_next_last)
                }
            }
            TrainingPlan.Phase.REST -> {
                b.runPhaseLabel.setText(R.string.strength_phase_rest)
                b.countdownValue.text = mmss(st.remainingSec)
                b.countdownLabel.setText(R.string.strength_rest_remaining)
                b.runProgress.progress = progressOf(st)
                val r = roundAt(st.roundIndex)
                if (r != null) {
                    b.targetSpeed.text = getString(
                        R.string.strength_target, r.bpm, r.cps, r.strokesPerMin.roundToInt()
                    )
                    b.nextSpeed.text = getString(R.string.strength_next_work, st.roundIndex + 1, r.bpm)
                }
                // 休息最后 3 秒轻响提示，准备起手
                val left = ceil(st.remainingSec).toInt()
                if (left in 1..3 && left != lastRestTick) {
                    lastRestTick = left
                    tone.play(REST_TICK_HZ, 0.09)
                }
            }
            TrainingPlan.Phase.DONE -> return
        }
    }

    private fun progressOf(st: TrainingPlan.Stage): Int {
        if (st.phaseTotalSec <= 0.0) return 0
        val done = (st.phaseTotalSec - st.remainingSec) / st.phaseTotalSec
        return (done * 1000).roundToInt().coerceIn(0, 1000)
    }

    private fun togglePause() {
        if (!running) return
        paused = !paused
        if (paused) {
            metronome.stop()
            analyzer?.clearMetronomeClicks()
        } else {
            anchorMs = SystemClock.elapsedRealtime()
            if (currentPhase == TrainingPlan.Phase.WORK) startGuideFor(phaseRound)
        }
        b.btnPause.setText(if (paused) R.string.strength_resume else R.string.strength_pause)
        render()
    }

    /** 跳过当前阶段（倒数 / 本次训练 / 休息）。 */
    private fun skipStage() {
        if (!running || paused) return
        val sec = elapsedMs / 1000.0
        val end = plan.stageEndSec(sec)
        elapsedMs = (end * 1000).toLong()
        render()
    }

    /** 追加/取消训练次数（训练中）。 */
    private fun dropLastPending() {
        val st = plan.stageAt(elapsedMs / 1000.0)
        val idx = plan.size - 1
        if (idx < 0 || !plan.canRemoveDuringRun(idx, st)) {
            toast(if (plan.size <= 1) R.string.strength_keep_one else R.string.strength_cannot_drop)
            return
        }
        plan.removeRound(idx)
        toast(R.string.strength_dropped)
        render()
    }

    private fun confirmEndTraining() {
        AlertDialog.Builder(this)
            .setTitle(R.string.strength_quit_title)
            .setMessage(R.string.strength_quit_msg)
            .setNegativeButton(R.string.strength_quit_no, null)
            .setPositiveButton(R.string.strength_quit_yes) { _, _ ->
                if (running) finishTraining(early = true) else finish()
            }
            .show()
    }

    // ---------------- 环境声统计 ----------------

    private fun startMic() {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, sampleRate / 4) * 2
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (e: Throwable) {
            null
        }
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            try { recorder?.release() } catch (_: Throwable) {}
            useMic = false
            b.micCard.visibility = View.GONE
            syncMicSwitch()
            toast(R.string.strength_mic_failed)
            return
        }
        val an = StreamingAnalyzer(sampleRate)
        an.setMetronomeBeat(1.0)
        analyzer = an
        recording = true
        try {
            recorder.startRecording()
        } catch (e: Throwable) {
            recorder.release()
            useMic = false
            b.micCard.visibility = View.GONE
            syncMicSwitch()
            toast(R.string.strength_mic_failed)
            return
        }
        recordThread = thread(name = "strength-mic") {
            val shortBuf = ShortArray(sampleRate / 10)
            val floatBuf = FloatArray(shortBuf.size)
            while (recording) {
                val n = try { recorder.read(shortBuf, 0, shortBuf.size) } catch (e: Throwable) { -1 }
                if (n > 0) {
                    for (i in 0 until n) floatBuf[i] = shortBuf[i] / 32768f
                    val live = an.push(floatBuf, n)
                    runOnUiThread { onLive(live) }
                }
            }
            try { recorder.stop() } catch (_: Throwable) {}
            try { recorder.release() } catch (_: Throwable) {}
        }
    }

    /** 引导音的回调：映射到分析器自己的时钟，喂给它做时间遮蔽。 */
    override fun onClick(beatIndex: Long, audibleNanos: Long) {
        val an = analyzer ?: return
        val t = an.currentTimeSec() + (audibleNanos - System.nanoTime()) / 1_000_000_000.0
        if (t > 0) an.addMetronomeClick(t)
    }

    private fun onLive(live: StreamingAnalyzer.Live) {
        if (!running) return
        lastStrokes = live.totalStrokes
        if (currentPhase == TrainingPlan.Phase.WORK && !paused) {
            roundLiveCpsSum += live.strokesPerSec
            roundLiveJitSum += live.jitterPct
            roundLiveSamples++
        }
        if (!useMic || b.micCard.visibility != View.VISIBLE) return
        b.liveSpeed.text = if (live.strokesPerSec > 0.2) {
            getString(R.string.strength_live_speed, live.strokesPerSec)
        } else {
            getString(R.string.strength_live_placeholder)
        }
        b.liveDetail.text = getString(
            R.string.strength_live_detail, live.totalStrokes, live.jitterPct.roundToInt()
        )
        // 第一次训练进行 6 秒仍一无所获时给一句可操作的提示
        b.micHintRun.text = if (currentPhase == TrainingPlan.Phase.WORK &&
            lastStrokes == 0 && elapsedMs - roundStartMs > 6000
        ) {
            getString(R.string.strength_no_stroke)
        } else {
            ""
        }
    }

    private fun stopMic() {
        recording = false
        recordThread?.join(500)
        recordThread = null
        analyzer = null
    }

    private fun stopEverything() {
        running = false
        paused = false
        handler.removeCallbacks(ticker)
        try { metronome.stop() } catch (_: Throwable) {}
        try { tone.stop() } catch (_: Throwable) {}
        stopMic()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------- 结算 ----------------

    private fun captureRoundResult(index: Int, playedMsOverride: Long?) {
        val r = roundAt(index) ?: return
        val playedMs = playedMsOverride
            ?: (r.durationSec * 1000L - roundPausedMs).coerceAtLeast(1L)
        val strokes = if (useMic) (lastStrokes - roundStartStrokes).coerceAtLeast(0) else 0
        val measured = if (useMic && playedMs > 0) strokes * 1000.0 / playedMs else 0.0
        val jitter = if (roundLiveSamples > 0) roundLiveJitSum / roundLiveSamples else 0.0
        results.add(
            RoundResult(
                index = index, targetBpm = r.bpm, strokes = strokes, measuredCps = measured,
                jitterPct = jitter, mic = useMic, full = playedMsOverride == null
            )
        )
    }

    private fun finishTraining(early: Boolean = false) {
        // 还没做完的那一次也计入小结（按已经弹过的时间算）
        if (running && early && currentPhase == TrainingPlan.Phase.WORK && phaseRound >= 0) {
            val played = (elapsedMs - roundStartMs - roundPausedMs).coerceAtLeast(1000L)
            captureRoundResult(phaseRound, played)
        }
        val usedMs = elapsedMs
        stopEverything()

        val sb = StringBuilder()
        sb.append(
            getString(
                R.string.strength_summary_head,
                if (early) getString(R.string.strength_summary_early) else "",
                results.size, mmss(usedMs / 1000.0)
            )
        )
        sb.append('\n')
        results.forEach { r ->
            sb.append('\n')
            sb.append(
                getString(
                    R.string.strength_summary_round, r.index + 1, r.targetBpm,
                    mmss(plan.rounds.getOrNull(r.index)?.durationSec?.toDouble() ?: 0.0)
                )
            )
            sb.append('\n')
            sb.append(
                if (r.mic && r.strokes > 0) {
                    getString(
                        R.string.strength_summary_measured, r.measuredCps, r.strokes,
                        r.jitterPct.roundToInt()
                    )
                } else {
                    getString(R.string.strength_summary_nomic)
                }
            )
            if (!r.full) sb.append(getString(R.string.strength_summary_short))
        }
        sb.append("\n\n").append(getString(R.string.strength_summary_note))

        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(if (early) R.string.strength_end_title else R.string.strength_done_title)
            .setMessage(sb.toString())
            .setCancelable(false)
            .setPositiveButton(R.string.strength_done_btn) { _, _ -> finish() }
            .show()
    }

    // ---------------- 小工具 ----------------

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun mmss(seconds: Double): String {
        val s = ceil(seconds).toInt().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private class RoundResult(
        val index: Int,
        val targetBpm: Int,
        val strokes: Int,
        val measuredCps: Double,
        val jitterPct: Double,
        val mic: Boolean,
        val full: Boolean
    )

    private companion object {
        const val TICK_MS = 100L
        const val BPM_STEP = 5
        const val DUR_STEP = 30
        const val REST_STEP = 5
        const val REST_TICK_HZ = 880.0
    }
}
