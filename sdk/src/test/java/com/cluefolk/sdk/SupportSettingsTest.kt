package com.cluefolk.sdk

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupportSettingsTest {
    @Test
    fun playerIdIsTrimmedAndBlankClears() {
        assertEquals("hero#1", SupportSettings.playerId("hero#1"))
        assertEquals("hero 1", SupportSettings.playerId(" \t hero 1 \n"))
        assertEquals("hero", SupportSettings.playerId("\u00A0\u3000hero\u2009\u0085"))
        assertEquals("", SupportSettings.playerId(null))
        assertEquals("", SupportSettings.playerId(""))
        assertEquals("", SupportSettings.playerId(" \r\n\t "))
    }

    @Test
    fun playerIdAllowsUpTo256CharactersAfterTrimming() {
        val longest = "a".repeat(256)
        assertEquals(longest, SupportSettings.playerId(longest))
        assertEquals(longest, SupportSettings.playerId("  $longest  "))
        assertNull(SupportSettings.playerId("a".repeat(257)))
        // The server counts code points: 256 emoji are 512 UTF-16 chars.
        val emoji = "\uD83D\uDE00".repeat(256)
        assertEquals(emoji, SupportSettings.playerId(emoji))
        assertNull(SupportSettings.playerId(emoji + "a"))
    }

    @Test
    fun playerIdWithLineBreaksNulOrBrokenTextIsInvalid() {
        assertNull(SupportSettings.playerId("hero\n1"))
        assertNull(SupportSettings.playerId("hero\r1"))
        assertNull(SupportSettings.playerId("hero\u00001"))
        assertNull(SupportSettings.playerId("hero\u0000"))
        assertNull(SupportSettings.playerId("hero\uD83D"))
        assertNull(SupportSettings.playerId("\uDE00hero"))
        assertEquals("hero\t1", SupportSettings.playerId("hero\t1"))
    }

    @Test
    fun freeRotationOverridesTheGameOrientation() {
        for (game in listOf(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
        )) {
            assertEquals(game, SupportSettings.orientation(false, game))
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, SupportSettings.orientation(true, game))
        }
    }
}
