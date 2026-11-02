package com.cluefolk.sdk

import org.json.JSONObject

/** Pure bridge v2 envelopes and payloads; see bridge.schema.json in the test resources. */
internal object BridgeMessages {
    class Request(val id: String, val type: String, val payload: JSONObject)

    /** native.imagePut carries [data] (base64 of a JPEG preview); native.imageGet does not. */
    class ImageRequest(val id: String, val data: String?)

    const val IMAGE_DATA_MAX_LENGTH = 120_000
    val IMAGE_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    val BASE64 = Regex("[A-Za-z0-9+/]+=*")

    private val envelopeKeys = setOf("protocol", "version", "requestId", "type", "payload")
    private val cursor = Regex("[0-9]{1,20}:[0-9]{1,19}")

    fun parse(raw: String): Request {
        val envelope = JSONObject(raw)
        require(envelope.keys().asSequence().toSet() == envelopeKeys &&
            envelope.getString("protocol") == BridgeProtocol.PROTOCOL && envelope.get("version") == BridgeProtocol.VERSION)
        val id = envelope.getString("requestId")
        require(id.length in 1..128)
        return Request(id, envelope.getString("type"), envelope.getJSONObject("payload"))
    }

    fun reply(requestId: String, type: String, payload: JSONObject): String =
        JSONObject().put("protocol", BridgeProtocol.PROTOCOL).put("version", BridgeProtocol.VERSION)
            .put("requestId", requestId).put("type", type).put("payload", payload).toString()

    fun image(type: String, payload: JSONObject): ImageRequest {
        val put = type == BridgeProtocol.NATIVE_IMAGE_PUT
        require(put || type == BridgeProtocol.NATIVE_IMAGE_GET)
        require(payload.keys().asSequence().toSet() == if (put) setOf("id", "data") else setOf("id"))
        val id = payload.get("id") as? String
        require(id != null && IMAGE_ID.matches(id))
        if (!put) return ImageRequest(id, null)
        val data = payload.get("data") as? String
        require(data != null && data.length <= IMAGE_DATA_MAX_LENGTH && BASE64.matches(data))
        return ImageRequest(id, data)
    }

    fun navigation(screen: String, id: String): JSONObject {
        require(screen in BridgeProtocol.NAVIGATION_SCREENS && id.length <= 128)
        return JSONObject().put("screen", screen).put("id", id)
    }

    fun eventCursor(value: Any?): String? = (value as? String)?.takeIf { cursor.matches(it) }

    fun bootstrap(
        code: String,
        apiBaseUrl: String,
        context: JSONObject,
        appearance: JSONObject?,
        logo: String?,
        language: String,
        navigation: JSONObject,
        unreadCount: Int?,
        eventCursor: String?,
        historySyncId: String,
        historyPageCount: Int,
        session: Session,
    ): JSONObject {
        require(code.isNotEmpty())
        val payload = JSONObject().put("code", code).put("apiBaseUrl", apiBaseUrl).put("context", context)
            .put("appearance", appearance ?: JSONObject.NULL).put("logo", logo ?: JSONObject.NULL).put("language", language).put("navigation", navigation)
            .put("unreadCount", unreadCount ?: JSONObject.NULL).put("historySyncId", historySyncId)
            .put("historyPageCount", historyPageCount).put("player", session.player())
        if (eventCursor != null) payload.put("eventCursor", eventCursor)
        return payload
    }
}
