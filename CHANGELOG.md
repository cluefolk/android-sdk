# Changelog

## 0.3.2 — 2026-11-03

- Validate appearance values and embedded images before parsing or WebView decoding; reject excessive dimensions and pixel counts.
- Enforce bridge payload types and bound identity provider requests to 15 seconds.
- Keep attachment previews bounded while preserving JPEG orientation and WebView compatibility.
- Lock dependencies and verify plugin, dependency and metadata SHA-256 checksums in strict mode.

## 0.3.1 — 2026-11-02

Support screen updates.

- Replies are signed with the operator's first name, for example "Anna · Support team", once the operator sets a name; otherwise "Support team".
- Delivery shows as compact ticks next to the time: one when sent, two when delivered, two at full strength once read.
- Inside a conversation the message box no longer shows the player ID.
- The "Talk to the team" button rests at the bottom of the home screen.

## 0.3.0 — 2026-11-02

First public release.

- Published to Maven Central as `com.cluefolk:android-sdk`.
- Games can compile with `compileSdk` 34 and Android Gradle Plugin 7.4 (Unity 2022.3): the SDK uses AndroidX Activity 1.9.3, Core 1.13.1 and WebKit 1.12.1.
- `Cluefolk.VERSION` gives the SDK version.
- The built-in support screen reads conversation fields from the project configuration.
