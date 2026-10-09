package com.vampuck.pipa_trainer.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.widget.Toast
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
 * 自更新：查询服务器清单，比较 versionCode，下载 APK（校验 md5 + 签名）并调起系统安装器。
 *
 * Android 对第三方 App 没有真正的静默更新——即便下载完成，仍需用户在系统
 * 安装器点「安装」。本类只负责把流程做到那一步前的全部自动化。
 */
object UpdateChecker {

    /** 版本清单端点（B 方案：自建 ora）。*/
    const val MANIFEST_URL = "https://ora.vampuck.com/dl/pipa_latest.json"

    /** 只允许从自家域名下载，且必须 https。 */
    private val ALLOWED_HOSTS = setOf("ora.vampuck.com")

    private val MD5_RE = Regex("[0-9a-fA-F]{32}")

    private const val DAY_MS = 24L * 60 * 60 * 1000

    data class Manifest(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val md5: String,
        val notes: String
    )

    /** 后台抓清单并解析。失败返回 null（静默，不打扰用户）。*/
    suspend fun fetch(): Manifest? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
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
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /**
     * 下载 APK 到应用缓存目录，校验 md5 与签名。
     * @param onProgress 0..100，-1 表示总长度未知
     * @return 下载好的文件；失败返回 null
     */
    suspend fun download(
        activity: Activity,
        m: Manifest,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            // 清单里的 md5 缺失 / 格式不对 = 直接失败。原实现「为空就跳过校验」，
            // 一次漏填就让整个完整性校验静默消失。
            if (!MD5_RE.matches(m.md5)) return@withContext null
            if (!allowedUrl(m.apkUrl)) return@withContext null

            val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
            // 远端 versionName 直接拼进文件名会被 `../` 之类穿透；只留白名单字符。
            val safeName = m.versionName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(32)
            val out = File(dir, "pipa_$safeName.apk")
            // 清掉旧残留，但别动我们马上要写的这个——MainActivity 可能还握着
            // 上一次下载好、等待用户去开「安装未知应用」权限的那个包。
            val cutoff = System.currentTimeMillis() - DAY_MS
            dir.listFiles()?.forEach { f ->
                if (f.name != out.name && f.lastModified() < cutoff) f.delete()
            }

            conn = (URL(m.apkUrl).openConnection() as HttpURLConnection).apply {
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

            val got = BigInteger(1, md.digest()).toString(16).padStart(32, '0')
            if (!got.equals(m.md5, ignoreCase = true)) {
                out.delete()
                return@withContext null
            }
            // md5 与 apkUrl 同源，清单被改时两者一起被改；签名比对才是唯一能发现
            // 「换了个包」的检查。
            if (!signatureMatches(activity, out)) {
                out.delete()
                return@withContext null
            }
            out
        } catch (_: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun allowedUrl(url: String): Boolean = try {
        val u = URL(url)
        u.protocol == "https" && ALLOWED_HOSTS.contains(u.host)
    } catch (_: Throwable) {
        false
    }

    /**
     * 下载到的 APK 是否是「同一个 App、同一个签名」。
     *
     * 用 PackageManager 解析 APK 的签名证书，与已安装版本比对——签名不同，系统
     * 安装器本来也会拒绝覆盖安装，这里提前一步、并给出可读的失败。
     */
    fun signatureMatches(context: Context, apk: File): Boolean {
        val installed = packageDigests(context, installed = true, path = context.packageName)
        val candidate = packageDigests(context, installed = false, path = apk.absolutePath)
        return installed != null && installed.isNotEmpty() && installed == candidate
    }

    private fun packageDigests(
        context: Context,
        installed: Boolean,
        path: String
    ): Set<String>? = try {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = if (installed) pm.getPackageInfo(path, flags) else pm.getPackageArchiveInfo(path, flags)
        if (info == null || (!installed && info.packageName != context.packageName)) {
            null
        } else {
            @Suppress("DEPRECATION")
            val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                info.signingInfo?.apkContentsSigners
            else
                info.signatures
            sigs?.map { sha256(it.toByteArray()) }?.toSet()
        }
    } catch (_: Throwable) {
        null
    }

    private fun sha256(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /** 调起系统安装器。调用方需确保已有「安装未知应用」权限。*/
    fun install(activity: Activity, apk: File) {
        try {
            if (!canInstall(activity)) return
            if (!apk.exists()) return
            val uri: Uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (_: Throwable) {
            // 没有安装器 / 权限被临时收回：不能让更新流程把 App 打崩。
            Toast.makeText(activity, "无法调起系统安装器", Toast.LENGTH_LONG).show()
        }
    }

    /** 当前 App 是否允许安装未知来源（API 26+ 为按应用授权）。*/
    fun canInstall(activity: Activity): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            activity.packageManager.canRequestPackageInstalls()
        else true
    }
}
