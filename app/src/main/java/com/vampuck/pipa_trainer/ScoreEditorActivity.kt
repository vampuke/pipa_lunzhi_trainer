package com.vampuck.pipa_trainer

import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

    // 若为编辑现有曲目，记录其 id（保存时沿用，实现覆盖而非新建）
    private var editingId: String? = null

    // 当前编辑状态
    private var curDegree = 1
    private var curOctave = 0
    private var curDur = 1.0
    private var curFinger = Fingerings.NONE
    private var beatsPerBar = 4

    private val degreeBtns = ArrayList<MaterialButton>()
    private val durBtns = ArrayList<Pair<MaterialButton, Double>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityScoreEditorBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupDegreePad()
        setupOctave()
        setupDuration()
        setupFingering()
        setupBeatsPerBar()

        maybeLoadForEdit()

        b.btnAdd.setOnClickListener { addNote(rest = false) }
        b.btnRest.setOnClickListener { addNote(rest = true) }
        b.btnBackspace.setOnClickListener {
            if (notes.isNotEmpty()) { notes.removeAt(notes.size - 1); refreshPreview() }
        }
        b.btnClear.setOnClickListener {
            if (notes.isEmpty()) return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.editor_clear_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ -> notes.clear(); refreshPreview() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        b.btnSave.setOnClickListener { save() }

        refreshPreview()
    }

    private fun maybeLoadForEdit() {
        val id = intent.getStringExtra(EXTRA_EDIT_ID) ?: return
        val ip = PracticePieces.imported.firstOrNull { it.id == id } ?: return
        val sc = PracticePieces.scoreFor(id) ?: return
        editingId = id
        b.inputTitle.setText(ip.title)
        b.inputBpm.setText(ip.refBpm.toString())
        b.inputDiff.setText(ip.difficulty)
        b.inputBlurb.setText(ip.blurb)
        b.inputKey.setText(sc.key.substringBefore("  ").trim())
        beatsPerBar = sc.beatsPerBar
        when (beatsPerBar) {
            3 -> b.bpbGroup.check(R.id.bpb3)
            2 -> b.bpbGroup.check(R.id.bpb2)
            else -> b.bpbGroup.check(R.id.bpb4)
        }
        sc.sections.firstOrNull()?.let { b.inputSection.setText(it.name) }
        notes.clear()
        sc.sections.forEach { notes.addAll(it.notes) }
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

    private val fingerCells = ArrayList<Pair<View, String>>()

    private fun setupFingering() {
        val d = resources.displayMetrics.density
        for (f in Fingerings.PALETTE) {
            // 每个指法 = 竖排：符号预览(与谱面同一绘制) + 名称
            val cell = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding((d * 8).toInt(), (d * 6).toInt(), (d * 8).toInt(), (d * 6).toInt())
                isClickable = true
                isFocusable = true
            }
            if (f.code.isNotEmpty()) {
                val glyph = com.vampuck.pipa_trainer.view.FingerGlyphView(this).apply {
                    code = f.code
                    glyphColor = getColor(R.color.text_primary)
                }
                cell.addView(glyph)
            } else {
                // 「无」用一个短横占位
                val tv = android.widget.TextView(this).apply {
                    text = "—"; textSize = 16f; setTextColor(getColor(R.color.text_secondary))
                    gravity = android.view.Gravity.CENTER
                    height = (d * 26).toInt()
                }
                cell.addView(tv)
            }
            val label = android.widget.TextView(this).apply {
                text = f.name; textSize = 11f
                setTextColor(getColor(R.color.text_secondary))
                gravity = android.view.Gravity.CENTER
            }
            cell.addView(label)
            cell.setOnClickListener { curFinger = f.code; highlightFinger() }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (d * 6).toInt()
            cell.layoutParams = lp
            b.fingerRow.addView(cell)
            fingerCells.add(cell to f.code)
        }
        highlightFinger()
    }

    private fun highlightFinger() {
        for ((cell, code) in fingerCells) {
            cell.setBackgroundColor(
                if (code == curFinger) getColor(R.color.primary_container) else getColor(R.color.surface)
            )
        }
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
            if (on) getColor(R.color.brand_text_strong) else getColor(R.color.text_primary)
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

        val id = editingId ?: ("user_" + System.currentTimeMillis())
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

    companion object {
        const val EXTRA_EDIT_ID = "edit_id"
    }
}
