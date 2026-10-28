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
import com.vampuck.pipa_trainer.practice.PracticeDay
import com.vampuck.pipa_trainer.practice.PracticeFormat
import com.vampuck.pipa_trainer.practice.PracticeSource
import com.vampuck.pipa_trainer.practice.PracticeStats
import com.vampuck.pipa_trainer.practice.PracticeStore
import com.vampuck.pipa_trainer.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val practiceStore by lazy { PracticeStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        bindFeature(b.itStrength, R.drawable.ic_strength, R.string.feat_strength_title, R.string.feat_strength_sub)
        bindFeature(b.itTuner, R.drawable.ic_tuner, R.string.feat_tuner_title, R.string.feat_tuner_sub)
        bindFeature(b.itMetronome, R.drawable.ic_metronome, R.string.feat_metronome_title, R.string.feat_metronome_sub)

        b.btnStrength.setOnClickListener {
            startActivity(Intent(this, StrengthTrainingActivity::class.java))
        }
        b.btnTuner.setOnClickListener {
            startActivity(Intent(this, TunerActivity::class.java))
        }
        b.btnMetronome.setOnClickListener {
            startActivity(Intent(this, MetronomeActivity::class.java))
        }

        b.practiceActivity.activityHeatmap.onDayClick = { showPracticeDay(it) }
        checkForUpdate()
    }

    override fun onResume() {
        super.onResume()
        renderPracticeActivity()
        // 从「允许安装未知应用」设置页返回：如已授权且有待装 APK，继续安装。
        // 路径存在 SharedPreferences 里——只放内存字段的话，跳设置页途中被系统
        // 回收，这次更新就静默丢了。
        val apk = loadPendingApk()
        if (apk != null && UpdateChecker.canInstall(this)) {
            savePendingApk(null)
            UpdateChecker.install(this, apk)
        }
    }

    private fun renderPracticeActivity() {
        val log = practiceStore.load()
        val today = PracticeDay.today()
        val todayKey = today
        b.practiceActivity.todayPractice.text = PracticeFormat.duration(log.totalMs(todayKey))
        b.practiceActivity.streakPractice.text = "连续 ${PracticeStats.streak(log, todayKey)} 天"
        b.practiceActivity.yearPractice.text = "近一年 ${PracticeFormat.duration(log.totalMsSince(PracticeDay.shift(today, -364)))}"
        b.practiceActivity.activityHeatmap.submit(log, today)
        b.practiceActivity.activityScroll.post {
            b.practiceActivity.activityScroll.fullScroll(android.view.View.FOCUS_RIGHT)
        }
    }

    private fun showPracticeDay(day: String) {
        val log = practiceStore.load()
        val basics = PracticeFormat.duration(log.totalMs(day, PracticeSource.BASICS))
        val metro = PracticeFormat.duration(log.totalMs(day, PracticeSource.METRONOME))
        val message = "${getString(R.string.activity_day_total)}：${PracticeFormat.duration(log.totalMs(day))}\n\n" +
            "${getString(R.string.activity_basics)}：$basics\n${getString(R.string.activity_basics_note)}\n\n" +
            "${getString(R.string.activity_metronome)}：$metro\n${getString(R.string.activity_metronome_note)}\n\n" +
            getString(R.string.activity_local_note)
        MaterialAlertDialogBuilder(this)
            .setTitle(PracticeFormat.fullDate(day))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
