package com.vampuck.pipa_trainer

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import com.vampuck.pipa_trainer.data.PracticePieces
import com.vampuck.pipa_trainer.databinding.ActivityPlayAlongBinding
import com.vampuck.pipa_trainer.databinding.ItemPieceBinding

/** 预设名曲列表：选一首进入「跟练」播放页。 */
class PlayAlongActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayAlongBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayAlongBinding.inflate(layoutInflater)
        setContentView(b.root)

        val inflater = LayoutInflater.from(this)
        for (p in PracticePieces.ALL) {
            val card = ItemPieceBinding.inflate(inflater, b.pieceList, false)
            card.pieceTitle.text = p.title
            card.pieceDiff.text = p.difficulty
            card.pieceStyle.text = p.composerOrStyle
            card.pieceBlurb.text = p.blurb
            val mins = p.totalSeconds(p.refBpm) / 60
            val secs = p.totalSeconds(p.refBpm) % 60
            val meta = getString(R.string.play_piece_meta, p.refBpm, p.sections.size, mins, secs)
            card.pieceMeta.text = if (PracticePieces.scoreFor(p.id) != null)
                "$meta · ${getString(R.string.play_has_score)}" else meta
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
