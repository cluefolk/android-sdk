package com.cluefolk.sdk

import java.util.Base64
import java.util.Random
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {
    @Test
    fun installSecretIs43Base64UrlCharactersOf32Bytes() {
        repeat(64) {
            val secret = InstallSecret.generate()
            assertEquals(43, secret.length)
            assertTrue(secret, InstallSecret.valid(secret))
            assertTrue(secret, secret.matches(Regex("[A-Za-z0-9_-]{43}")))
            assertEquals(32, Base64.getUrlDecoder().decode(secret).size)
        }
        assertNotEquals(InstallSecret.generate(), InstallSecret.generate())
    }

    @Test
    fun installSecretEncodingIsUnpaddedBase64Url() {
        val random = Random(7)
        for (size in 0..40) {
            val bytes = ByteArray(size).also(random::nextBytes)
            assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), InstallSecret.encode(bytes))
        }
    }

    @Test
    fun malformedInstallSecretsAreInvalid() {
        assertFalse(InstallSecret.valid("A".repeat(42)))
        assertFalse(InstallSecret.valid("A".repeat(44)))
        assertFalse(InstallSecret.valid("A".repeat(42) + "+"))
        assertFalse(InstallSecret.valid("A".repeat(42) + "="))
    }

    @Test
    fun sessionResponsesAndStoredSessionsParse() {
        val anonymous = JSONObject().put("sessionToken", "token").put("sessionExpiresAt", "2026-11-27T12:00:00.5Z")
            .put("playerId", "player").put("playerKind", "anonymous").put("externalUserId", JSONObject.NULL)
            .put("claimedExternalId", "hero#1").put("eventCursor", "12345:0").put("merged", false)
        val session = Session.parse(anonymous, Timestamps.parseRfc3339(anonymous.getString("sessionExpiresAt")))
        assertEquals("anonymous", session.kind)
        assertNull(session.externalUserId)
        assertEquals("hero#1", session.claimedExternalId)
        val stored = session.toJSON()
        assertEquals(session, Session.parse(stored, stored.getLong("expires")))
        val verified = JSONObject(anonymous.toString()).put("playerKind", "verified").put("externalUserId", "user-1")
        assertEquals("user-1", Session.parse(verified, 1L).externalUserId)
        assertThrows(IllegalArgumentException::class.java) {
            Session.parse(JSONObject(anonymous.toString()).put("playerKind", "verified"), 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Session.parse(JSONObject(anonymous.toString()).put("playerKind", "guest"), 1L)
        }
    }
}
