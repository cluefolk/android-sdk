package com.cluefolk.sdk

import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import java.util.Locale
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Initialize starts on main; storage loads in the background. Callbacks run on main. */
object Cluefolk {
    const val DEFAULT_API_URL = "https://cluefolk.com"
    /** The SDK version, as published to Maven Central. */
    const val VERSION = BuildConfig.SDK_VERSION

    /**
     * Only the project ID is required. The widget is built into the SDK and the
     * logo is the app's own icon unless [logoResource] names a drawable or mipmap.
     */
    data class Options @JvmOverloads constructor(
        val projectId: String,
        val apiUrl: String = DEFAULT_API_URL,
        val logoResource: String? = null,
    )

    fun interface Callback {
        fun complete(error: String?)
    }

    fun interface IdentityCallback {
        fun complete(token: String?, error: String?)
    }

    fun interface IdentityTokenProvider {
        fun getToken(callback: IdentityCallback)
    }

    interface EventListener {
        fun onCluefolkReady()
        fun onCluefolkOpened()
        fun onCluefolkClosed()
        fun onCluefolkUnreadChanged(count: Int)
        fun onCluefolkIdentityTokenRequired(requestId: String)
        fun onCluefolkConversationCreated(conversationId: String)
        fun onCluefolkConversationStatusChanged(conversationId: String, status: String)
        fun onCluefolkMessageSent(conversationId: String, messageId: String)
        fun onCluefolkError(code: String)
    }

