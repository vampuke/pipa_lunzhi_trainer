package com.vampuck.pipa_trainer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vampuck.pipa_trainer.databinding.ActivityMainBinding
import com.vampuck.pipa_trainer.update.UpdateChecker
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding

    // 等待安装的已下载 APK（从「设置允许安装」返回后继续）
    private var pendingApk: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        bindFeature(b.itFile, R.drawable.ic_file, R.string.feat_file_title, R.string.feat_file_sub)
        bindFeature(b.itLive, R.drawable.ic_live, R.string.feat_live_title, R.string.feat_live_sub)
        bindFeature(b.itTuner, R.drawable.ic_tuner, R.string.feat_tuner_title, R.string.feat_tuner_sub)
        bindFeature(b.itMetronome, R.drawable.ic_metronome, R.string.feat_metronome_title, R.string.feat_metronome_sub)
        bindFeature(b.itPlay, R.drawable.ic_play, R.string.feat_play_title, R.string.feat_play_sub)

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
        b.btnPlay.setOnClickListener {
            startActivity(Intent(this, PlayAlongActivity::class.java))
        }

        checkForUpdate()
    }

    override fun onResume() {
        super.onResume()
        // 从「允许安装未知应用」设置页返回：如已授权且有待装 APK，继续安装
        val apk = pendingApk
        if (apk != null && apk.exists() && UpdateChecker.canInstall(this)) {
            pendingApk = null
            UpdateChecker.install(this, apk)
        }
    }

    // ---------------- 自更新 ----------------

    private fun checkForUpdate() {
        lifecycleScope.launch {
            val m = UpdateChecker.fetch() ?: return@launch
            if (m.versionCode <= BuildConfig.VERSION_CODE) return@launch
            if (isFinishing || isDestroyed) return@launch

            val msg = buildString {
                append(getString(R.string.update_found, m.versionName))
                if (m.notes.isNotBlank()) {
                    append("\n\n")
                    append(m.notes)
                }
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.update_title)
                .setMessage(msg)
                .setPositiveButton(R.string.update_now) { _, _ -> startDownload(m) }
                .setNegativeButton(R.string.update_later, null)
                .show()
        }
    }

    private fun startDownload(m: UpdateChecker.Manifest) {
        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.update_downloading)
            .setMessage("0%")
            .setCancelable(false)
            .create()
        dlg.show()

        lifecycleScope.launch {
            val apk = UpdateChecker.download(this@MainActivity, m) { pct ->
                runOnUiThread {
                    if (dlg.isShowing) {
                        dlg.setMessage(if (pct < 0) getString(R.string.update_downloading) else "$pct%")
                    }
                }
            }
            if (dlg.isShowing) dlg.dismiss()

            if (apk == null) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.update_title)
                    .setMessage(R.string.update_failed)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            promptInstall(apk)
        }
    }

    private fun promptInstall(apk: File) {
        if (UpdateChecker.canInstall(this)) {
            UpdateChecker.install(this, apk)
            return
        }
        // 需要用户先授予「安装未知应用」权限
        pendingApk = apk
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AlertDialog.Builder(this)
                .setTitle(R.string.update_title)
                .setMessage(R.string.update_need_permission)
                .setPositiveButton(R.string.update_goto_settings) { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                .setNegativeButton(R.string.update_later, null)
                .show()
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
