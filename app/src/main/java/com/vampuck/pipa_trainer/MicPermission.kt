package com.vampuck.pipa_trainer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.content.ContextCompat

/**
 * 麦克风权限的统一处理。
 *
 * 抽出来是因为「实时练习」和「调音器」原来各自只写了一句
 * `if (granted) start() else Toast(...)`：用户点两次拒绝（或勾「不再询问」）之后，
 * 这两个功能就永久不可用，界面只留一句 Toast，没有任何去系统设置的入口。
 */
internal object MicPermission {

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 权限被拒后的恢复路径：
     *  - 还能再问（rationale 为真）→ 说明理由 + 「重新授权」；
     *  - 系统已不再弹窗 → 引导去「应用信息 → 权限」手动打开。
     */
    fun showHelp(activity: Activity, onRetry: () -> Unit) {
        val rationale = activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        if (rationale) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.mic_denied_title)
                .setMessage(R.string.mic_denied_msg)
                .setNegativeButton(R.string.strength_cancel, null)
                .setPositiveButton(R.string.mic_denied_retry) { _, _ -> onRetry() }
                .show()
        } else {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.mic_blocked_title)
                .setMessage(R.string.mic_blocked_msg)
                .setNegativeButton(R.string.strength_cancel, null)
                .setPositiveButton(R.string.mic_open_settings) { _, _ -> openSettings(activity) }
                .show()
        }
    }

    fun openSettings(activity: Activity) {
        try {
            activity.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.packageName, null)
                )
            )
        } catch (_: Throwable) {
            Toast.makeText(activity, R.string.mic_settings_failed, Toast.LENGTH_LONG).show()
        }
    }
}
