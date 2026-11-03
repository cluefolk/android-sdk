package com.cluefolk.sdk

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeContractTest {
    private val replies = setOf(BridgeProtocol.SDK_BOOTSTRAP, BridgeProtocol.SDK_ACK, BridgeProtocol.SDK_ERROR)
    private val schemaFile = resource("contracts/bridge.schema.json")
    private val schema = JSONObject(schemaFile.readText())
    private val fixtures = resource("contracts/bridge-fixtures").listFiles()!!
        .filter { it.name.endsWith(".json") }.sortedBy { it.name }.associate { it.name.removeSuffix(".json") to JSONObject(it.readText()) }

    private fun resource(path: String) = File(requireNotNull(javaClass.classLoader?.getResource(path)) { path }.toURI())

    private fun JSONObject.keySet(): Set<String> = keys().asSequence().toSet()

    private fun strings(values: JSONArray) = (0 until values.length()).map { values.getString(it) }

    // Key order is unspecified, so JSON is compared as plain maps and lists.
    private fun plain(value: Any?): Any? = when (value) {
        is JSONObject -> value.keySet().associateWith { plain(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { plain(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
    }

    private fun payloadRule(type: String): JSONObject {
        val rules = schema.getJSONArray("allOf")
        return (0 until rules.length()).map { rules.getJSONObject(it) }
            .single { it.getJSONObject("if").getJSONObject("properties").getJSONObject("type").getString("const") == type }
            .getJSONObject("then").getJSONObject("properties").getJSONObject("payload")
    }

    private fun bootstrap(session: Session, eventCursor: String?) = BridgeMessages.bootstrap(
        code = "Z".repeat(43),
        apiBaseUrl = "https://cluefolk.com",
        context = JSONObject().put("screen", "inventory").put("_cluefolk", JSONObject().put("platform", "android")),
        appearance = null,
        logo = null,
        language = "en-US",
        navigation = BridgeMessages.navigation("conversation", ""),
        unreadCount = 0,
        eventCursor = eventCursor,
        historySyncId = "b8f6",
        historyPageCount = 1,
        session = session,
    )

    @Test
    fun generatedProtocolMatchesTheSchema() {
        val digest = MessageDigest.getInstance("SHA-256").digest(schemaFile.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(BridgeProtocol.SCHEMA_SHA256, digest)
        val properties = schema.getJSONObject("properties")
        assertEquals(BridgeProtocol.VERSION, properties.getJSONObject("version").getInt("const"))
        assertEquals(BridgeProtocol.NATIVE_REQUESTS + replies, strings(properties.getJSONObject("type").getJSONArray("enum")).toSet())
        val navigation = payloadRule(BridgeProtocol.SDK_BOOTSTRAP).getJSONObject("properties").getJSONObject("navigation")
        assertEquals(BridgeProtocol.NAVIGATION_SCREENS,
            strings(navigation.getJSONObject("properties").getJSONObject("screen").getJSONArray("enum")).toSet())
    }

    @Test
    fun everyFixtureIsAKnownEnvelopeOfTheCurrentVersion() {
        assertEquals(BridgeProtocol.NATIVE_REQUESTS + replies, fixtures.keys)
        for ((name, envelope) in fixtures) {
            assertEquals(name, BridgeProtocol.VERSION, envelope.getInt("version"))
            val request = BridgeMessages.parse(envelope.toString())
            assertEquals(name, request.type)
            assertTrue(name, request.type in BridgeProtocol.NATIVE_REQUESTS || request.type in replies)
            if (request.type.startsWith("native.")) assertTrue(name, request.type in BridgeProtocol.NATIVE_REQUESTS)
        }
    }

    @Test
    fun otherProtocolVersionsAreRejected() {
        for (version in listOf(1, BridgeProtocol.VERSION + 1)) {
            val envelope = JSONObject(fixtures.getValue(BridgeProtocol.NATIVE_READY).toString()).put("version", version)
            assertThrows(IllegalArgumentException::class.java) { BridgeMessages.parse(envelope.toString()) }
        }
        val extra = JSONObject(fixtures.getValue(BridgeProtocol.NATIVE_READY).toString()).put("extra", true)
        assertThrows(IllegalArgumentException::class.java) { BridgeMessages.parse(extra.toString()) }
    }

    @Test
    fun malformedNativePayloadsAreRejectedWithTheirCorrelationIdAvailable() {
        fun rejected(type: String, payload: JSONObject) {
            val envelope = JSONObject(fixtures.getValue(type).toString()).put("payload", payload)
            assertEquals(envelope.getString("requestId"), BridgeMessages.requestId(envelope.toString()))
            assertThrows(type, IllegalArgumentException::class.java) { BridgeMessages.parse(envelope.toString()) }
        }
        rejected(BridgeProtocol.NATIVE_READY, JSONObject().put("extra", true))
        rejected(BridgeProtocol.NATIVE_UNREAD_COUNT_CHANGED, JSONObject().put("count", -1))
        rejected(BridgeProtocol.NATIVE_UNREAD_COUNT_CHANGED, JSONObject().put("count", "1"))
        rejected(BridgeProtocol.NATIVE_HISTORY_PAGE, JSONObject().put("syncId", "sync").put("index", 0).put("extra", true))
        rejected(BridgeProtocol.NATIVE_CONVERSATION_STATUS_CHANGED,
            JSONObject().put("conversationId", "conversation").put("status", "unknown"))
        rejected(BridgeProtocol.NATIVE_DRAFT_PUT, JSONObject().put("key", "new").put("content", "not-an-object"))
        rejected(BridgeProtocol.NATIVE_IMAGE_GET, JSONObject().put("id", "0b6a9c3e-7d1f-4e2a-9c58-3f0e2d1b4a77").put("extra", true))
        assertEquals(null, BridgeMessages.requestId("not json"))
        assertEquals(null, BridgeMessages.requestId("""{"requestId":""}"""))
    }

    @Test
    fun repliesUseTheFixtureEnvelope() {
        val fixture = fixtures.getValue(BridgeProtocol.SDK_ACK)
        val reply = JSONObject(BridgeMessages.reply("00000000-0000-4000-8000-000000000001", BridgeProtocol.SDK_ACK,
            fixture.getJSONObject("payload")))
        assertEquals(plain(fixture), plain(reply))
    }

    @Test
    fun bootstrapPayloadMatchesTheContract() {
        val rule = payloadRule(BridgeProtocol.SDK_BOOTSTRAP)
        val properties = rule.getJSONObject("properties")
        val fixture = fixtures.getValue(BridgeProtocol.SDK_BOOTSTRAP).getJSONObject("payload")
        val payload = bootstrap(Session("token", 1L, "player", "anonymous", null, ""), BridgeMessages.eventCursor("12345:0"))
        for (key in listOf("code", "apiBaseUrl", "context", "player")) assertTrue(key, payload.has(key))
        assertTrue(payload.keySet().containsAll(strings(rule.getJSONArray("required"))))
        assertTrue(properties.keySet().containsAll(payload.keySet()))
        assertEquals(fixture.keySet(), payload.keySet())
        assertTrue(payload.getJSONObject("navigation").getString("screen") in BridgeProtocol.NAVIGATION_SCREENS)
        assertEquals(plain(fixture.getJSONObject("navigation")), plain(payload.getJSONObject("navigation")))
        val player = properties.getJSONObject("player")
        assertEquals(strings(player.getJSONArray("required")).toSet(), payload.getJSONObject("player").keySet())
        assertEquals(plain(fixture.getJSONObject("player")), plain(payload.getJSONObject("player")))
        assertTrue(Regex(properties.getJSONObject("eventCursor").getString("pattern")).matches(payload.getString("eventCursor")))
    }

    @Test
    fun bootstrapPayloadOmitsUnknownCursor() {
        val payload = bootstrap(Session("token", 1L, "player", "verified", "user-1", "old-id"), BridgeMessages.eventCursor(12345L))
        assertFalse(payload.has("eventCursor"))
        assertEquals("verified", payload.getJSONObject("player").getString("kind"))
        assertEquals("user-1", payload.getJSONObject("player").getString("externalUserId"))
        assertEquals("old-id", payload.getJSONObject("player").getString("claimedExternalId"))
    }

    @Test
    fun imagePayloadRulesMatchTheSchema() {
        val put = payloadRule(BridgeProtocol.NATIVE_IMAGE_PUT)
        val get = payloadRule(BridgeProtocol.NATIVE_IMAGE_GET)
        assertEquals(setOf("id", "data"), strings(put.getJSONArray("required")).toSet())
        assertEquals(setOf("id"), strings(get.getJSONArray("required")).toSet())
        for (rule in listOf(put, get)) {
            assertFalse(rule.getBoolean("additionalProperties"))
            assertEquals("^${BridgeMessages.IMAGE_ID.pattern}$",
                rule.getJSONObject("properties").getJSONObject("id").getString("pattern"))
        }
        val data = put.getJSONObject("properties").getJSONObject("data")
        assertEquals("^${BridgeMessages.BASE64.pattern}$", data.getString("pattern"))
        assertEquals(BridgeMessages.IMAGE_DATA_MAX_LENGTH, data.getInt("maxLength"))
        // The longest base64 value decodes to exactly the largest preview the SDK keeps.
        assertEquals(SecureImages.MAX_BYTES, BridgeMessages.IMAGE_DATA_MAX_LENGTH / 4 * 3)
        for (type in listOf(BridgeProtocol.NATIVE_IMAGE_PUT, BridgeProtocol.NATIVE_IMAGE_GET)) {
            val request = BridgeMessages.parse(fixtures.getValue(type).toString())
            val image = BridgeMessages.image(request.type, request.payload)
            assertEquals(request.payload.getString("id"), image.id)
            assertEquals(if (request.payload.has("data")) request.payload.getString("data") else null, image.data)
        }
    }

    @Test
    fun malformedImagePayloadsAreRejected() {
        val id = "0b6a9c3e-7d1f-4e2a-9c58-3f0e2d1b4a77"
        fun put(payload: JSONObject) = BridgeMessages.image(BridgeProtocol.NATIVE_IMAGE_PUT, payload)
        fun get(payload: JSONObject) = BridgeMessages.image(BridgeProtocol.NATIVE_IMAGE_GET, payload)
        assertEquals("A".repeat(120_000), put(JSONObject().put("id", id).put("data", "A".repeat(120_000))).data)
        assertEquals("/9j/4A==", put(JSONObject().put("id", id).put("data", "/9j/4A==")).data)
        assertEquals(id, get(JSONObject().put("id", id)).id)
        val rejected = listOf(
            { put(JSONObject().put("id", id.uppercase()).put("data", "/9j/")) },
            { put(JSONObject().put("id", "{$id}").put("data", "/9j/")) },
            { put(JSONObject().put("id", 7).put("data", "/9j/")) },
            { put(JSONObject().put("id", id)) },
            { put(JSONObject().put("id", id).put("data", "/9j/").put("extra", true)) },
            { put(JSONObject().put("id", id).put("data", "_9j-")) },
            { put(JSONObject().put("id", id).put("data", "/9j/\n4AAQ")) },
            { put(JSONObject().put("id", id).put("data", "==")) },
            { put(JSONObject().put("id", id).put("data", "")) },
            { put(JSONObject().put("id", id).put("data", 1234)) },
            { put(JSONObject().put("id", id).put("data", "A".repeat(120_004))) },
            { get(JSONObject().put("id", id).put("data", "/9j/")) },
            { get(JSONObject()) },
            { BridgeMessages.image(BridgeProtocol.NATIVE_DRAFT_GET, JSONObject().put("id", id)) },
        )
        for ((index, call) in rejected.withIndex())
            assertThrows("case $index", IllegalArgumentException::class.java) { call() }
    }

    @Test
    fun navigationUsesOnlyContractScreens() {
        for (screen in BridgeProtocol.NAVIGATION_SCREENS) assertEquals(screen, BridgeMessages.navigation(screen, "").getString("screen"))
        assertThrows(IllegalArgumentException::class.java) { BridgeMessages.navigation("new", "") }
        assertThrows(IllegalArgumentException::class.java) { BridgeMessages.navigation("conversation", "x".repeat(129)) }
    }
}
