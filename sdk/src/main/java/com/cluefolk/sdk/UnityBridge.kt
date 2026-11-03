package com.cluefolk.sdk

import android.app.Activity
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.UUID

/**
 * Unity uses the same Android SDK; this adapter has no WebView implementation. An identity
 * provider is registered only after C# SetIdentityTokenProvider, so games without one start
 * anonymously; both provider requests and identityTokenRequired events are answered by C#.
 */
object UnityBridge {
    private const val IDENTITY_TIMEOUT_MS = 15_000L
    private data class IdentityRequest(val callback: Cluefolk.IdentityCallback, val timeout: Runnable)

    private val main = Handler(Looper.getMainLooper())
    private var receiver = "CluefolkDispatcher"
    private val identityRequests = HashMap<String, IdentityRequest>()

    private fun clearIdentityRequests(error: String) {
        val stale = identityRequests.values.toList()
        identityRequests.clear()
        stale.forEach {
            main.removeCallbacks(it.timeout)
            it.callback.complete(null, error)
        }
    }

    private fun player(): Class<*> = Class.forName("com.unity3d.player.UnityPlayer")

    private fun host(): Activity = player().getField("currentActivity").get(null) as Activity

    private fun send(
        type: String,
        requestId: String = "",
        error: String? = null,
        payload: String = "{}",
    ) {
        val message =
            JSONObject()
                .put("type", type)
                .put("requestId", requestId)
                .put("error", error ?: "")
                .put("payload", payload)
        player()
            .getMethod(
                "UnitySendMessage",
                String::class.java,
                String::class.java,
                String::class.java,
            )
            .invoke(null, receiver, "OnNativeMessage", message.toString())
    }

    @JvmStatic
    fun initialize(
        project: String,
        api: String,
        logo: String,
        gameObject: String,
    ) {
        main.post {
            clearIdentityRequests("ACCOUNT_CHANGED")
            receiver = gameObject
            try {
                Cluefolk.setEventListener(object : Cluefolk.EventListener {
                    override fun onCluefolkReady() { send("ready") }
                    override fun onCluefolkOpened() { send("opened") }
                    override fun onCluefolkClosed() { send("closed") }
                    override fun onCluefolkUnreadChanged(count: Int) {
                        send("unreadChanged", payload = JSONObject().put("count", count).toString())
                    }
                    override fun onCluefolkIdentityTokenRequired(requestId: String) {
                        send("identityTokenRequired", requestId)
                    }
                    override fun onCluefolkConversationCreated(conversationId: String) {
                        send("conversationCreated", payload = JSONObject().put("conversationId", conversationId).toString())
                    }
                    override fun onCluefolkConversationStatusChanged(conversationId: String, status: String) {
                        send("conversationStatusChanged", payload = JSONObject().put("conversationId", conversationId).put("status", status).toString())
                    }
                    override fun onCluefolkMessageSent(conversationId: String, messageId: String) {
                        send("messageSent", payload = JSONObject().put("conversationId", conversationId).put("messageId", messageId).toString())
                    }
                    override fun onCluefolkError(code: String) {
                        send("error", payload = JSONObject().put("code", code).toString())
                    }
                })
                Cluefolk.initialize(host(), Cluefolk.Options(project, api, logo.ifEmpty { null }))
            } catch (_: Exception) {
                send("error", error = "INITIALIZATION_FAILED")
            }
        }
    }

    @JvmStatic
    fun login(token: String, requestId: String) {
        main.post {
            clearIdentityRequests("ACCOUNT_CHANGED")
            Cluefolk.loginWithIdentity(token) { error -> send("completion", requestId, error) }
        }
    }

    @JvmStatic
    fun setIdentityProvider(enabled: Boolean) {
        main.post {
            clearIdentityRequests("IDENTITY_PROVIDER_CHANGED")
            Cluefolk.setIdentityTokenProvider(
                if (!enabled) null
                else Cluefolk.IdentityTokenProvider { callback ->
                    val requestId = UUID.randomUUID().toString()
                    val timeout = Runnable {
                        val expired = identityRequests.remove(requestId) ?: return@Runnable
                        expired.callback.complete(null, "IDENTITY_PROVIDER_TIMEOUT")
                    }
                    identityRequests[requestId] = IdentityRequest(callback, timeout)
                    main.postDelayed(timeout, IDENTITY_TIMEOUT_MS)
                    send("identityTokenRequired", requestId)
                },
            )
        }
    }

    @JvmStatic
    fun completeIdentity(requestId: String, token: String, error: String) {
        main.post {
            val request = identityRequests.remove(requestId)
            if (request != null) {
                main.removeCallbacks(request.timeout)
                request.callback.complete(token.ifEmpty { null }, error.ifEmpty { null })
            }
            else Cluefolk.completeIdentityToken(requestId, token, error)
        }
    }

    @JvmStatic
    fun open() {
        main.post { Cluefolk.open(host()) }
    }

    @JvmStatic
    fun language(value: String) { Cluefolk.setLanguage(value) }

    /** An invalid ID is reported by the SDK as error INVALID_ARGUMENT. */
    @JvmStatic
    fun playerId(value: String) { Cluefolk.setPlayerId(value) }

    @JvmStatic
    fun openConversation(id: String) { main.post { Cluefolk.openConversation(host(), id) } }

    @JvmStatic
    fun handlePush(json: String, opened: Boolean): Boolean {
        val data = JSONObject(json)
        val values = data.keys().asSequence().associateWith { data.getString(it) }
        if (values["origin"] != "cluefolk") return false
        if (opened) main.post { Cluefolk.openFromPush(host(), values) }
        else Cluefolk.handlePush(values)
        return true
    }

    @JvmStatic
    fun close() {
        main.post { Cluefolk.close() }
    }

    @JvmStatic
    fun logout() {
        main.post {
            clearIdentityRequests("ACCOUNT_CHANGED")
            Cluefolk.logout()
        }
    }

    /**
     * C# SetAppearance: [base] is the theme file ("" for the default theme) and [overrides] the
     * changes its Appearance.Builder recorded; both empty is SetAppearance(null). A theme that
     * fails is reported as error INVALID_APPEARANCE and the previous one stays.
     */
    @JvmStatic
    fun appearance(base: String, overrides: String) {
        main.post {
            val value = if (base.isEmpty() && overrides.isEmpty()) null else try {
                Appearance.Builder(base.ifEmpty { null }).overrides(overrides).build()
            } catch (_: Exception) {
                Cluefolk.notifyError("INVALID_APPEARANCE")
                return@post
            }
            Cluefolk.setAppearance(value)
        }
    }

    @JvmStatic
    fun context(json: String) {
        main.post {
            try {
                Cluefolk.setConversationContext(json)
            } catch (_: Exception) {
                send("error", error = "INVALID_CONTEXT")
            }
        }
    }

    @JvmStatic
    fun attributes(json: String) {
        main.post {
            try {
                Cluefolk.setAttributes(json)
            } catch (_: Exception) {
                send("error", error = "INVALID_ATTRIBUTES")
            }
        }
    }

    @JvmStatic
    fun unset(keys: Array<String>) {
        main.post {
            try {
                Cluefolk.unsetAttributes(keys)
            } catch (_: Exception) {
                send("error", error = "INVALID_ATTRIBUTES")
            }
        }
    }

    @JvmStatic
    fun unread(requestId: String) {
        main.post {
            Cluefolk.getUnreadCount { count, error ->
                send("completion", requestId, error, JSONObject().put("count", count).toString())
            }
        }
    }
}
