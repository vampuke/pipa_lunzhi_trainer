package com.vampuck.pipa_trainer

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.vampuck.pipa_trainer.data.Fingerings
import com.vampuck.pipa_trainer.data.ImportStore
import com.vampuck.pipa_trainer.data.ImportedPiece
import com.vampuck.pipa_trainer.data.JNote
import com.vampuck.pipa_trainer.data.JScore
import com.vampuck.pipa_trainer.data.JScoreJson
import com.vampuck.pipa_trainer.data.JSectionData
import com.vampuck.pipa_trainer.data.PieceSection
import com.vampuck.pipa_trainer.data.PracticePiece
import com.vampuck.pipa_trainer.data.PracticePieces
import com.vampuck.pipa_trainer.databinding.ActivityScoreEditorBinding

/**
 * 交互式简谱编辑器。
 *
 * 用法：选当前「音级/八度/时值/指法」，点「添加音符」把它写进谱子，上方实时预览。
 * 支持删除最后一个、清空、休止符。填好曲名后保存为可跟练曲目（存 SharedPreferences）。
 * 所见即所得，无需手写 JSON。
 */
class ScoreEditorActivity : AppCompatActivity() {

    private lateinit var b: ActivityScoreEditorBinding

    private val notes = ArrayList<JNote>()

    // 当前编辑状态
    private var curDegree = 1
    private var curOctave = 0
    private var curDur = 1.0
    private var curFinger = Fingerings.NONE
    private var beatsPerBar = 4

    private val degreeBtns = ArrayList<MaterialButton>()
    private val durBtns = ArrayList<Pair<MaterialButton, Double>>()
    private val fingerBtns = ArrayList<Pair<MaterialButton, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityScoreEditorBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupDegreePad()
        setupOctave()
        setupDuration()
        setupFingering()
        setupBeatsPerBar()

        b.btnAdd.setOnClickListener { addNote(rest = false) }
        b.btnRest.setOnClickListener { addNote(rest = true) }
        b.btnBackspace.setOnClickListener {
            if (notes.isNotEmpty()) { notes.removeAt(notes.size - 1); refreshPreview() }
        }
        b.btnClear.setOnClickListener {
            if (notes.isEmpty()) return@setOnClickListener
            AlertDialog.Builder(this)
                .setMessage(R.string.editor_clear_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ -> notes.clear(); refreshPreview() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        b.btnSave.setOnClickListener { save() }

        refreshPreview()
    }

    // ---------------- 输入控件 ----------------

    private fun setupDegreePad() {
        degreeBtns.addAll(listOf(b.d1, b.d2, b.d3, b.d4, b.d5, b.d6, b.d7))
        for ((i, btn) in degreeBtns.withIndex()) {
            val deg = i + 1
            btn.setOnClickListener { curDegree = deg; highlightDegree() }
        }
        highlightDegree()
    }

    private fun highlightDegree() {
        for ((i, btn) in degreeBtns.withIndex()) {
            setSelected(btn, i + 1 == curDegree)
        }
    }

    private fun setupOctave() {
        b.octDown.setOnClickListener { curOctave = (curOctave - 1).coerceIn(-2, 2); updateOctaveLabel() }
        b.octUp.setOnClickListener { curOctave = (curOctave + 1).coerceIn(-2, 2); updateOctaveLabel() }
        updateOctaveLabel()
    }

    private fun updateOctaveLabel() {
        b.octLabel.text = when {
            curOctave > 0 -> "+$curOctave"
            curOctave < 0 -> "$curOctave"
            else -> "0"
        }
    }

    private fun setupDuration() {
        durBtns.addAll(listOf(
            b.dur16 to 0.25, b.dur8 to 0.5, b.dur4 to 1.0,
            b.dur2 to 2.0, b.durDot2 to 3.0, b.dur1 to 4.0
        ))
        for ((btn, v) in durBtns) btn.setOnClickListener { curDur = v; highlightDur() }
        highlightDur()
    }

    private fun highlightDur() {
        for ((btn, v) in durBtns) setSelected(btn, v == curDur)
    }

    private fun setupFingering() {
        // 动态生成指法按钮
        val inflater = layoutInflater
        for (f in Fingerings.PALETTE) {
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            btn.text = "${f.symbol} ${f.name}"
            btn.textSize = 13f
            btn.setOnClickListener { curFinger = f.code; highlightFinger() }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (resources.displayMetrics.density * 6).toInt()
            btn.layoutParams = lp
            b.fingerRow.addView(btn)
            fingerBtns.add(btn to f.code)
        }
        highlightFinger()
    }

    private fun highlightFinger() {
        for ((btn, code) in fingerBtns) setSelected(btn, code == curFinger)
    }

    private fun setupBeatsPerBar() {
        b.bpbGroup.check(R.id.bpb4)
        b.bpbGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            beatsPerBar = when (id) {
                R.id.bpb3 -> 3
                R.id.bpb2 -> 2
                else -> 4
            }
            refreshPreview()
        }
    }

