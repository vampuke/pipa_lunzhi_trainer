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
        b.btnFile.setOnClickListener {
            startActivity(Intent(this, FileAnalysisActivity::class.java))
        }
        b.btnLive.setOnClickListener {
            startActivity(Intent(this, LiveActivity::class.java))
        }
    }
}
