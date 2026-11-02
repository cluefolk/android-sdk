package com.cluefolk.sdk

import android.content.pm.ActivityInfo

/** Pure rules behind setPlayerId and the support screen orientation; Cluefolk applies them on main. */
internal object SupportSettings {
    const val PLAYER_ID_MAX_LENGTH = 256

    /**
     * The game's player ID as the server stores it (claimedExternalId): trimmed the way the
     * server trims, "" for null or blank (clears it), or null when invalid: more than 256
     * characters (code points, as the server counts), CR, LF, NUL or broken UTF-16.
     */
    fun playerId(value: String?): String? {
        val trimmed = value.orEmpty().trim(::isSpace)
        if (trimmed.codePointCount(0, trimmed.length) > PLAYER_ID_MAX_LENGTH) return null
        var index = 0
        while (index < trimmed.length) {
            val char = trimmed[index]
            when {
                char == '\r' || char == '\n' || char == '\u0000' -> return null
                char.isHighSurrogate() ->
                    if (index + 1 < trimmed.length && trimmed[index + 1].isLowSurrogate()) index++ else return null
                char.isLowSurrogate() -> return null
            }
            index++
        }
        return trimmed
    }

    /** Support turns the way the game does unless free rotation (from the appearance) is on. */
    fun orientation(freeRotation: Boolean, gameOrientation: Int): Int =
        if (freeRotation) ActivityInfo.SCREEN_ORIENTATION_FULL_USER else gameOrientation

    // Go's unicode.IsSpace (strings.TrimSpace on the server), so the stored value matches.
    private fun isSpace(char: Char): Boolean = when (char) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u0085', '\u00A0',
        '\u1680', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000' -> true
        else -> char in '\u2000'..'\u200A'
    }
}
