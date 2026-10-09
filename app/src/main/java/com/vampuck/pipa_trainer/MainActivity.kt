package com.vampuck.pipa_trainer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vampuck.pipa_trainer.databinding.ActivityMainBinding
import com.vampuck.pipa_trainer.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // 读回用户导入的简谱（解析放到 IO 线程：谱子多了不该卡启动）
        lifecycleScope.launch(Dispatchers.IO) {
            com.vampuck.pipa_trainer.data.ImportStore.loadAll(applicationContext)
        }

        bindFeature(b.itFile, R.drawable.ic_file, R.string.feat_file_title, R.string.feat_file_sub)
        bindFeature(b.itLive, R.drawable.ic_live, R.string.feat_live_title, R.string.feat_live_sub)
        bindFeature(b.itStrength, R.drawable.ic_strength, R.string.feat_strength_title, R.string.feat_strength_sub)
        bindFeature(b.itTuner, R.drawable.ic_tuner, R.string.feat_tuner_title, R.string.feat_tuner_sub)
        bindFeature(b.itMetronome, R.drawable.ic_metronome, R.string.feat_metronome_title, R.string.feat_metronome_sub)
        bindFeature(b.itPlay, R.drawable.ic_play, R.string.feat_play_title, R.string.feat_play_sub)

        b.btnFile.setOnClickListener {
            startActivity(Intent(this, FileAnalysisActivity::class.java))
        }
        b.btnLive.setOnClickListener {
            startActivity(Intent(this, LiveActivity::class.java))
        }
        b.btnStrength.setOnClickListener {
            startActivity(Intent(this, StrengthTrainingActivity::class.java))
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
        // 从「允许安装未知应用」设置页返回：如已授权且有待装 APK，继续安装。
        // 路径存在 SharedPreferences 里——只放内存字段的话，跳设置页途中被系统
        // 回收，这次更新就静默丢了。
        val apk = loadPendingApk()
        if (apk != null && UpdateChecker.canInstall(this)) {
            savePendingApk(null)
            UpdateChecker.install(this, apk)
        }
    }

    // ---------------- 待安装 APK 的持久化 ----------------

    private fun prefs() = getSharedPreferences("update_state", MODE_PRIVATE)

    private fun savePendingApk(f: File?) {
        prefs().edit().putString(KEY_PENDING_APK, f?.absolutePath ?: "").apply()
    }

    private fun loadPendingApk(): File? {
        val p = prefs().getString(KEY_PENDING_APK, "").orEmpty()
        return if (p.isBlank()) null else File(p).takeIf { it.exists() }
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
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.update_title)
                .setMessage(msg)
                .setPositiveButton(R.string.update_now) { _, _ -> startDownload(m) }
                .setNegativeButton(R.string.update_later, null)
                .show()
        }
    }

    private fun startDownload(m: UpdateChecker.Manifest) {
        val dlg = MaterialAlertDialogBuilder(this)
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
            if (isFinishing || isDestroyed) return@launch

            if (apk == null) {
                MaterialAlertDialogBuilder(this@MainActivity)
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
        if (isFinishing || isDestroyed) return
        if (UpdateChecker.canInstall(this)) {
            UpdateChecker.install(this, apk)
            return
        }
        // 需要用户先授予「安装未知应用」权限
        savePendingApk(apk)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            MaterialAlertDialogBuilder(this)
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

    private companion object {
        const val KEY_PENDING_APK = "pending_apk"
    }
}
