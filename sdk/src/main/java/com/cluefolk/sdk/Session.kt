package com.cluefolk.sdk

import java.security.SecureRandom
import java.util.Random
import org.json.JSONObject

internal data class Session(
    val token: String,
    val expires: Long,
    val playerId: String,
    val kind: String,
    val externalUserId: String?,
    val claimedExternalId: String,
) {
    init {
        require(token.isNotEmpty() && playerId.isNotEmpty())
        require(kind == "anonymous" || (kind == "verified" && !externalUserId.isNullOrEmpty()))
    }

    fun player(): JSONObject = JSONObject().put("kind", kind)
        .put("externalUserId", externalUserId ?: JSONObject.NULL).put("claimedExternalId", claimedExternalId)

    fun toJSON(): JSONObject = JSONObject().put("sessionToken", token).put("expires", expires)
        .put("playerId", playerId).put("playerKind", kind)
        .put("externalUserId", externalUserId ?: JSONObject.NULL).put("claimedExternalId", claimedExternalId)

    companion object {
        /** Reads a server session response or a stored session; [expires] is in epoch milliseconds. */
        fun parse(json: JSONObject, expires: Long) = Session(
            json.getString("sessionToken"), expires, json.getString("playerId"), json.getString("playerKind"),
            if (json.isNull("externalUserId")) null else json.getString("externalUserId"),
            json.optString("claimedExternalId"),
        )
    }
}

/** Per-install anonymous credential: 32 random bytes, base64url without padding. */
internal object InstallSecret {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun generate(random: Random = SecureRandom()): String = encode(ByteArray(32).also(random::nextBytes))

    fun valid(value: String): Boolean = value.length == 43 && value.all { it in ALPHABET }

    // android.util.Base64 is unavailable in JVM tests and java.util.Base64 needs API 26.
    fun encode(bytes: ByteArray): String {
        val output = StringBuilder((bytes.size * 4 + 2) / 3)
        for (start in bytes.indices step 3) {
            var chunk = 0
            for (offset in 0..2) chunk = chunk shl 8 or (bytes.getOrElse(start + offset) { 0 }.toInt() and 255)
            for (index in 0 until minOf(4, bytes.size - start + 1)) output.append(ALPHABET[chunk shr 18 - 6 * index and 63])
        }
        return output.toString()
    }
}