    private fun setSelected(btn: MaterialButton, on: Boolean) {
        btn.setBackgroundColor(
            if (on) getColor(R.color.primary_container) else getColor(R.color.surface)
        )
        btn.setTextColor(
            if (on) getColor(R.color.pipa_primary_dark) else getColor(R.color.text_primary)
        )
    }

    // ---------------- 编辑 ----------------

    private fun addNote(rest: Boolean) {
        val note = if (rest) JNote(0, 0, curDur, false, Fingerings.NONE)
        else JNote(curDegree, curOctave, curDur, Fingerings.isTremolo(curFinger), curFinger)
        notes.add(note)
        refreshPreview()
    }

    private fun refreshPreview() {
        val sc = currentScore()
        b.jianpu.setScore(sc, sc.sections.map { it.name })
        b.editorCount.text = getString(R.string.editor_count, notes.size, totalBeatsStr(sc))
        // 滚到底，露出最新音符
        b.previewScroll.post { b.previewScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun currentScore(): JScore {
        val key = (b.inputKey.text?.toString()?.trim().orEmpty()).ifEmpty { "1=D" } + "  ${beatsPerBar}/4"
        val secName = (b.inputSection.text?.toString()?.trim().orEmpty()).ifEmpty { "全曲" }
        return JScore(key, beatsPerBar, listOf(JSectionData(secName, ArrayList(notes))))
    }

    private fun totalBeatsStr(sc: JScore): String {
        val tb = sc.totalBeats
        return if (tb == tb.toInt().toDouble()) tb.toInt().toString() else "%.2f".format(tb)
    }

    // ---------------- 保存 ----------------

    private fun save() {
        val title = b.inputTitle.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) { toast(getString(R.string.editor_need_title)); return }
        if (notes.isEmpty()) { toast(getString(R.string.editor_need_notes)); return }

        val id = "user_" + System.currentTimeMillis()
        val sc = currentScore()
        val bpm = b.inputBpm.text?.toString()?.trim()?.toIntOrNull() ?: 60
        val diff = b.inputDiff.text?.toString()?.trim().orEmpty().ifEmpty { "自录" }

        val piece = PracticePiece(
            id = id,
            title = title,
            composerOrStyle = getString(R.string.editor_self_made),
            refBpm = bpm.coerceIn(20, 220),
            difficulty = diff,
            blurb = b.inputBlurb.text?.toString()?.trim().orEmpty(),
            sections = sc.sections.map { PieceSection(it.name, it.beats.toInt().coerceAtLeast(1)) }
        )
        val rawJson = JScoreJson.export(piece, sc)
        val ip = ImportedPiece(piece, sc, rawJson)
        PracticePieces.registerImported(ip)
        ImportStore.save(this, ip)
        toast(getString(R.string.editor_saved, title))
        finish()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
