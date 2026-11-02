// Generated from bridge.schema.json, the Cluefolk bridge contract (sha256 590b0e97b6a5fad00d8d5730b414546200e1fcce768f7adeb4ad3ef7ed901820). Do not edit.
package com.cluefolk.sdk

internal object BridgeProtocol {
    const val PROTOCOL = "cluefolk"
    const val VERSION = 2
    const val SCHEMA_SHA256 = "590b0e97b6a5fad00d8d5730b414546200e1fcce768f7adeb4ad3ef7ed901820"
    const val NATIVE_READY = "native.ready"
    const val NATIVE_AUTH_REQUIRED = "native.authRequired"
    const val NATIVE_CLOSE = "native.close"
    const val NATIVE_UNREAD_COUNT_CHANGED = "native.unreadCountChanged"
    const val NATIVE_HISTORY_PAGE = "native.historyPage"
    const val NATIVE_CONVERSATION_CREATED = "native.conversationCreated"
    const val NATIVE_CONVERSATION_STATUS_CHANGED = "native.conversationStatusChanged"
    const val NATIVE_MESSAGE_SENT = "native.messageSent"
    const val NATIVE_DRAFT_GET = "native.draftGet"
    const val NATIVE_DRAFT_PUT = "native.draftPut"
    const val NATIVE_IMAGE_PUT = "native.imagePut"
    const val NATIVE_IMAGE_GET = "native.imageGet"
    const val SDK_BOOTSTRAP = "sdk.bootstrap"
    const val SDK_ACK = "sdk.ack"
    const val SDK_ERROR = "sdk.error"
    val NATIVE_REQUESTS = setOf("native.ready", "native.authRequired", "native.close", "native.unreadCountChanged", "native.historyPage", "native.conversationCreated", "native.conversationStatusChanged", "native.messageSent", "native.draftGet", "native.draftPut", "native.imagePut", "native.imageGet")
    val NAVIGATION_SCREENS = setOf("home", "conversation")
}
