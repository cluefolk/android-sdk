# Cluefolk Android SDK

In-game player support for Android games. Players chat with your support team without leaving the game. The support screen is built into the SDK, so nothing is downloaded when it opens.

- Kotlin and Java, Android 7.0 (API 24) and later
- Works without sign-in: players start anonymously, linking a game account is optional
- Unity games use the [Cluefolk Unity package](https://github.com/cluefolk/unity-sdk), which wraps this SDK

## Requirements

- `minSdk` 24, `compileSdk` 34 or later
- Android Gradle Plugin 7.4 or later, AndroidX enabled (`android.useAndroidX=true`)
- A Cluefolk project: copy its **Project ID** from **Settings → General** at [cluefolk.com](https://cluefolk.com)

## Install

The SDK is on Maven Central, which new Android projects already list in their repositories.

```kotlin
dependencies {
    implementation("com.cluefolk:android-sdk:0.3.2")
}
```

It brings AndroidX Activity, Core and WebKit. R8/ProGuard rules ship inside the AAR.

## Quick start

Initialize once, from the UI thread, with your Project ID:

```kotlin
class GameApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Cluefolk.setEventListener(supportListener) // Optional: readiness, unread count, errors.
        Cluefolk.initialize(this, Cluefolk.Options(projectId = "YOUR_PROJECT_ID"))
    }
}
```

`initialize` returns at once and loads saved credentials in the background. When it is done, `Cluefolk.isReady()` is `true` and the listener receives `onCluefolkReady()`. Then a support button opens the chat:

```kotlin
Cluefolk.open(activity)                 // The support home screen.
Cluefolk.openConversation(activity, "") // Straight to the player's current chat.
```

From Java: `Cluefolk.initialize(application, new Cluefolk.Options("YOUR_PROJECT_ID"));` and `Cluefolk.open(activity);`.

Callbacks always arrive on the main thread; other methods can be called from any thread. Before the SDK is ready, operations fail with `NOT_READY`; the listener and identity provider can be set earlier.

## Players

Support is anonymous by default. On first use the SDK creates a random install secret, stores it encrypted with an Android Keystore key and uses it to recognise the device, so the history survives restarts. No game backend is needed.

### The game's player ID

Pass your own player ID so the support team can find the player. It is shown to the team as **not verified**.

```kotlin
Cluefolk.setPlayerId(game.userId) // null or "" clears it.
```

The ID is trimmed and kept in memory only. IDs longer than 256 characters or containing line breaks are ignored with `INVALID_ARGUMENT`. `logout()` clears it.

### Verified players (optional)

To link support to the game account, your game backend signs a short-lived identity token after the player signs in to the game: ES256 JWT, `typ: cluefolk-identity+jwt`, at most 5 minutes, `sub` = your player ID. Add the backend's public key in **Settings → Server connection → Players and sign-in**. Signing keys never go into the app.

```kotlin
// After the game login succeeds; the device's anonymous history moves to the account.
Cluefolk.loginWithIdentity(identityToken) { error ->
    // A support error must not fail the game login.
}

// Optional: let the SDK ask your backend for a fresh token when it needs a new session.
Cluefolk.setIdentityTokenProvider { callback ->
    game.fetchCluefolkIdentity { token, error -> callback.complete(token, error) }
}

// When the player signs out or switches accounts:
Cluefolk.logout()
```

Sessions last 30 days and extend with use. If your backend cannot provide a token, support still opens anonymously and the listener receives the non-fatal `IDENTITY_UNAVAILABLE`. If the project requires sign-in, anonymous sessions fail with `IDENTITY_REQUIRED`.

## Context for the support team

```kotlin
Cluefolk.setAttributes("""{"level":37}""")             // Keys registered in your project.
Cluefolk.setConversationContext("""{"screen":"shop"}""") // Sent with the next new conversation.
```

The SDK also reports the platform, OS version, device model, app version and build, and language.

## Appearance

The support screen's look ships inside your game build. In Cluefolk open **Settings → Widget**, press **Download for build** and add the `<ID>_widget.json` file to your app (for example to `assets/`). After the SDK is ready and before opening support:

```kotlin
val json = assets.open("YOUR_ID16_widget.json").bufferedReader().use { it.readText() }
Cluefolk.setAppearance(Appearance.Builder(json).build())
```

`Appearance.Builder()` without a file starts from the default look; change it in code with `title`, `mode`, `rotation`, `lightColors`/`darkColors`, `welcome`, `topics` and `contact`. `build()` throws `IllegalArgumentException` with the reason if a value is invalid. `setAppearance(null)` returns to the default look.

The screen turns like the game unless the theme sets `rotation` to `FREE`.

## Push notifications

Notifications about support replies go through your game server and your existing push provider; Cluefolk never holds device tokens. Forward Cluefolk messages to the SDK:

```kotlin
override fun onMessageReceived(message: RemoteMessage) {
    if (Cluefolk.handlePush(message.data)) return // Updates the unread count.
    // Your own notifications…
}

// When the player taps the notification, pass the notification data from the Intent:
val data = intent.extras?.let { extras -> extras.keySet().associateWith { extras.getString(it).orEmpty() } }.orEmpty()
Cluefolk.openFromPush(activity, data)
```

Both return `false` for notifications that are not from Cluefolk. You do not need a separate messaging service.

See [Push notifications](https://cluefolk.com/guide/en/#push) for the server side.

## Events and errors

`Cluefolk.EventListener` receives `onCluefolkReady`, `onCluefolkOpened`, `onCluefolkClosed`, `onCluefolkUnreadChanged(count)`, `onCluefolkIdentityTokenRequired(requestId)`, `onCluefolkConversationCreated`, `onCluefolkConversationStatusChanged`, `onCluefolkMessageSent` and `onCluefolkError(code)`. Answer `onCluefolkIdentityTokenRequired` with `Cluefolk.completeIdentityToken(requestId, token, error)` if you do not use a provider.

## Privacy and security

- HTTPS only. The support page runs from the SDK's own assets with a strict Content Security Policy; unknown navigation and bridge commands are refused.
- Session and install secret are encrypted with an Android Keystore key. Images the player sent are kept on the device as encrypted previews, outside backups; `logout()` deletes them.
- The SDK never stores game access tokens or signing keys.

## More

- [Cluefolk guide: integrating the game client](https://cluefolk.com/guide/en/#client-integration)
- [Game server contract](https://cluefolk.com/game-integration.md)
- [Changelog](CHANGELOG.md) · [License: MIT](LICENSE)
