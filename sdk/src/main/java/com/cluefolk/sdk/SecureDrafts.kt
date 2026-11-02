package com.cluefolk.sdk

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Called only on the SDK's draft I/O executor. Neither keys nor plaintext enter
// WebView storage, and AES-GCM binds every record to its game account and draft.
internal class SecureDrafts(context: Context) {
    private val preferences = context.getSharedPreferences("cluefolk.drafts", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("cluefolk.drafts", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("cluefolk.drafts", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun identifier(namespace: String, draft: String): String =
        MessageDigest.getInstance("SHA-256").digest("$namespace/$draft".toByteArray()).joinToString("") { "%02x".format(it) }

    fun read(namespace: String, draft: String): String? {
        val id = identifier(namespace, draft)
        if (preferences.getLong("time.$id", 0) < System.currentTimeMillis() - 30L * 86400 * 1000) return null
        val raw = preferences.getString("entry.$id", null) ?: return null
        val data = Base64.decode(raw, Base64.NO_WRAP)
        require(data.size in 29..102_428)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        cipher.updateAAD("$namespace/$draft".toByteArray())
        return String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
    }

    fun write(namespace: String, draft: String, content: String?) {
        val id = identifier(namespace, draft)
        val editor = preferences.let(SharedPreferences::edit)
        if (content == null) {
            editor.remove("entry.$id").remove("time.$id")
        } else {
            val data = content.toByteArray(Charsets.UTF_8)
            require(data.size <= 102_400)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD("$namespace/$draft".toByteArray())
            editor.putString("entry.$id", Base64.encodeToString(cipher.iv + cipher.doFinal(data), Base64.NO_WRAP))
                .putLong("time.$id", System.currentTimeMillis())
            val entries = preferences.all.filterKeys { it.startsWith("time.") }.entries.sortedBy { it.value as? Long ?: 0 }
            val expiredBefore = System.currentTimeMillis() - 30L * 86400 * 1000
            val keep = entries.filter { (it.value as? Long ?: 0) >= expiredBefore && it.key != "time.$id" }.takeLast(19).map { it.key }.toSet()
            entries.filter { it.key != "time.$id" && it.key !in keep }.forEach {
                editor.remove(it.key).remove("entry." + it.key.removePrefix("time."))
            }
        }
        // KTX edit(commit=true) discards this result; a draft acknowledgement
        // must instead confirm that its synchronous write succeeded.
        check(editor.commit())
    }

    fun clear() {
        check(preferences.let(SharedPreferences::edit).clear().commit())
    }
}
