package com.vampuck.pipa_trainer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.vampuck.pipa_trainer.audio.Metronome
import com.vampuck.pipa_trainer.audio.TonePlayer
import com.vampuck.pipa_trainer.data.TrainingConfigStore
import com.vampuck.pipa_trainer.databinding.ActivityStrengthTrainingBinding
import com.vampuck.pipa_trainer.databinding.ItemRoundPlanBinding
import com.vampuck.pipa_trainer.training.RoundTiming
import com.vampuck.pipa_trainer.training.TrainingConfig
import com.vampuck.pipa_trainer.training.TrainingPlan
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 基本功：分次练习轮指。
 *
 * 开始前先排好计划——每次训练有**独立的速度**（BPM）与时长（默认 2:00），
 * 次数可任意增删；每次之间自动休息 30 秒（可调）。点「开始训练」后倒数 5 秒
 * 进入第一次，之后「训练 → 休息 → 训练 → …」自动衔接。
 *
 * 训练中：
 *  - 引导音按目标速度走（[guidePerBeat]：每拍一轮 / 每击一响），训练开始自动响、休息自动停；
 *  - 可随时暂停 / 跳过当前阶段 / 追加一次 / 取消还没开始的次数 / 结束训练。
 *
 * 时间轴交给纯逻辑的 [TrainingPlan]（有单元测试），界面每次 tick 用
 * [SystemClock.elapsedRealtime] 重算已过去的秒数并映射到阶段，因此暂停、跳过、
 * 增删次数都不会让计时漂移或错位。
 */
class StrengthTrainingActivity : AppCompatActivity() {

    private lateinit var b: ActivityStrengthTrainingBinding

    private val plan = TrainingPlan()
    private val metronome = Metronome()
    private val tone = TonePlayer()

    // ---- 设置项 ----
    private var guidePerBeat = true      // true = 每拍一轮（整拍）；false = 每击一响（轮指模式）
    private var accentFirst = true       // true = 强调每小节首拍；false = 每拍相同
    private var syncingSwitches = false

    /**
     * 当前这套计划的名字。
     *
     * 以前这里没有名字，[persistDraft] 每次都拿 [autoConfigName] 现算一个名字存进去，
     * 于是用户「保存配置」时起的名字在下次进入时必然被自动名覆盖——这就是
     * 「保存好名字、下次进来又变回默认名」的原因。现在名字是一份状态：
     * 载入 / 保存 / 重命名都会更新它，[persistDraft] 存的就是它；只有从未定过名
     * （空串）时才回落到自动名。
     */
    private var planName: String = ""
    /**
     * 正在编辑的那套已保存配置的名字（null = 没在编辑，按「保存」会新增一套）。
     * 点胶囊载入或长按进入编辑时设置；保存/删除后清空。
     */
    private var editingName: String? = null
    /**
     * 当前计划是从哪套配置载入/复制来的。保存时据此问「另存为新配置 / 覆盖原配置」。
     * 显式进入编辑（长按→编辑这套）会把 [editingName] 设上，走另一条路径。
     */
    private var loadedFrom: String? = null

    // ---- 运行状态 ----
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var paused = false
    private var elapsedMs = 0L
    private var anchorMs = 0L
    private var currentPhase: TrainingPlan.Phase? = null
    private var phaseRound = -1
    private var roundStartMs = 0L
    // 「跳过本次训练」时记下这一轮已真实弹奏的毫秒数：阶段切换结算该轮时用它，
    // 否则会用 null（= 打满整轮）把被跳过的半截轮按整轮算，小结里的实练时长就偏长。
    private var skipWorkOverrideMs: Long? = null
    private var lastRestTick = -1
    /** [paused] 是被 [onStop] 切后台自动按下的（用来在回来时提示一句，而不是静默停下）。 */
    private var pausedByBackground = false

    // ---- 引导音状态：当前在按哪一轮的速度响（-1 = 没响）----
    // 用来判断「要不要重启节拍器」：阶段切换时如果目标一样就不重启，
    // 提前起的节拍能无缝接进正式训练，不会有一下停顿或重复的第一声。
    private var guideRound = -1
    private var guideBpm = -1
    private var guideMode = -1

    private val results = ArrayList<RoundResult>()

    // ---------------- 生命周期 ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityStrengthTrainingBinding.inflate(layoutInflater)
        setContentView(b.root)

        // 引导音只负责发声：不再有麦克风统计，所以不需要注册节拍回调。
        metronome.setAccentFirst(accentFirst)
        metronome.setMode(Metronome.MODE_QUARTER)
        metronome.onError = { msg -> runOnUiThread { toast("节拍器无法播放：$msg") } }
        tone.onError = { msg -> runOnUiThread { toast(msg) } }

