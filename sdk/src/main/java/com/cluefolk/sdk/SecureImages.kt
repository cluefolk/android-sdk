package com.cluefolk.sdk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// The player's own previews of images they sent: the server never returns images to
// players. Called only on the SDK's storage I/O executor. Every file is a 12-byte IV
// followed by AES-GCM ciphertext and its 128-bit tag, with the game account and the
// attachment ID as AAD, so a file cannot be read under another account or ID. The files
// live in no-backup storage, never in WebView storage.
internal class SecureImages(
    private val directory: File,
    private val key: () -> SecretKey,
    private val clock: () -> Long = System::currentTimeMillis,
    private val limit: Int = LIMIT,
) {
    /** Null when there is no copy; an unreadable or altered copy is deleted. */
    fun read(namespace: String, id: String): ByteArray? {
        val file = File(directory, name(namespace, id))
        if (!file.isFile) return null
        val record = file.readBytes()
        if (record.size !in IV_SIZE + TAG_SIZE + 3..IV_SIZE + MAX_BYTES + TAG_SIZE) return discard(file)
        // A key or Keystore failure is a storage failure and keeps the file.
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_SIZE * 8, record, 0, IV_SIZE))
        cipher.updateAAD(binding(namespace, id))
        val jpeg = try {
            cipher.doFinal(record, IV_SIZE, record.size - IV_SIZE)
        } catch (_: GeneralSecurityException) {
            return discard(file)
        }
        return if (isJpeg(jpeg)) jpeg else discard(file)
    }

    fun write(namespace: String, id: String, jpeg: ByteArray) {
        require(isJpeg(jpeg))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(binding(namespace, id))
        val sealed = cipher.doFinal(jpeg)
        val iv = cipher.iv
        check(iv.size == IV_SIZE && sealed.size == jpeg.size + TAG_SIZE)
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("No image directory")
        val target = File(directory, name(namespace, id))
        val temp = File(directory, target.name + ".tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(iv)
                output.write(sealed)
                output.fd.sync()
            }
            if (!temp.renameTo(target)) throw IOException("Image copy not replaced")
        } finally {
            temp.delete()
        }
        target.setLastModified(clock())
        evict(target)
    }

    fun clear() {
        val remaining = directory.listFiles()?.filterNot { it.deleteRecursively() }
        check(remaining.isNullOrEmpty())
    }

    // Keeps the newest copies by modification time, the one just written always among them.
    private fun evict(current: File) {
        val (records, stray) = (directory.listFiles() ?: return).partition { NAME.matches(it.name) }
        stray.forEach { it.delete() } // Temporary files of interrupted writes.
        records.filter { it != current }.map { it to it.lastModified() }.sortedByDescending { it.second }
            .drop(limit - 1).forEach { it.first.delete() }
    }

    private fun discard(file: File): ByteArray? {
        file.delete()
        return null
    }

    private fun binding(namespace: String, id: String) = "$namespace/$id".toByteArray(Charsets.UTF_8)

    private fun name(namespace: String, id: String): String =
        MessageDigest.getInstance("SHA-256").digest(binding(namespace, id)).joinToString("") { "%02x".format(it) }

    companion object {
        const val LIMIT = 200
        const val MAX_BYTES = 90_000
        private const val IV_SIZE = 12
        private const val TAG_SIZE = 16
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ALIAS = "cluefolk.images"
        private val NAME = Regex("[0-9a-f]{64}")

        fun open(context: Context) = SecureImages(File(context.noBackupFilesDir, "cluefolk-images"), ::keystoreKey)

        /** A preview the SDK keeps: a JPEG of at most [MAX_BYTES] bytes. */
        fun isJpeg(data: ByteArray): Boolean =
            data.size in 3..MAX_BYTES && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()

        private fun keystoreKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    }
}
