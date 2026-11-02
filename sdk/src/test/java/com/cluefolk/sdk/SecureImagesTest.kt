package com.cluefolk.sdk

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// AndroidKeyStore is unavailable in JVM tests; a plain AES key exercises the same records.
class SecureImagesTest {
    @get:Rule val folder = TemporaryFolder()

    private val account = "project/https://cluefolk.com/player-1"
    private val other = "project/https://cluefolk.com/player-2"
    private val id = "0b6a9c3e-7d1f-4e2a-9c58-3f0e2d1b4a77"
    private val key = aesKey()
    private var now = 1_700_000_000_000L
    private val directory by lazy { File(folder.root, "cluefolk-images") }

    private fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    // Whole seconds apart, so the order survives file systems with coarse timestamps.
    private fun store(key: () -> SecretKey = { this.key }) = SecureImages(directory, key, { now += 1_000; now })

    private fun jpeg(seed: Int, size: Int = 512) = ByteArray(size) { (it * 31 + seed).toByte() }.also {
        it[0] = 0xFF.toByte()
        it[1] = 0xD8.toByte()
        it[2] = 0xFF.toByte()
    }

    private fun uuid(index: Int) = "00000000-0000-4000-8000-%012x".format(index)

    private fun file(namespace: String, id: String) = File(directory,
        MessageDigest.getInstance("SHA-256").digest("$namespace/$id".toByteArray()).joinToString("") { "%02x".format(it) })

    private fun records() = directory.listFiles().orEmpty().filter { !it.name.endsWith(".tmp") }

    @Test
    fun roundTripStoresOnlyCiphertext() {
        val images = store()
        val image = jpeg(1)
        images.write(account, id, image)
        assertArrayEquals(image, images.read(account, id))
        val stored = file(account, id)
        assertEquals(listOf(stored), directory.listFiles()!!.toList())
        val record = stored.readBytes()
        assertEquals(12 + image.size + 16, record.size)
        assertFalse(record.copyOfRange(12, 12 + image.size).contentEquals(image))
        val replacement = jpeg(2, SecureImages.MAX_BYTES)
        images.write(account, id, replacement)
        assertArrayEquals(replacement, images.read(account, id))
        assertEquals(1, records().size)
    }

    @Test
    fun anotherAccountOrIdCannotRead() {
        val images = store()
        images.write(account, id, jpeg(1))
        assertNull(images.read(other, id))
        assertNull(images.read(account, uuid(1)))
        // The AAD binds the record to its account and ID, not only the file name.
        file(account, id).copyTo(file(other, id))
        file(account, id).copyTo(file(account, uuid(1)))
        assertNull(images.read(other, id))
        assertNull(images.read(account, uuid(1)))
        assertFalse(file(other, id).exists())
        assertFalse(file(account, uuid(1)).exists())
        assertNotNull(images.read(account, id))
    }

    @Test
    fun corruptCopiesReadAsMissingAndAreRemoved() {
        val images = store()
        val target = file(account, id)

        images.write(account, id, jpeg(1))
        val record = target.readBytes()
        record[record.size / 2] = (record[record.size / 2] + 1).toByte()
        target.writeBytes(record)
        assertNull(images.read(account, id))
        assertFalse(target.exists())

        images.write(account, id, jpeg(1))
        target.writeBytes(target.readBytes().copyOf(20))
        assertNull(images.read(account, id))
        assertFalse(target.exists())

        // A copy sealed with a lost key cannot be opened with the current one.
        store { aesKey() }.write(account, id, jpeg(1))
        assertNull(images.read(account, id))
        assertFalse(target.exists())

        // An authentic record whose content is not a JPEG preview.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD("$account/$id".toByteArray())
        target.writeBytes(cipher.iv + cipher.doFinal(ByteArray(64) { 1 }))
        assertNull(images.read(account, id))
        assertFalse(target.exists())
    }

    @Test
    fun keyFailureIsAStorageErrorAndKeepsTheCopy() {
        store().write(account, id, jpeg(1))
        val broken = store { throw IOException("Keystore unavailable") }
        assertThrows(IOException::class.java) { broken.read(account, id) }
        assertThrows(IOException::class.java) { broken.write(account, uuid(1), jpeg(2)) }
        assertTrue(file(account, id).exists())
        assertNotNull(store().read(account, id))
    }

    @Test
    fun keepsTheNewest200Copies() {
        val images = store()
        File(directory.apply { mkdirs() }, "f".repeat(64) + ".tmp").writeBytes(ByteArray(8))
        for (index in 0 until SecureImages.LIMIT + 5) images.write(account, uuid(index), jpeg(index))
        assertEquals(SecureImages.LIMIT, directory.listFiles()!!.size)
        for (index in 0 until 5) assertNull(images.read(account, uuid(index)))
        for (index in 5 until SecureImages.LIMIT + 5) assertArrayEquals(jpeg(index), images.read(account, uuid(index)))
        // Writing an existing ID again makes it the newest copy.
        images.write(account, uuid(5), jpeg(5))
        images.write(account, uuid(1000), jpeg(1000))
        assertNotNull(images.read(account, uuid(5)))
        assertNull(images.read(account, uuid(6)))
        assertEquals(SecureImages.LIMIT, records().size)
    }

    @Test
    fun clearRemovesEveryCopy() {
        val images = store()
        images.clear()
        for (index in 0 until 3) images.write(account, uuid(index), jpeg(index))
        images.write(other, id, jpeg(9))
        images.clear()
        assertEquals(0, directory.listFiles()!!.size)
        assertNull(images.read(other, id))
        images.write(account, id, jpeg(1))
        assertNotNull(images.read(account, id))
    }

    @Test
    fun onlyJpegPreviewsAreStored() {
        val images = store()
        assertThrows(IllegalArgumentException::class.java) { images.write(account, id, ByteArray(64)) }
        assertThrows(IllegalArgumentException::class.java) { images.write(account, id, jpeg(1, SecureImages.MAX_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { images.write(account, id, jpeg(1, 3).copyOf(2)) }
        assertFalse(SecureImages.isJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFE.toByte())))
        assertTrue(SecureImages.isJpeg(jpeg(1, 3)))
        assertNull(images.read(account, id))
        assertFalse(file(account, id).exists())
    }
}