        b.accentGroup.check(if (accentFirst) R.id.btnAccentFirst else R.id.btnAccentSame)
        b.accentGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || syncingSwitches) return@addOnButtonCheckedListener
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
            guidePerBeat = checkedId == R.id.btnGuideBeat
            updateGuideHint()
            persistDraft()
        }

        b.btnStartTraining.setOnClickListener { beginTraining() }

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

        updateGuideHint()
        b.btnSaveConfig.setOnClickListener { askSaveConfig() }
        restoreLastConfig()
        updateCurrentConfigBanner()   // 无「上次配置」时也要显示「新计划」横幅
        rebuildRoundRows()
        renderConfigs()
        reserveFabSpace()
        // 旋转/分屏后可用宽度变了要重新换行（本页声明了 configChanges，系统不会重建）。
        // 注意：**绝不能在 layout 回调里直接重建子视图**——在 layout 过程中
        // removeAllViews()/addView() 会让新加进去的胶囊拿不到 layout，结果是
        // 整块区域高度为 0（用户看到的就是「常用配置」下面一片空白）。
        // 所以这里只记下宽度，重建 post 到 layout 之后再做。
        b.configList.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val w = r - l
            if (w > 0 && w != configRowWidth) {
                configRowWidth = w
                v.post { if (!isFinishing && !isDestroyed) renderConfigs() }
            }
        }
    }

    /**
     * 悬浮的「开始训练」按钮会盖在滚动内容上：给设置页底部留出空白（按钮高度 + 边距），
     * 这样把计划列表滚到底时，最后一段说明文字也不会被按钮压住。
     * 训练页不需要——那时按钮已经收起，而且多出来的空白只会让页面显得空。
     */
    private fun reserveFabSpace() {
        val v = b.setupGroup
        v.updatePadding(bottom = v.paddingBottom + resources.getDimensionPixelSize(R.dimen.fab_clearance))
    }

    /**
     * 切后台（来电、切应用、锁屏）不再把整组训练作废——一组排下来可能 20 多分钟，
     * 接个电话全没了代价太大。改成**自动暂停**：elapsedMs 原地不动，回来点「继续」
     * 接着练。同时把 ticker 停掉，后台不必每 100ms 空转；屏幕也允许正常熄灭。
     *
     * 真正丢掉会话只剩一种情况：Activity 被销毁（进程回收 / 退到首页）——那需要
     * 前台服务才能救，不在这次范围内。
     */
    override fun onStop() {
        super.onStop()
        if (running && !paused) {
            paused = true
            pausedByBackground = true
            stopGuide()
            b.btnPause.setText(R.string.strength_resume)
            render()
        }
        handler.removeCallbacks(ticker)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        if (!running) return
        // 重新锚定：后台那段时间不该算进训练。暂停中也不累加，所以两条路径都安全。
        anchorMs = SystemClock.elapsedRealtime()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        render()
        if (pausedByBackground) {
            pausedByBackground = false
            Toast.makeText(this, R.string.strength_paused_background, Toast.LENGTH_LONG).show()
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
        val field = numberField(r.bpm, R.string.strength_bpm_field)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.strength_bpm_dialog)
            .setView(field.root)
            .setNegativeButton(R.string.strength_cancel, null)
            .setPositiveButton(R.string.strength_ok) { _, _ ->
                val v = field.text.toIntOrNull()
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
        val field = numberField(r.durationSec, R.string.strength_dur_field)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.strength_dur_dialog)
            .setView(field.root)
            .setNegativeButton(R.string.strength_cancel, null)
            .setPositiveButton(R.string.strength_ok) { _, _ ->
                val v = field.text.toIntOrNull()
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
        TrainingConfig.of(plan, name, guidePerBeat, accentFirst)

    /**
     * 每次改动都存一份「上一次编辑的配置」，下次进来自动恢复。
     *
     * 存的是 [planName]（用户起过/载入过的名字），**不再**用自动名覆盖——否则保存的
     * 名字永远留不下来。名字为空（从未定名）时才用自动名。
     *
     * 「上一次编辑的配置」本身也进常用列表：否则刚进页面时列表是空的，用户会以为
     * 这里没东西、不知道怎么新增。显式保存过的那套被标记成「已定名」，草稿覆盖不了它。
     * 两件事合成一次写盘（见 [TrainingConfigStore.saveLastAndRecordDraft]）。
     */
    private fun persistDraft() {
        val cfg = currentConfig(displayName())
        TrainingConfigStore.saveLastAndRecordDraft(this, cfg)
        updateCurrentConfigBanner()
    }

    /** 当前这套计划的名字：定过就用它，没定过用按计划现算的自动名。 */
    private fun displayName(): String {
        val custom = TrainingConfig.sanitize(planName)
        return custom.ifBlank { autoConfigName() }
    }

    private fun restoreLastConfig() {
        val c = TrainingConfigStore.last(this) ?: return
        applyConfig(c, announce = false)
        // 名字随上次一起恢复：保存/载入过的名字要一直带着，不能下次进来变回自动名。
        planName = c.name
        persistDraft()
        toast(getString(R.string.strength_restored, displayName()))
    }

    /** 把一套配置灌进当前计划与开关（不触发监听器，避免互相覆盖）。 */
    private fun applyConfig(c: TrainingConfig, announce: Boolean) {
        c.applyTo(plan)
        planName = c.name          // 名字随配置一起载入，下次进来还是这个名字
        loadedFrom = null          // 由 loadConfig/beginEditConfig 按需设置
        guidePerBeat = c.guidePerBeat
        accentFirst = c.accentFirst
        syncingSwitches = true
        b.guideGroup.check(if (guidePerBeat) R.id.btnGuideBeat else R.id.btnGuideStroke)
        b.accentGroup.check(if (accentFirst) R.id.btnAccentFirst else R.id.btnAccentSame)
        syncingSwitches = false
        metronome.setAccentFirst(accentFirst)
        b.restValue.text = getString(R.string.strength_rest_value, plan.restSec)
        updateGuideHint()
        rebuildRoundRows()
        persistDraft()
        if (announce) toast(getString(R.string.strength_config_loaded, c.name))
    }

    private fun renderConfigs() {
        val list = TrainingConfigStore.saved(this)
        b.configList.removeAllViews()
        // 即使一条配置都没有，也要把这个「＋ 新建」画出来（否则空页面没有任何入口）
        b.configHint.setText(
            if (list.isEmpty()) R.string.strength_configs_empty else R.string.strength_configs_hint
        )
        val density = resources.displayMetrics.density
        val padH = (14 * density).toInt()
        val padV = (8 * density).toInt()
        val gap = (8 * density).toInt()
        val chipColor = getColor(R.color.accent_chip_text)

        // 按可用宽度贪心换行：把每个胶囊量出真实宽度，排满一行就另起一行。
        // 原来是一行横向滑动，后来几条在手机上永远看不到（也没有可滑的提示）。
        val avail = (
            configRowWidth.takeIf { it > 0 }
                ?: (resources.displayMetrics.widthPixels - (36 * density).toInt())
            ) - b.configList.paddingStart - b.configList.paddingEnd

        var row = newConfigRow()
        var used = 0
        // 最后放一个「＋ 新建」：明确给出「新增一套」的入口（而不是让人猜要先改再存）
        val entries: List<TrainingConfig?> = list + listOf(null)
        for (c in entries) {
            val chip = if (c == null) buildNewChip(padH, padV, chipColor)
                       else buildConfigChip(c, padH, padV, chipColor)
            // 单个名字过长时也不越界：限量宽度，超出用省略号
            if (avail > 0) chip.maxWidth = avail
            // 先量一次拿到真实宽度（文字长度 + 内边距）
            chip.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val cw = chip.measuredWidth
            // 放不下且这一行已经有东西 -> 换行（单个再长也强塞一行，避免死循环）
            if (used > 0 && used + cw > avail) {
                b.configList.addView(row)
                row = newConfigRow()
                used = 0
            }
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = gap; bottomMargin = gap }
            row.addView(chip)
            used += cw + gap
        }
        if (row.childCount > 0) b.configList.addView(row)
    }

    /** 上一次排布时用的宽度：宽度变了（旋转/分屏）要重新换行。 */
    private var configRowWidth = 0

    private fun newConfigRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    /** 一个配置胶囊：点=载入，长按=「编辑 / 复制 / 删除」。 */
    private fun buildConfigChip(
        c: TrainingConfig, padH: Int, padV: Int, chipColor: Int
    ): TextView = TextView(this).apply {
        text = c.name
        textSize = 13f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(chipColor)
        setBackgroundResource(R.drawable.bg_chip_accent)
        setPadding(padH, padV, padH, padV)
        isClickable = true
        isLongClickable = true
        setOnClickListener { loadConfig(c) }
        setOnLongClickListener { configMenu(c); true }
    }

    /** 列表最后的「＋ 新建」：把计划重置成默认的一份，开始配新的。 */
    private fun buildNewChip(padH: Int, padV: Int, chipColor: Int): TextView = TextView(this).apply {
        text = getString(R.string.strength_config_new)
        textSize = 13f
        maxLines = 1
        setTextColor(chipColor)
        // 用虚线描边区分「这是一件事，不是一个配置」
        setBackgroundResource(R.drawable.bg_chip_new)
        setPadding(padH, padV, padH, padV)
        isClickable = true
        setOnClickListener { newConfig() }
    }

    /**
     * 新建一套：把计划重置为默认的一份（此时还没有名字，下次改动/保存时自动取名）。
     * 不再处于「编辑某套」状态，所以随后的保存会新增，而不是覆盖。
     */
    private fun newConfig() {
        plan.rounds.clear()
        plan.rounds.add(TrainingPlan.Round())
        plan.restSec = TrainingPlan.DEFAULT_REST_SEC
        plan.leadInSec = TrainingPlan.DEFAULT_LEAD_IN_SEC
        guidePerBeat = true
        accentFirst = true
        planName = ""
        syncingSwitches = true
        b.guideGroup.check(R.id.btnGuideBeat)
        b.accentGroup.check(R.id.btnAccentFirst)
        syncingSwitches = false
        metronome.setAccentFirst(accentFirst)
        b.restValue.text = getString(R.string.strength_rest_value, plan.restSec)
        updateGuideHint()
        rebuildRoundRows()
        endEditConfig()
        renderConfigs()
        scrollToPlan()
        toast(R.string.strength_config_new_done)
    }

    /**
     * 点胶囊：把这套配置载入当前计划。
     *
     * 载入后**不进入**编辑态——用户接下来按「保存」多半是想存成新的一套（拿这套当
     * 起点改一改）。所以记下「当前是基于哪套改的」，保存时弹窗让用户选
     * 「另存为新配置 / 覆盖原配置」，避免又出现「一保存就把原配置吃掉/覆盖」。
     */
    private fun loadConfig(c: TrainingConfig) {
        applyConfig(c, announce = true)
        endEditConfig()
        loadedFrom = c.name
    }

    /**
     * 编辑一套已保存的配置。
     *
     * 进入编辑后，底部按钮变成「保存修改」；用户改完计划/开关、再按它时，
     * 会**更新原来那一套**（先删旧的、再按新名字存回去），而不是多出一套。
     */
    private fun beginEditConfig(c: TrainingConfig) {
        applyConfig(c, announce = false)
        editingName = c.name
        updateSaveConfigButton()
        scrollToPlan()
        toast(getString(R.string.strength_config_editing, c.name))
    }

    /** 退出编辑态（存好/删掉/重新载入别的配置时都要回到普通状态）。 */
    private fun endEditConfig() {
        editingName = null
        updateSaveConfigButton()
    }

    private fun updateSaveConfigButton() {
        b.btnSaveConfig.setText(
            if (editingName != null) R.string.strength_save_config_update
            else R.string.strength_save_config
        )
        updateCurrentConfigBanner()
    }

    /**
     * 显著横幅：告诉用户现在在编/练哪一套配置。
     *  - 正在编辑某套 -> 「正在编辑：X」
     *  - 已载入/定名的一套 -> 「当前配置：X」
     *  - 还没名字（刚新建的草稿）-> 「当前：新计划（未保存）」
     */
    private fun updateCurrentConfigBanner() {
        val custom = TrainingConfig.sanitize(planName)
        b.currentConfigBanner.text = when {
            editingName != null ->
                getString(R.string.strength_current_config_editing, editingName)
            custom.isNotBlank() ->
                getString(R.string.strength_current_config, custom)
            else -> getString(R.string.strength_current_config_new)
        }
        b.currentConfigBanner.visibility = View.VISIBLE
    }

    /** 进入编辑/载入后把设置页滚到「计划」那里，省得用户自己找。 */
    private fun scrollToPlan() {
        b.setupGroup.post { b.setupGroup.smoothScrollTo(0, b.planLabel.top) }
    }

    /**
     * 「保存」按钮。
     *
     * 三种情况分得很清楚，避免互相覆盖：
     *  - 正在编辑某套（长按→编辑）→ 直接覆盖它（可改名）；
     *  - 刚载入过某套、没有动过名字 → 弹窗问「另存为新配置 / 覆盖原配置」；
     *  - 其它 → 新增一套。
     */
    private fun askSaveConfig() {
        val editing = editingName
        val loaded = loadedFrom
        if (editing == null && loaded != null && loaded == planName) {
            askSaveLoadedChoice(loaded)
            return
        }
        // 这里（而不是 askSaveName 内部）决定「保留当前名字」：正在编辑时 editing 就是
        // planName，等价；非编辑态优先沿用 planName，避免「再存一次」变成新增。
        askSaveName(
            suggestedName = TrainingConfig.sanitize(planName).ifBlank { editing ?: autoConfigName() },
            editing = editing
        )
    }

    /** 刚载入的那套还没改名：问清楚是「存成新的一套」还是「覆盖原来那套」。 */
    private fun askSaveLoadedChoice(loaded: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.strength_save_choice_title, loaded))
            .setItems(
                arrayOf(
                    getString(R.string.strength_save_as_new),
                    getString(R.string.strength_save_overwrite, loaded)
                )
            ) { _, which ->
                if (which == 0) {
                    // 另存：名字加「副本」，预设选中方便直接改
                    val root = loaded.substringBefore(kDuplicateSuffix).trim().ifBlank { loaded }
                    val taken = TrainingConfigStore.saved(this).map { it.name }.toSet()
                    val base = TrainingConfig.sanitize(
                        getString(R.string.strength_config_copy_suffix, root)
                    )
                    var suggest = base; var n = 2
                    while (suggest in taken) {
                        suggest = TrainingConfig.sanitize(
                            getString(R.string.strength_config_copy_suffix_n, root, n)
                        ); n++
                    }
                    askSaveName(suggestedName = suggest, editing = null)
                } else {
                    // 覆盖原配置：名字不变，直接存回它
                    planName = loaded
                    TrainingConfigStore.putDistinct(this, currentConfig(loaded))
                    persistDraft()
                    loadedFrom = null
                    endEditConfig()
                    renderConfigs()
                    toast(getString(R.string.strength_config_saved, loaded))
                }
            }
            .show()
    }

    /**
     * 起名并保存。[editing] != null 表示是「覆盖这一套」（改名会删掉旧名条目）。
     *
     * [suggestedName] 就是弹窗里**预填**的名字，调用方说了算——这里不再回落 [planName]。
     * 原来那版写的是 `sanitize(planName).ifBlank { defaultSuggestion }`，于是「另存为
     * 新配置」传进来的「X 副本」被丢掉、预填成刚载入的「X」，用户直接点确定就把原配置
     * 覆盖了（按钮上写的却是「不动原来那套」）。谁想要「沿用当前名字」就自己传。
     */
    private fun askSaveName(suggestedName: String, editing: String?) {
        val suggestion = TrainingConfig.sanitize(suggestedName).ifBlank { autoConfigName() }
        val field = textField(suggestion, R.string.strength_config_name_field)
        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (editing != null) R.string.strength_config_rename_title
                else R.string.strength_config_name_title
            )
            .setView(field.root)
            .setNegativeButton(R.string.strength_cancel, null)
            .setPositiveButton(R.string.strength_ok) { _, _ ->
                val name = TrainingConfig.sanitize(field.text).ifBlank { suggestion }
                if (editing != null && editing != name) {
                    // 改了名字：先删掉旧名字那一条，免得留下一个同计划的旧配置
                    TrainingConfigStore.delete(this, editing)
                }
                planName = name
                // 用 putDistinct（只按名字去重）：按名字存，绝不因为「计划恰好相同」
                // 把**另一套**配置顶掉——复制/载入后再保存曾把原件吃掉就是这个原因。
                TrainingConfigStore.putDistinct(this, currentConfig(name))
                persistDraft()      // 「上次编辑的配置」也要跟着改名，否则马上退出会看到旧名字
                loadedFrom = null
                endEditConfig()
                renderConfigs()
                toast(getString(R.string.strength_config_saved, name))
            }
            .show()
    }

    /**
     * 长按胶囊。
     *
     * 分两种：用户保存过的配置可以「编辑 / 复制 / 删除」；而进页面时自动记下的那份
     * **草稿**（还没定名）由程序自己管理——它随当前计划变化，不给删除（删了下次编辑
     * 又会回来），只给「编辑」和「另存为新配置」。
     */
    private fun configMenu(c: TrainingConfig) {
        val isDraft = !TrainingConfigStore.isNamed(this, c.name)
        val items = if (isDraft) {
            arrayOf(
                getString(R.string.strength_config_edit),
                getString(R.string.strength_config_save_as_new)
            )
        } else {
            arrayOf(
                getString(R.string.strength_config_edit),
                getString(R.string.strength_config_duplicate),
                getString(R.string.strength_config_delete_ok)
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(c.name)
            .setItems(items) { _, which ->
                if (isDraft) {
                    if (which == 0) beginEditConfig(c) else askSaveName(autoConfigName(), null)
                } else {
                    when (which) {
                        0 -> beginEditConfig(c)
                        1 -> duplicateConfig(c)
                        2 -> confirmDeleteConfig(c)
                    }
                }
            }
            .show()
    }

    /** 复制一套配置为新的（「<原名> 副本」，再复制时递增序号），随后刷新列表。 */
    private fun duplicateConfig(c: TrainingConfig) {
        // 从「原名」算，而不是在副本上再叠副本，免得出现「X 副本 副本」
        // 用 substringBefore：对「X 副本 2」也能拿回「X」，不会越滚越长。
        val root = c.name.substringBefore(kDuplicateSuffix).trim().ifBlank { c.name }
        val base = TrainingConfig.sanitize(getString(R.string.strength_config_copy_suffix, root))
        val taken = TrainingConfigStore.saved(this).map { it.name }.toSet()
        var name = base
        var n = 2
        while (name in taken) {
            name = TrainingConfig.sanitize(getString(R.string.strength_config_copy_suffix_n, root, n))
            n++
        }
        TrainingConfigStore.putDistinct(this, c.copy(name = name))
        renderConfigs()
        toast(getString(R.string.strength_config_saved, name))
    }

    private fun confirmDeleteConfig(c: TrainingConfig) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.strength_config_delete_title)
            .setMessage(getString(R.string.strength_config_delete_msg, c.name))
            .setNegativeButton(R.string.strength_cancel, null)
            .setPositiveButton(R.string.strength_config_delete_ok) { _, _ ->
                TrainingConfigStore.delete(this, c.name)
                if (editingName == c.name) endEditConfig()
                // 正在编的就是这套：把名字也一起放下。否则接着动一下速度，
                // persistDraft() 会拿这个名字把刚删掉的配置又录回来。
                if (TrainingConfig.sanitize(planName) == c.name) planName = ""
                renderConfigs()
                updateCurrentConfigBanner()
            }
            .show()
    }

    // ---------------- 训练流程 ----------------

    private fun beginTraining() {
        // 计划为空时不该进训练流程（原来的 onStartTraining 就有这道守卫，改用
        // beginTraining 直接绑按钮时别丢）：去掉麦克风权限分支后仍要保留。
        if (plan.rounds.isEmpty()) return
        results.clear()
        running = true
        paused = false
        pausedByBackground = false
        elapsedMs = 0L
        anchorMs = SystemClock.elapsedRealtime()
        currentPhase = null
        phaseRound = -1
        lastRestTick = -1
        guideRound = -1
        guideBpm = -1
        guideMode = -1

        b.setupGroup.visibility = View.GONE
        b.runGroup.visibility = View.VISIBLE
        b.btnStartTraining.visibility = View.GONE
        b.btnPause.setText(R.string.strength_pause)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 记下这次实际开跑的配置：下次进来能在「常用配置」里直接选。
        // 用 displayName()（用户定过的名字）——这里原来传的是自动名，会把用户
        // 起好的名字又覆盖掉。也不能用按「同计划」去重的 put()，否则开跑一次
        // 就会把另一套计划相同的配置顶掉（副本场景）。
        TrainingConfigStore.putDistinct(this, currentConfig(displayName()))
        renderConfigs()

        handler.post(ticker)
        render()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val delta = now - anchorMs
            anchorMs = now
            // 暂停时**不**累加 elapsedMs：整条时间轴停在原地（恢复时重新锚定 anchorMs），
            // 所以轮次结算的分母就是这一轮的时长本身，不需要另外记暂停时长。
            if (!paused) elapsedMs += delta
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
                // 正常跑完一轮 → null（打满整轮）；被「跳过」提前离开 → 用实际弹奏时长，
                // 否则半截轮按整轮算，小结里的实练时长就偏长。
                captureRoundResult(prevRound, skipWorkOverrideMs)
            }
            skipWorkOverrideMs = null
            onEnter(st)
            if (!running) return          // 收尾（总结弹窗）已接管
        }
        paint(st)
        updateGuide()                     // 引导音（含倒数/休息最后几秒的提前起拍）
    }

    private fun onEnter(st: TrainingPlan.Stage) {
        when (st.phase) {
            TrainingPlan.Phase.WORK -> {
                roundStartMs = elapsedMs
            }
            TrainingPlan.Phase.REST -> {
                lastRestTick = -1
            }
            TrainingPlan.Phase.DONE -> finishTraining()
            TrainingPlan.Phase.LEAD_IN -> { /* 倒数中，节拍由 updateGuide 提前起 */ }
        }
    }

    /** 引导音现在该按哪一轮响：训练中 = 本轮；倒数/休息的最后几秒 = 提前起的那一轮。 */
    private fun guideTargetIndex(): Int {
        val sec = elapsedMs / 1000.0
        val st = plan.stageAt(sec)
        return when (st.phase) {
            TrainingPlan.Phase.WORK -> st.roundIndex
            TrainingPlan.Phase.DONE -> -1
            else -> plan.preroll(sec) ?: -1
        }
    }

    /**
     * 让引导音跟上当前阶段——每个 tick 都调，但只在「目标变了」时才动节拍器：
     * 同一轮、同一速度就让它继续响，于是提前起的节拍能**无缝**接进正式训练
     * （不重启 → 不会有一声重复、也不会停顿半拍）。
     */
    private fun updateGuide() {
        if (!running || paused) {
            stopGuide()
            return
        }
        val idx = guideTargetIndex()
        if (idx < 0) {
            stopGuide()
            return
        }
        val r = roundAt(idx) ?: return
        val mode = if (guidePerBeat) Metronome.MODE_QUARTER else Metronome.MODE_LUNZHI
        if (guideRound == idx && guideBpm == r.bpm && guideMode == mode &&
            metronome.isRunning
        ) {
            return
        }
        if (guideRound >= 0 && (guideBpm != r.bpm || guideMode != mode)) {
            metronome.stop()          // 速度/模式变了才重启
        }
        guideRound = idx
        guideBpm = r.bpm
        guideMode = mode
        metronome.setAccentFirst(accentFirst)
        metronome.setMode(mode)
        metronome.setBpm(r.bpmD)
        metronome.start()
    }

    private fun stopGuide() {
        if (guideRound >= 0 || metronome.isRunning) {
            metronome.stop()
        }
        guideRound = -1
        guideBpm = -1
        guideMode = -1
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
                // 休息时只讲**即将开始**的那一轮（第几次 + 目标速度）。原来这里
                // targetSpeed 与 nextSpeed 都在说同一轮，大字号那句又容易被读成
                // 「刚才那次」；「第 N / M 次」在上面的 runRoundLabel 里已经有了。
                val r = roundAt(st.roundIndex)
                if (r != null) {
                    b.targetSpeed.text = getString(
                        R.string.strength_rest_target, st.roundIndex + 1, r.bpm, r.cps
                    )
                }
                b.nextSpeed.text = ""
                // 休息最后几秒的节拍由 updateGuide() 提前起；没开提前起拍时，
                // 仍然用 3 声轻响提示准备起手（两者不会同时响）。
                if (plan.preroll(elapsedMs / 1000.0) == null) {
                    val left = ceil(st.remainingSec).toInt()
                    if (left in 1..3 && left != lastRestTick) {
                        lastRestTick = left
                        tone.play(REST_TICK_HZ, 0.09)
                    }
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
        pausedByBackground = false
        if (paused) {
            stopGuide()
        } else {
            anchorMs = SystemClock.elapsedRealtime()
        }
        b.btnPause.setText(if (paused) R.string.strength_resume else R.string.strength_pause)
        render()
    }

    /** 跳过当前阶段（倒数 / 本次训练 / 休息）。 */
    private fun skipStage() {
        if (!running || paused) return
        val sec = elapsedMs / 1000.0
        // 若正跳过一轮 WORK，记下它已真实弹奏的时长，供下一 tick 结算该轮时用——
        // 不记就会用 null（打满整轮）把半截轮按整轮算，实练时长偏长。
        if (currentPhase == TrainingPlan.Phase.WORK && phaseRound >= 0) {
            skipWorkOverrideMs = (elapsedMs - roundStartMs).coerceAtLeast(1000L)
        }
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
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.strength_quit_title)
            .setMessage(R.string.strength_quit_msg)
            .setNegativeButton(R.string.strength_quit_no, null)
            .setPositiveButton(R.string.strength_quit_yes) { _, _ ->
                if (running) finishTraining(early = true) else finish()
            }
            .show()
    }

    private fun stopEverything() {
        running = false
        paused = false
        pausedByBackground = false
        handler.removeCallbacks(ticker)
        stopGuide()
        try { metronome.stop() } catch (_: Throwable) {}
        try { tone.stop() } catch (_: Throwable) {}
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------- 结算 ----------------

    private fun captureRoundResult(index: Int, playedMsOverride: Long?) {
        val r = roundAt(index) ?: return
        // 分母只认真实弹奏时长：暂停期间 elapsedMs 本来就不累加，所以跑满一轮时
        // 就是这一轮的时长本身；被「跳过」提前离开时用实际弹奏时长。
        val playedMs = RoundTiming.playedMs(r.durationSec, playedMsOverride)
        results.add(
            RoundResult(
                index = index,
                targetBpm = r.bpm,
                plannedSec = r.durationSec,
                playedSec = playedMs / 1000.0,
                full = playedMsOverride == null
            )
        )
    }

    private fun finishTraining(early: Boolean = false) {
        // 还没做完的那一次也计入小结（按已经弹过的时间算）
        if (running && early && currentPhase == TrainingPlan.Phase.WORK && phaseRound >= 0) {
            // elapsedMs 在暂停时本来就不累加（暂停时长不在这一轮里），所以
            // played 就取实弹时长。
            val played = (elapsedMs - roundStartMs).coerceAtLeast(1000L)
            captureRoundResult(phaseRound, played)
        }
        val usedMs = elapsedMs
        stopEverything()

        val sb = StringBuilder()
        // 「共完成 N 次」只数**打满整轮**的：提前结束时那半截轮也进了 results，
        // 但它写的是「实练 0:05（提前结束）」，跟「完成」并排放自相矛盾。
        val done = results.count { it.full }
        val partial = results.size - done
        sb.append(
            getString(
                R.string.strength_summary_head,
                if (early) getString(R.string.strength_summary_early) else "",
                done, mmss(usedMs / 1000.0)
            )
        )
        if (partial > 0) sb.append(getString(R.string.strength_summary_partial, partial))
        sb.append('\n')
        results.forEach { r ->
            sb.append('\n')
            sb.append(
                getString(
                    R.string.strength_summary_round, r.index + 1, r.targetBpm,
                    mmss(r.plannedSec.toDouble())
                )
            )
            if (!r.full) sb.append(getString(R.string.strength_summary_short, mmss(r.playedSec)))
        }

        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(if (early) R.string.strength_end_title else R.string.strength_done_title)
            .setMessage(sb.toString())
            .setCancelable(false)
            // 结束后留在本页回到设置界面（而不是直接退回首页），方便接着再来一组
            .setPositiveButton(R.string.strength_done_btn) { _, _ -> returnToSetup() }
            .show()
    }

    /** 训练收尾后：停在基本功页的设置界面，重置按钮与计时状态。 */
    private fun returnToSetup() {
        if (isFinishing || isDestroyed) return
        b.runGroup.visibility = View.GONE
        b.setupGroup.visibility = View.VISIBLE
        b.btnStartTraining.visibility = View.VISIBLE
        b.btnPause.setText(R.string.strength_pause)
        b.countdownValue.text = ""
        b.runProgress.progress = 0
        rebuildRoundRows()
        renderConfigs()
    }

    // ---------------- 小工具 ----------------

    /**
     * 弹窗里的输入框。原来是裸 EditText：没有边框、没有内边距、字号偏小，看着像一行
     * 「浮」在弹窗上的字。现在改成 inflate [R.layout.dialog_field]，与页面里的输入框
     * 共用同一套样式（App.TextField / App.TextInput）：圆角描边、聚焦转红木主色、
     * 提示浮到边框上，两处外观完全一致。
     */
    private class DialogField(val root: View, val input: TextInputEditText) {
        /** 去掉首尾空白的输入内容。 */
        val text: String get() = input.text?.toString()?.trim().orEmpty()
        fun toIntOrNull(): Int? = text.toIntOrNull()
    }

    private fun textField(initial: String, hintRes: Int): DialogField =
        buildField(initial, hintRes, InputType.TYPE_CLASS_TEXT)

    private fun numberField(initial: Int, hintRes: Int): DialogField =
        buildField(initial.toString(), hintRes, InputType.TYPE_CLASS_NUMBER)

    private fun buildField(initial: String, hintRes: Int, inputType: Int): DialogField {
        val v = layoutInflater.inflate(R.layout.dialog_field, null)
        val til = v.findViewById<TextInputLayout>(R.id.fieldLayout)
        val et = v.findViewById<TextInputEditText>(R.id.fieldInput)
        til.hint = getString(hintRes)
        et.inputType = inputType
        et.setText(initial)
        et.setSelection(initial.length)
        return DialogField(v, et)
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun toast(text: CharSequence) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun mmss(seconds: Double): String {
        val s = ceil(seconds).toInt().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private class RoundResult(
        val index: Int,
        val targetBpm: Int,
        val plannedSec: Int,
        val playedSec: Double,
        val full: Boolean
    )

    private companion object {
        /** 复制配置时加的后缀（也用来识别「这本就是副本」，避免副本上叠副本）。 */
        const val kDuplicateSuffix = " 副本"
        const val TICK_MS = 100L
        const val BPM_STEP = 5
        const val DUR_STEP = 30
        const val REST_STEP = 5
        const val REST_TICK_HZ = 880.0
    }
}
