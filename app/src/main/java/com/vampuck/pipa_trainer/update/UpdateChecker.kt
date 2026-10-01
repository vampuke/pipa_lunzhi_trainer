package com.vampuck.pipa_trainer.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 自更新：查询服务器清单，比较 versionCode，下载 APK（校验 md5）并调起系统安装器。
 *
 * Android 对第三方 App 没有真正的静默更新——即便下载完成，仍需用户在系统
 * 安装器点「安装」。本类只负责把流程做到那一步前的全部自动化。
 */
object UpdateChecker {

    /** 版本清单端点（B 方案：自建 ora）。*/
    const val MANIFEST_URL = "https://ora.vampuck.com/dl/pipa_latest.json"

    data class Manifest(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val md5: String,
        val notes: String
    )

    /** 后台抓清单并解析。失败返回 null（静默，不打扰用户）。*/
    suspend fun fetch(): Manifest? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("Cache-Control", "no-cache")
            }
            conn.inputStream.use { ins ->
                val text = ins.readBytes().toString(Charsets.UTF_8)
                val o = JSONObject(text)
                Manifest(
                    versionCode = o.getInt("versionCode"),
                    versionName = o.getString("versionName"),
                    apkUrl = o.getString("apkUrl"),
                    md5 = o.optString("md5", ""),
                    notes = o.optString("notes", "")
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 下载 APK 到应用缓存目录，校验 md5。
     * @param onProgress 0..100，-1 表示总长度未知
     * @return 下载好的文件；失败返回 null
     */
    suspend fun download(
        activity: Activity,
        m: Manifest,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
            // 清掉旧的下载残留
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "pipa_${m.versionName}.apk")

            val conn = (URL(m.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 20000
                requestMethod = "GET"
            }
            if (conn.responseCode !in 200..299) return@withContext null
            val total = conn.contentLength
            val md = MessageDigest.getInstance("MD5")
            conn.inputStream.use { ins ->
                out.outputStream().use { os ->
                    val buf = ByteArray(16 * 1024)
                    var read: Int
                    var done = 0L
                    while (ins.read(buf).also { read = it } != -1) {
                        os.write(buf, 0, read)
                        md.update(buf, 0, read)
                        done += read
                        onProgress(if (total > 0) ((done * 100) / total).toInt() else -1)
                    }
                }
            }

            // md5 校验（清单未给 md5 则跳过）
            if (m.md5.isNotBlank()) {
                val got = BigInteger(1, md.digest()).toString(16).padStart(32, '0')
                if (!got.equals(m.md5, ignoreCase = true)) {
                    out.delete()
                    return@withContext null
                }
            }
            out
        } catch (_: Throwable) {
            null
        }
    }

    /** 调起系统安装器。调用方需确保已有「安装未知应用」权限。*/
    fun install(activity: Activity, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            activity, "${activity.packageName}.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    /** 当前 App 是否允许安装未知来源（API 26+ 为按应用授权）。*/
    fun canInstall(activity: Activity): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            activity.packageManager.canRequestPackageInstalls()
        else true
    }
}
