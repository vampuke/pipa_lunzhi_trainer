package com.vampuck.pipa_trainer

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import com.vampuck.pipa_trainer.data.PracticePieces
import com.vampuck.pipa_trainer.databinding.ActivityPlayAlongBinding
import com.vampuck.pipa_trainer.databinding.ItemPieceBinding

/** 预设曲目列表 + 导入入口：选一首进入「跟练」播放页。 */
class PlayAlongActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayAlongBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayAlongBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnImport.setOnClickListener {
            startActivity(Intent(this, ImportActivity::class.java))
        }
        b.btnEditor.setOnClickListener {
            startActivity(Intent(this, ScoreEditorActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        rebuildList()   // 导入返回后刷新
    }

    private fun rebuildList() {
        b.pieceList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        // 只展示带简谱的曲目
        val shown = PracticePieces.ALL.filter { PracticePieces.scoreFor(it.id) != null }
        for (p in shown) {
            val card = ItemPieceBinding.inflate(inflater, b.pieceList, false)
            card.pieceTitle.text = p.title
            // 用户导入的标「自录」，内置的标难度
            val isImported = PracticePieces.imported.any { it.id == p.id }
            card.pieceDiff.text = if (isImported) getString(R.string.play_imported_tag) else p.difficulty
            card.pieceStyle.text = p.composerOrStyle
            card.pieceBlurb.text = p.blurb
            val mins = p.totalSeconds(p.refBpm) / 60
            val secs = p.totalSeconds(p.refBpm) % 60
            val meta = getString(R.string.play_piece_meta, p.refBpm, p.sections.size, mins, secs)
            card.pieceMeta.text = "$meta · ${getString(R.string.play_has_score)}"
            card.root.setOnClickListener {
                startActivity(
                    Intent(this, PiecePlayerActivity::class.java)
                        .putExtra(PiecePlayerActivity.EXTRA_PIECE_ID, p.id)
                )
            }
            b.pieceList.addView(card.root)
        }
    }
}