    fun interface UnreadCallback {
        fun complete(count: Int, error: String?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor()
    private val storageIO = Executors.newSingleThreadExecutor()
    private lateinit var context: Application
    internal var options: Options? = null
        private set

    private var session: Session? = null
    private var lastVerifiedExternalId: String? = null
    private var installSecret: String? = null
    private var sliding = false
    @Volatile private var ready = false
    private var generation = UUID.randomUUID().toString()
    @Volatile private var lifetime = UUID.randomUUID().toString()
    private var provider: IdentityTokenProvider? = null
    private var refreshWaiters: MutableList<(String?) -> Unit>? = null
    private var pendingIdentity: Pair<String, IdentityCallback>? = null
    private var activityReference: WeakReference<SupportActivity>? = null
    internal var activity: SupportActivity?
        get() = activityReference?.get()
        set(value) {
            activityReference = value?.let { WeakReference(it) }
        }

    private var supportRequestId: String? = null
    private var supportViewReference: WeakReference<SupportView>? = null
    private var supportView: SupportView?
        get() = supportViewReference?.get()
        set(value) { supportViewReference = value?.let { WeakReference(it) } }
    private var supportOpened = false
    private var navigation = BridgeMessages.navigation("home", "")
    // The game's own player ID for anonymous players: null until the game sets one, "" once cleared.
    private var gamePlayerId: String? = null
    private var gamePlayerIdSending = false
    private var language = Locale.getDefault().toLanguageTag()
    private var metadata = JSONObject()
    private var foregroundActivities = 0
    private var unreadRefreshing = false
    private var unreadCount: Int? = null
    private var unreadObservedAt: Long? = null
    private var historyPages = emptyList<JSONObject>()
    private var historySyncId = UUID.randomUUID().toString()
    private var eventCursor: String? = null
    private var pendingPush: Pair<WeakReference<Activity>, JSONObject>? = null
    private val unreadPoll = object : Runnable {
        override fun run() {
            if (!ready || foregroundActivities == 0) return
            val observed = unreadObservedAt
            if (observed == null || android.os.SystemClock.elapsedRealtime() - observed >= 30_000)
                refreshUnreadCount()
            main.postDelayed(this, 30_000)
        }
    }
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            foregroundActivities++
            if (foregroundActivities == 1) {
                main.removeCallbacks(unreadPoll)
                main.post(unreadPoll)
            }
        }
        override fun onActivityStopped(activity: Activity) {
            foregroundActivities = (foregroundActivities - 1).coerceAtLeast(0)
            if (foregroundActivities == 0) main.removeCallbacks(unreadPoll)
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    // The theme from setAppearance; null is the default. Its rotation decides how support turns.
    private var theme: Appearance? = null
    private var logo: String? = null
    private var conversationContext = JSONObject()
    private var listener: EventListener? = null
    private var attributeValues = JSONObject()
    private val attributeUnset = mutableSetOf<String>()
    private var attributeSending = false
    private var attributeBatch: Pair<String, JSONObject>? = null
    private var attributeRevision = 0L
    private var attributeScheduled = false

    @JvmStatic
    fun initialize(app: Context, config: Options) {
        checkMain()
        check(options == null) { "Cluefolk is already initialized" }
        require(trusted(config.apiUrl) && config.projectId.isNotBlank()) { "An HTTPS API origin is required" }
        val application = app.applicationContext as Application
        context = application
        options = config
        val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
        metadata = JSONObject()
            .put("platform", "android").put("sdkVersion", VERSION).put("language", language)
            .put("osVersion", Build.VERSION.RELEASE.take(128))
            .put("deviceModel", (Build.MANUFACTURER + " " + Build.MODEL).take(256))
            .put("appVersion", packageInfo.versionName.orEmpty().take(128))
            .put("appBuild", if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode.toString() else JSONObject.NULL)
        if (app is Activity && app.window?.decorView?.isAttachedToWindow == true) foregroundActivities = 1
        application.registerActivityLifecycleCallbacks(lifecycle)
        val expected = lifetime
        network.execute {
            val stored = unseal(application, "cluefolk", "session", config)
                ?.let { runCatching { Session.parse(it, it.getLong("expires")) }.getOrNull() }
                ?.takeIf { it.expires > System.currentTimeMillis() }
            val verified = unseal(application, "cluefolk", "verified", config)?.optString("externalUserId")
            val secret = loadInstallSecret(application, config)
            val icon = gameIcon(application, config.logoResource)
            main.post {
                if (lifetime != expected) return@post
                logo = icon
                session = stored
                lastVerifiedExternalId = verified?.ifEmpty { null }
                installSecret = secret
                ready = true
                listener?.onCluefolkReady()
                main.post(unreadPoll)
                consumePendingPush()
                syncGamePlayerId()
            }
        }
    }

    @JvmStatic
    fun isReady(): Boolean = ready

    /** Optional: without a provider support starts anonymously; null removes it. */
    @JvmStatic
    fun setIdentityTokenProvider(value: IdentityTokenProvider?) {
        onMain {
            provider = value
        }
    }

    @JvmStatic
    fun setEventListener(value: EventListener?) {
        onMain {
            listener = value
            if (ready) value?.onCluefolkReady()
        }
    }

    /** Releases callbacks and transient state while preserving the stored session. */
    @JvmStatic
    fun shutdown() {
        onMain {
            lifetime = UUID.randomUUID().toString()
            if (options != null) context.unregisterActivityLifecycleCallbacks(lifecycle)
            foregroundActivities = 0
            main.removeCallbacks(unreadPoll)
            pendingPush = null
            ready = false
            listener = null
            provider = null
            reset(clearCredentials = false)
            installSecret = null
            theme = null
            logo = null
            options = null
        }
    }

    /** Answers the identityTokenRequired SDK event; stale request IDs are ignored. */
    @JvmStatic
    fun completeIdentityToken(requestId: String, token: String, error: String) {
        onMain {
            val pending = pendingIdentity ?: return@onMain
            if (pending.first != requestId) return@onMain
            pendingIdentity = null
            pending.second.complete(token.ifEmpty { null }, error.ifEmpty { null })
        }
    }

    @JvmStatic
    fun loginWithIdentity(token: String, callback: Callback) {
        onMain {
            if (!ready) {
                callback.complete("NOT_READY")
                return@onMain
            }
            val currentLifetime = lifetime
            val previous = session
            reset()
            val expected = generation
            if (previous != null) request("/sessions/logout", token = previous.token) { _, _ -> }
            createSession(token, null, expected) { error ->
                if (lifetime == currentLifetime) callback.complete(error)
            }
        }
    }

    /**
     * POST /sessions with [identityToken], otherwise /sessions/anonymous; both carry the install secret.
     * An anonymous session claims the game's player ID, else [claimed] (the last verified external ID).
     */
    private fun createSession(identityToken: String?, claimed: String?, expected: String, callback: (String?) -> Unit) {
        val config = options ?: return callback("NOT_INITIALIZED")
        val secret = installSecret ?: return callback("NOT_READY")
        val body = JSONObject().put("projectId", config.projectId).put("installSecret", secret)
        if (identityToken != null) body.put("identityToken", identityToken)
        else (gamePlayerId ?: claimed)?.takeIf { it.isNotEmpty() }?.let { body.put("claimedExternalId", it) }
        val currentLifetime = lifetime
        request(if (identityToken != null) "/sessions" else "/sessions/anonymous", body = body) { result, error ->
            if (lifetime != currentLifetime) return@request
            if (generation != expected) {
                result?.optString("sessionToken")?.takeIf { error == null && it.isNotEmpty() }?.let {
                    request("/sessions/logout", token = it) { _, _ -> }
                }
                callback("ACCOUNT_CHANGED")
                return@request
            }
            if (error == "HTTP_403" && errorCode(result) == "IDENTITY_REQUIRED") {
                callback("IDENTITY_REQUIRED")
                return@request
            }
            if (error != null || result == null) {
                callback(error ?: "INVALID_RESPONSE")
                return@request
            }
            val stored: Session
            val unread: Int
            try {
                stored = Session.parse(result, Timestamps.parseRfc3339(result.getString("sessionExpiresAt")))
                unread = result.getInt("unreadCount")
            } catch (_: Exception) {
                callback("INVALID_RESPONSE")
                return@request
            }
            try {
                persist(stored)
                if (stored.kind == "verified") seal(context, "cluefolk", "verified", config,
                    JSONObject().put("externalUserId", stored.externalUserId))
            } catch (_: Exception) {
                callback("SESSION_STORAGE_FAILED")
                return@request
            }
            session = stored
            if (stored.kind == "verified") lastVerifiedExternalId = stored.externalUserId
            scheduleAttributes()
            syncGamePlayerId()
            historyPages = emptyList()
            historySyncId = UUID.randomUUID().toString()
            notifyUnreadChanged(unread)
            if (lifetime != currentLifetime) return@request
            if (generation != expected) { callback("ACCOUNT_CHANGED"); return@request }
            eventCursor = BridgeMessages.eventCursor(result.opt("eventCursor"))
            loadInitialHistory(result.optJSONObject("history"), stored.token, expected) { pages, failure ->
                if (lifetime != currentLifetime) return@loadInitialHistory
                if (generation != expected) {
                    callback("ACCOUNT_CHANGED")
                    return@loadInitialHistory
                }
                if (failure != null) {
                    callback(failure)
                    return@loadInitialHistory
                }
                historyPages = pages
                historySyncId = UUID.randomUUID().toString()
                consumePendingPush()
                callback(null)
            }
        }
    }

    private fun loadInitialHistory(
        first: JSONObject?, token: String, expected: String,
        completion: (List<JSONObject>, String?) -> Unit,
    ) {
        if (first == null) { completion(emptyList(), null); return }
        val pages = mutableListOf<JSONObject>()
        val cursors = mutableSetOf<String>()
        fun append(page: JSONObject) {
            if (generation != expected) { completion(emptyList(), "ACCOUNT_CHANGED"); return }
            val next: String
            try {
                page.getJSONArray("conversations")
                page.getJSONArray("messages")
                next = page.getString("nextCursor")
                require(next.length <= 1024 && (next.isEmpty() || cursors.add(next)))
            } catch (_: Exception) {
                completion(emptyList(), "INVALID_HISTORY_RESPONSE")
                return
            }
            pages.add(page)
            if (next.isEmpty()) { completion(pages, null); return }
            request("/history?cursor=" + Uri.encode(next), "GET", token) { result, error ->
                if (error != null || result == null) completion(emptyList(), error ?: "INVALID_HISTORY_RESPONSE")
                else append(result)
            }
        }
        append(first)
    }

    internal fun historyPage(syncId: String, index: Int): JSONObject {
        require(syncId == historySyncId && index in historyPages.indices)
        return historyPages[index]
    }

    private fun ensureSession(callback: (String?) -> Unit) {
        val current = session
        val now = System.currentTimeMillis()
        if (current != null && current.expires > now + 30_000) {
            if (current.expires < now + 7L * 86_400_000) slide(current)
            callback(null)
            return
        }
        refreshWaiters?.let {
            it.add(callback)
            return
        }
        val expected = generation
        refreshWaiters = mutableListOf(callback)
        fun finish(error: String?) {
            if (generation != expected) return
            val waiters = refreshWaiters
            refreshWaiters = null
            pendingIdentity = null
            waiters?.forEach { it(error) }
        }
        val claimed = lastVerifiedExternalId
        val source = provider
        if (source == null && (claimed == null || listener == null)) {
            createSession(null, claimed, expected) { finish(it) }
            return
        }
        // Support stays available while the game backend cannot issue identity tokens.
        var completed = false
        fun anonymous() {
            notifyError("IDENTITY_UNAVAILABLE")
            if (generation == expected) createSession(null, claimed, expected) { finish(it) }
        }
        val timeout = Runnable {
            if (!completed && generation == expected) {
                completed = true
                pendingIdentity = null
                anonymous()
            }
        }
        main.postDelayed(timeout, 15_000)
        val completion = IdentityCallback { token, error ->
            main.post {
                if (completed || generation != expected) return@post
                completed = true
                main.removeCallbacks(timeout)
                pendingIdentity = null
                if (error != null || token.isNullOrBlank()) anonymous()
                else createSession(token, null, expected) { failure ->
                    if (failure == null || failure == "ACCOUNT_CHANGED") finish(failure) else anonymous()
                }
            }
        }
        if (source != null) {
            source.getToken(completion)
        } else {
            val requestId = UUID.randomUUID().toString()
            pendingIdentity = Pair(requestId, completion)
            listener?.onCluefolkIdentityTokenRequired(requestId)
        }
    }

    /** Native sessions slide to 30 days; the refresh runs in the background during the last week. */
    private fun slide(current: Session) {
        if (sliding) return
        sliding = true
        val expected = generation
        request("/sessions/refresh", token = current.token) { result, error ->
            if (generation != expected) return@request
            sliding = false
            if (session?.token != current.token) return@request
            if (error == "HTTP_401") {
                dropSession()
                return@request
            }
            if (error != null) return@request
            val expires = runCatching { Timestamps.parseRfc3339(result?.getString("sessionExpiresAt").orEmpty()) }
                .getOrNull() ?: return@request
            val updated = current.copy(expires = expires)
            runCatching { persist(updated) }
            session = updated
        }
    }

    private fun dropSession() {
        session = null
        context.getSharedPreferences("cluefolk", Context.MODE_PRIVATE).edit { remove("session") }
    }

    @JvmStatic
    fun open(host: Activity) = openTo(host, "home", "")

    /** An empty ID opens the player's current chat. */
    @JvmStatic
    fun openConversation(host: Activity, conversationId: String) = openTo(host, "conversation", conversationId)

    private fun openTo(host: Activity, screen: String, id: String) {
        val target = BridgeMessages.navigation(screen, id)
        onMain {
            if (!requireReady()) return@onMain
            navigation = target
            if (supportRequestId != null) {
                supportView?.navigate(navigation)
                return@onMain
            }
            val requestId = UUID.randomUUID().toString()
            supportRequestId = requestId
            val expected = generation
            val presenter = WeakReference(host)
            ensureSession { error ->
                if (generation != expected || supportRequestId != requestId) return@ensureSession
                if (error != null) {
                    supportRequestId = null
                    notifyError(error)
                    return@ensureSession
                }
                val current = presenter.get()
                if (current == null || current.isFinishing || current.isDestroyed) {
                    supportRequestId = null
                    notifyError("NO_ACTIVITY")
                    return@ensureSession
                }
                // Retries a player ID that an earlier attempt could not deliver.
                syncGamePlayerId()
                try {
                    // Support turns the way the game does (landscape, portrait or both)
                    // unless the theme sets free rotation.
                    val freeRotation = theme?.rotation == Appearance.Rotation.FREE
                    current.startActivity(Intent(current, SupportActivity::class.java)
                        .putExtra("cluefolkRequestId", requestId)
                        .putExtra("cluefolkOrientation", SupportSettings.orientation(freeRotation, current.requestedOrientation)))
                } catch (_: RuntimeException) {
                    supportRequestId = null
                    notifyError("PRESENTATION_FAILED")
                }
            }
        }
    }

    internal fun isCurrentSupport(requestId: String): Boolean =
        ready && supportRequestId == requestId

    internal fun attachSupport(host: SupportActivity, requestId: String, retained: SupportView?): SupportView? {
        if (!isCurrentSupport(requestId)) return null
        val config = options ?: return null
        val view = retained ?: supportView ?: SupportView(context, config.apiUrl, requestId)
        supportView = view
        activity = host
        view.attach(host)
        return view
    }

    internal fun supportShown(requestId: String) {
        if (!isCurrentSupport(requestId) || supportOpened) return
        supportOpened = true
        listener?.onCluefolkOpened()
    }

    internal fun detachSupport(host: SupportActivity, requestId: String, changingConfiguration: Boolean) {
        if (activity !== host || supportRequestId != requestId) return
        if (changingConfiguration) {
            supportView?.detach()
            activity = null
            return
        }
        close()
    }

    @JvmStatic
    fun close() {
        onMain {
            pendingPush = null
            closeSupport()
        }
    }

    private fun closeSupport() {
        supportRequestId = null
        val view = supportView
        supportView = null
        val host = activity
        activity = null
        val opened = supportOpened
        supportOpened = false
        view?.destroy()
        host?.finish()
        if (opened) listener?.onCluefolkClosed()
    }

    @JvmStatic
    fun setLanguage(value: String) {
        require(value.length in 2..64 && value.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*")))
        onMain {
            language = value
            metadata.put("language", value)
            supportView?.setLanguage(value)
        }
    }

    /**
     * The game's own player ID (for example its user ID). Staff see it on an anonymous
     * player's conversations marked unverified; verified players are unaffected. Trimmed;
     * null or blank clears it. More than 256 characters, CR, LF or NUL is ignored and
     * reported as INVALID_ARGUMENT. Kept in memory until logout(); may be set before ready.
     */
    @JvmStatic
    fun setPlayerId(playerId: String?) {
        val value = SupportSettings.playerId(playerId)
        onMain {
            if (value == null) {
                notifyError("INVALID_ARGUMENT")
                return@onMain
            }
            gamePlayerId = value
            syncGamePlayerId()
        }
    }

    // An existing anonymous session gets the game's player ID by PATCH /players/me; a new one
    // claims it in createSession. A session is never created only to send it.
    private fun syncGamePlayerId() {
        val value = gamePlayerId ?: return
        val current = session ?: return
        if (!ready || gamePlayerIdSending || current.kind != "anonymous" || current.claimedExternalId == value) return
        val expected = generation
        gamePlayerIdSending = true
        request("/players/me", "PATCH", current.token, JSONObject().put("claimedExternalId", value)) { result, error ->
            if (generation != expected) return@request
            gamePlayerIdSending = false
            if (error == null) {
                val stored = session
                if (stored != null && stored.token == current.token) {
                    val updated = stored.copy(claimedExternalId = result?.optString("claimedExternalId", value) ?: value)
                    runCatching { persist(updated) }
                    session = updated
                }
                // Only a newer value or another session needs a further request.
                if (gamePlayerId != value || session?.token != current.token) syncGamePlayerId()
                return@request
            }
            when {
                error == "HTTP_409" && errorCode(result) == "PLAYER_VERIFIED" -> {}
                // The next session claims the ID when it is created.
                error == "HTTP_401" -> if (session?.token == current.token) dropSession()
                else -> notifyError(error)
            }
        }
    }

    @JvmStatic
    fun refreshUnreadCount() {
        onMain {
            if (!ready || session == null || unreadRefreshing) return@onMain
            unreadRefreshing = true
            val expected = generation
            getUnreadCount { count, error ->
                if (generation != expected) return@getUnreadCount
                unreadRefreshing = false
                if (error == null) notifyUnreadChanged(count)
            }
        }
    }

    @JvmStatic
    fun handlePush(data: Map<String, String>): Boolean {
        if (data["origin"] != "cluefolk") return false
        onMain { if (matchesPush(JSONObject(data))) refreshUnreadCount() }
        return true
    }

    @JvmStatic
    fun openFromPush(host: Activity, data: Map<String, String>): Boolean {
        if (data["origin"] != "cluefolk") return false
        val payload = JSONObject(data)
        onMain {
            pendingPush = Pair(WeakReference(host), payload)
            consumePendingPush()
        }
        return true
    }

    private fun matchesPush(data: JSONObject): Boolean {
        val config = options ?: return false
        val player = session?.playerId ?: return false
        return player.isNotEmpty() && data.optString("playerId") == player &&
            data.optString("projectId") == config.projectId
    }

    private fun consumePendingPush() {
        if (!ready || session?.playerId.isNullOrEmpty()) return
        val pending = pendingPush ?: return
        pendingPush = null
        if (!matchesPush(pending.second)) return
        val host = pending.first.get() ?: return
        val id = pending.second.optString("conversationId")
        if (id.length !in 1..128) return
        openConversation(host, id)
    }

    @JvmStatic
    fun logout() {
        onMain {
            if (!requireReady()) return@onMain
            val previous = session
            val app = context
            storageIO.execute {
                runCatching { SecureDrafts(app).clear() }
                runCatching { SecureImages.open(app).clear() }
            }
            pendingPush = null
            gamePlayerId = null
            reset()
            if (previous != null) request("/sessions/logout", token = previous.token) { _, _ -> }
        }
    }

    /**
     * The widget theme built with [Appearance.Builder], usually from the file shipped with the
     * build; it also decides how the support screen turns. Applies on the next open; null
     * restores the default theme.
     */
    @JvmStatic
    fun setAppearance(appearance: Appearance?) {
        onMain {
            if (!requireReady()) return@onMain
            theme = appearance
        }
    }

    @JvmStatic
    fun setConversationContext(values: String) {
        require(values.toByteArray(Charsets.UTF_8).size <= 16_384)
        val context = JSONObject(values)
        onMain {
            if (!requireReady()) return@onMain
            val merged = JSONObject(context.toString()).put("_cluefolk", metadata)
            if (merged.toString().toByteArray(Charsets.UTF_8).size > 16_384) {
                notifyError("INVALID_CONTEXT")
                return@onMain
            }
            conversationContext = context
        }
    }

    @JvmStatic
    fun setAttributes(values: String) {
        val incoming = JSONObject(values)
        require(incoming.length() <= 64 && incoming.toString().toByteArray(Charsets.UTF_8).size <= 15_000)
        onMain {
            if (!requireReady()) return@onMain
            val merged = JSONObject(attributeValues.toString())
            incoming.keys().forEach { merged.put(it, incoming.get(it)) }
            if (merged.length() > 64 || merged.toString().toByteArray(Charsets.UTF_8).size > 15_000) {
                notifyError("INVALID_ATTRIBUTES")
                return@onMain
            }
            attributeValues = merged
            incoming.keys().forEach { attributeUnset.remove(it) }
            scheduleAttributes()
        }
    }

    @JvmStatic
    fun unsetAttributes(keys: Array<String>) {
        val requested = keys.toSet()
        require(requested.size <= 64)
        onMain {
            if (!requireReady()) return@onMain
            if ((attributeUnset + requested + attributeValues.keys().asSequence().toSet()).size > 64) {
                notifyError("INVALID_ATTRIBUTES")
                return@onMain
            }
            requested.forEach {
                attributeValues.remove(it)
                attributeUnset.add(it)
            }
            scheduleAttributes()
        }
    }

    private fun scheduleAttributes(delay: Long = 300L) {
        if (
            session == null ||
                attributeScheduled ||
                attributeSending ||
                (attributeBatch == null &&
                    attributeValues.length() == 0 &&
                    attributeUnset.isEmpty())
        )
            return
        val expected = generation
        attributeScheduled = true
        main.postDelayed(
            {
                if (generation != expected) return@postDelayed
                attributeScheduled = false
                val credential = session ?: return@postDelayed
                if (credential.expires <= System.currentTimeMillis() + 30_000) {
                    attributeSending = true
                    ensureSession { error ->
                        if (generation == expected) {
                            attributeSending = false
                            if (error == null) scheduleAttributes()
                            else notifyError(error)
                        }
                    }
                    return@postDelayed
                }
                if (attributeBatch == null) {
                    attributeRevision = maxOf(attributeRevision + 1, System.currentTimeMillis())
                    val format =
                        java.text
                            .SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    val body =
                        JSONObject()
                            .put("observedAt", format.format(java.util.Date()))
                            .put("revision", attributeRevision)
                            .put("batchId", UUID.randomUUID().toString())
                    if (attributeValues.length() > 0) {
                        body.put("attributes", attributeValues)
                        attributeValues = JSONObject()
                        attributeBatch = Pair("/players/me/attributes", body)
                    } else {
                        body.put("keys", org.json.JSONArray(attributeUnset.toList()))
                        attributeUnset.clear()
                        attributeBatch = Pair("/players/me/attributes/unset", body)
                    }
                }
                val batch = attributeBatch ?: return@postDelayed
                attributeSending = true
                request(
                    batch.first,
                    if (batch.first.endsWith("/unset")) "POST" else "PATCH",
                    credential.token,
                    batch.second,
                ) { _, error ->
                    if (generation != expected) return@request
                    attributeSending = false
                    if (error == null) {
                        attributeBatch = null
                        scheduleAttributes()
                    } else {
                        notifyError(error)
                        if (error == "HTTP_401") {
                            dropSession()
                            attributeSending = true
                            ensureSession { failure ->
                                if (generation == expected) {
                                    attributeSending = false
                                    if (failure == null) scheduleAttributes()
                                    else notifyError(failure)
                                }
                            }
                            return@request
                        }
                        val status = error.removePrefix("HTTP_").toIntOrNull()
                        if (status != null && status in 400..499 && status != 429)
                            attributeBatch = null
                        scheduleAttributes(5_000)
                    }
                }
            },
            delay,
        )
    }

    @JvmStatic
    fun getUnreadCount(callback: UnreadCallback) {
        onMain {
            if (!ready) {
                callback.complete(0, "NOT_READY")
                return@onMain
            }
            val expected = generation
            val currentLifetime = lifetime
            val previousToken = session?.token
            ensureSession { error ->
                if (lifetime != currentLifetime) return@ensureSession
                if (error != null) {
                    callback.complete(0, error)
                    return@ensureSession
                }
                if (generation != expected) {
                    callback.complete(0, "ACCOUNT_CHANGED")
                    return@ensureSession
                }
                val known = unreadCount
                if (session?.token != previousToken && known != null) {
                    callback.complete(known, null)
                    return@ensureSession
                }
                request("/unread-count", "GET", session?.token) { result, failure ->
                    if (lifetime != currentLifetime) return@request
                    if (generation != expected) {
                        callback.complete(0, "ACCOUNT_CHANGED")
                        return@request
                    }
                    callback.complete(result?.optInt("count") ?: 0, failure)
                }
            }
        }
    }

    internal fun draft(id: String, content: JSONObject?, write: Boolean, callback: (JSONObject?, String?) -> Unit) {
        val namespace = accountNamespace() ?: return callback(null, "NOT_AUTHENTICATED")
        require(id == "new" || runCatching { UUID.fromString(id) }.isSuccess)
        val serialized = content?.toString()
        require(serialized == null || serialized.toByteArray(Charsets.UTF_8).size <= 102_400)
        accountStorage("DRAFT_STORAGE_UNAVAILABLE", callback) { app ->
            val store = SecureDrafts(app)
            if (write) { store.write(namespace, id, serialized); JSONObject() }
            else JSONObject().put("content", store.read(namespace, id)?.let(::JSONObject) ?: JSONObject.NULL)
        }
    }

    /** native.imagePut when [data] (base64 of a JPEG preview) is set, otherwise native.imageGet. */
    internal fun image(id: String, data: String?, callback: (JSONObject?, String?) -> Unit) {
        val namespace = accountNamespace() ?: return callback(null, "NOT_AUTHENTICATED")
        val jpeg = data?.let { Base64.decode(it, Base64.DEFAULT) }
        require(jpeg == null || SecureImages.isJpeg(jpeg))
        accountStorage("IMAGE_STORAGE_UNAVAILABLE", callback) { app ->
            val images = SecureImages.open(app)
            if (jpeg != null) { images.write(namespace, id, jpeg); JSONObject() }
            else JSONObject().put("data", images.read(namespace, id)?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: JSONObject.NULL)
        }
    }

    // Drafts and image copies are bound to the game account by this namespace.
    private fun accountNamespace(): String? {
        val config = options ?: return null
        val account = session ?: return null
        return if (ready) config.projectId + "/" + origin(config.apiUrl) + "/" + account.playerId else null
    }

    // Runs off the main thread; a result computed for a previous account is dropped.
    private fun accountStorage(failure: String, callback: (JSONObject?, String?) -> Unit, work: (Application) -> JSONObject) {
        val expected = generation
        val app = context
        storageIO.execute {
            val result = try { work(app) } catch (_: Exception) { null }
            main.post { if (generation == expected) callback(result, if (result == null) failure else null) }
        }
    }

    internal fun bootstrap(callback: (JSONObject?, String?) -> Unit) {
        if (!ready) {
            callback(null, "NOT_READY")
            return
        }
        val expected = generation
        ensureSession { error ->
            if (error != null) {
                callback(null, error)
                return@ensureSession
            }
            request("/sessions/bootstrap", token = session?.token) { result, failure ->
                if (generation != expected) {
                    callback(null, "ACCOUNT_CHANGED")
                    return@request
                }
                if (failure == "HTTP_401") {
                    dropSession()
                    ensureSession { refreshed ->
                        if (refreshed != null) callback(null, refreshed)
                        else
                            request("/sessions/bootstrap", token = session?.token) { next, nextError
                                ->
                                if (generation == expected)
                                    bootstrapResult(next, nextError, callback)
                                else callback(null, "ACCOUNT_CHANGED")
                            }
                    }
                } else bootstrapResult(result, failure, callback)
            }
        }
    }

    private fun bootstrapResult(
        result: JSONObject?,
        error: String?,
        callback: (JSONObject?, String?) -> Unit,
    ) {
        val account = session
        val config = options
        if (error != null || result == null || account == null || config == null) {
            callback(null, error ?: "INVALID_RESPONSE")
            return
        }
        val payload = try {
            BridgeMessages.bootstrap(
                code = result.getString("bootstrapCode"),
                apiBaseUrl = origin(config.apiUrl),
                context = JSONObject(conversationContext.toString()).put("_cluefolk", metadata),
                appearance = theme?.let { JSONObject(it.json) },
                logo = logo,
                language = language,
                navigation = navigation,
                unreadCount = unreadCount,
                eventCursor = eventCursor,
                historySyncId = historySyncId,
                historyPageCount = historyPages.size,
                session = account,
            )
        } catch (_: Exception) {
            callback(null, "INVALID_RESPONSE")
            return
        }
        callback(payload, null)
    }

    internal fun notifyUnreadChanged(count: Int) {
        if (count < 0) return
        unreadObservedAt = android.os.SystemClock.elapsedRealtime()
        if (unreadCount == count) return
        unreadCount = count
        listener?.onCluefolkUnreadChanged(count)
    }

    internal fun notifyConversationCreated(id: String) { listener?.onCluefolkConversationCreated(id) }
    internal fun notifyConversationStatusChanged(id: String, status: String) {
        listener?.onCluefolkConversationStatusChanged(id, status)
    }
    internal fun notifyMessageSent(id: String, messageId: String) {
        listener?.onCluefolkMessageSent(id, messageId)
    }

    internal fun notifyError(code: String) { listener?.onCluefolkError(code) }

    private fun requireReady(): Boolean {
        if (!ready) {
            notifyError("NOT_READY")
            return false
        }
        return true
    }

    private fun reset(clearCredentials: Boolean = true) {
        generation = UUID.randomUUID().toString()
        pendingIdentity = null
        val waiters = refreshWaiters
        refreshWaiters = null
        waiters?.forEach { it("ACCOUNT_CHANGED") }
        session = null
        lastVerifiedExternalId = null
        sliding = false
        unreadRefreshing = false
        gamePlayerIdSending = false
        historyPages = emptyList()
        historySyncId = UUID.randomUUID().toString()
        eventCursor = null
        if (unreadCount != null) notifyUnreadChanged(0)
        unreadCount = null
        unreadObservedAt = null
        conversationContext = JSONObject()
        attributeValues = JSONObject()
        attributeUnset.clear()
        attributeBatch = null
        attributeScheduled = false
        attributeSending = false
        closeSupport()
        // The install secret lives in its own file and survives logout.
        if (clearCredentials)
            context.getSharedPreferences("cluefolk", Context.MODE_PRIVATE).edit { clear() }
    }

    private fun request(
        path: String,
        method: String = "POST",
        token: String? = null,
        body: JSONObject? = null,
        callback: (JSONObject?, String?) -> Unit,
    ) {
        val config = options ?: return callback(null, "NOT_INITIALIZED")
        network.execute {
            var result: JSONObject? = null
            var error: String? = null
            try {
                val connection =
                    URL(origin(config.apiUrl) + "/v1/sdk" + path).openConnection()
                        as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.requestMethod = method
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 15_000
                    connection.setRequestProperty("Content-Type", "application/json")
                    if (token != null)
                        connection.setRequestProperty("Authorization", "Bearer $token")
                    if (body != null) {
                        connection.doOutput = true
                        connection.outputStream.use {
                            it.write(body.toString().toByteArray(Charsets.UTF_8))
                        }
                    }
                    val status = connection.responseCode
                    if (status in 200..299) result = JSONObject(read(connection.inputStream))
                    else {
                        error = "HTTP_$status"
                        // Error bodies are {"error":{"code":"..."}}; see errorCode.
                        result = runCatching { JSONObject(read(connection.errorStream)) }.getOrNull()
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                error = "NETWORK_UNAVAILABLE"
            }
            val output = result
            val failure = error
            main.post { callback(output, failure) }
        }
    }

    private fun read(stream: InputStream): String = stream.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var count = it.read(buffer)
        while (count >= 0) {
            output.write(buffer, 0, count)
            check(output.size() <= 1_048_576)
            count = it.read(buffer)
        }
        output.toString("UTF-8")
    }

    private fun errorCode(result: JSONObject?): String? = result?.optJSONObject("error")?.optString("code")

    // The widget header shows the game's icon: the named drawable or mipmap, or the
    // app icon. Game engines pass resource names, so the lookup is by name.
    @SuppressLint("DiscouragedApi")
    private fun gameIcon(app: Application, name: String?): String? = runCatching {
        val drawable = if (name == null) app.packageManager.getApplicationIcon(app.applicationInfo) else {
            val id = listOf("drawable", "mipmap").map { app.resources.getIdentifier(name, it, app.packageName) }.firstOrNull { it != 0 }
            app.getDrawable(requireNotNull(id))!!
        }
        val bitmap = drawable.toBitmap(192, 192)
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
    }.getOrNull()

    internal fun origin(value: String): String {
        val u = value.toUri()
        return "${u.scheme}://${u.host}" + if (u.port != -1) ":${u.port}" else ""
    }

    internal fun trusted(value: String): Boolean {
        val u = value.toUri()
        return u.scheme == "https" &&
            u.host != null &&
            u.userInfo == null &&
            u.query == null &&
            u.fragment == null
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        val expected = lifetime
        main.post { if (lifetime == expected) action() }
    }

    private fun checkMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Call Cluefolk on the main thread" }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("cluefolk.session", null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            "cluefolk.session",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    private fun binding(config: Options): ByteArray =
        (config.projectId + "\u0000" + origin(config.apiUrl)).toByteArray(Charsets.UTF_8)

    // AES-GCM binds every record to its project and API origin. The synchronous
    // commit reports a failed credential write.
    private fun seal(app: Context, file: String, name: String, config: Options, value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(binding(config))
        val data = cipher.iv + cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        val editor = app.getSharedPreferences(file, Context.MODE_PRIVATE).let(SharedPreferences::edit)
        editor.putString(name, Base64.encodeToString(data, Base64.NO_WRAP))
        check(editor.commit())
    }

    private fun unseal(app: Context, file: String, name: String, config: Options): JSONObject? =
        try {
            app.getSharedPreferences(file, Context.MODE_PRIVATE).getString(name, null)?.let {
                val bytes = Base64.decode(it, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                cipher.updateAAD(binding(config))
                JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            }
        } catch (_: Exception) {
            null
        }

    private fun persist(value: Session) = seal(context, "cluefolk", "session", options!!, value.toJSON())

    /** Created on first use and never cleared, so this install keeps its anonymous player across logouts. */
    private fun loadInstallSecret(app: Application, config: Options): String {
        val name = "secret." + MessageDigest.getInstance("SHA-256").digest(binding(config)).joinToString("") { "%02x".format(it) }
        unseal(app, "cluefolk.install", name, config)?.optString("secret")?.takeIf(InstallSecret::valid)?.let { return it }
        return InstallSecret.generate().also { secret ->
            runCatching { seal(app, "cluefolk.install", name, config, JSONObject().put("secret", secret)) }
        }
    }
}
