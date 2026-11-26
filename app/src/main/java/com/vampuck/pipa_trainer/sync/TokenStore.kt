package com.vampuck.pipa_trainer.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONException
import org.json.JSONObject

class TokenStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "account")
    private val file = AtomicFile(File(directory, "session.bin"))

    @Synchronized
    fun save(session: AccountSession): Boolean {
        var output: FileOutputStream? = null
        return try {
            check(directory.isDirectory || directory.mkdirs())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(AAD)
            val plaintext = JSONObject().put("token", session.token).put("userId", session.userId)
                .put("username", session.username).put("expiresAt", session.expiresAt).toString()
            val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val bytes = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use {
                    it.writeInt(1)
                    it.writeInt(cipher.iv.size)
                    it.write(cipher.iv)
                    it.write(encrypted)
                }
            }.toByteArray()
            output = file.startWrite()
            output.write(bytes)
            file.finishWrite(output)
            true
        } catch (_: Exception) {
            if (output != null) runCatching { file.failWrite(output) }
            false
        }
    }

    @Synchronized
    fun load(): AccountSession? = try {
        DataInputStream(file.openRead()).use { input ->
            requireValidFile(input.readInt() == 1)
            val ivLength = input.readInt()
            requireValidFile(ivLength == 12)
            val iv = ByteArray(ivLength).also(input::readFully)
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(1024)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                requireValidFile(buffer.size() + count <= 16 * 1024)
                buffer.write(chunk, 0, count)
            }
            requireValidFile(buffer.size() >= 16) // A GCM ciphertext must contain its authentication tag.
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.updateAAD(AAD)
            val json = JSONObject(String(cipher.doFinal(buffer.toByteArray()), Charsets.UTF_8))
            AccountSession(json.getString("token"), json.getString("userId"),
                json.getString("username"), json.getLong("expiresAt")).also {
                requireValidFile(it.token.matches(Regex("[A-Za-z0-9_-]{43}")))
                requireValidFile(it.userId.isNotBlank() && it.username.isNotBlank() && it.expiresAt > 0)
            }
        }
    } catch (error: Exception) {
        // Keep the last good file through temporary keystore/provider and read failures.
        if (error is AEADBadTagException || error is KeyPermanentlyInvalidatedException ||
            error is EOFException || error is JSONException || error is MalformedSessionFileException) {
            clear()
        }
        null
    }

    @Synchronized
    fun clear() { runCatching { file.delete() } }

    private fun requireValidFile(condition: Boolean) {
        if (!condition) throw MalformedSessionFileException()
    }

    private class MalformedSessionFileException : Exception()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false).build())
        }.generateKey()
    }

    private companion object {
        const val ALIAS = "pipa_session_v1"
        val AAD = "com.vampuck.pipa_trainer:session:v1".toByteArray(Charsets.UTF_8)
    }
}
