package com.vampuck.pipa_trainer

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.vampuck.pipa_trainer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        bindFeature(b.itFile, R.drawable.ic_file, R.string.feat_file_title, R.string.feat_file_sub)
        bindFeature(b.itLive, R.drawable.ic_live, R.string.feat_live_title, R.string.feat_live_sub)
        bindFeature(b.itTuner, R.drawable.ic_tuner, R.string.feat_tuner_title, R.string.feat_tuner_sub)
        bindFeature(b.itMetronome, R.drawable.ic_metronome, R.string.feat_metronome_title, R.string.feat_metronome_sub)

        b.btnFile.setOnClickListener {
            startActivity(Intent(this, FileAnalysisActivity::class.java))
        }
        b.btnLive.setOnClickListener {
            startActivity(Intent(this, LiveActivity::class.java))
        }
        b.btnTuner.setOnClickListener {
            startActivity(Intent(this, TunerActivity::class.java))
        }
        b.btnMetronome.setOnClickListener {
            startActivity(Intent(this, MetronomeActivity::class.java))
        }
    }

    private fun bindFeature(
        item: com.vampuck.pipa_trainer.databinding.ItemFeatureBinding,
        iconRes: Int, titleRes: Int, subRes: Int
    ) {
        item.featIcon.setImageResource(iconRes)
        item.featTitle.setText(titleRes)
        item.featSub.setText(subRes)
    }
}
