# Rokid Voice Relay — prototype v0.2

Experimental Rokid Nexus plugin for replying to Telegram/WhatsApp notifications by recording real audio from the Rokid glasses microphone instead of speech-to-text.

## v0.2 target flow

1. Telegram / WhatsApp notification arrives on Android.
2. The notification listener creates a direct short-lived `NexusPluginClient`, matching the production Relay notification architecture.
3. Voice Relay shows an 8-second notice on the glasses and requests display wake.
4. Tap **Voice note**.
5. Record through the Rokid microphone; tap to stop or Back to cancel.
6. The WAV is stored in `Music/RokidVoiceRelay`.
7. The recording retains its originating app, sender, package, notification key and conversation shortcut when available for the upcoming send stage.

**v0.2 does not yet send the audio back to the chat.** It validates reliable real-notification delivery plus target preservation before Telegram delivery is added.

## Supported packages

- WhatsApp: `com.whatsapp`
- WhatsApp Business: `com.whatsapp.w4b`
- Telegram Play Store: `org.telegram.messenger`
- Telegram direct-download: `org.telegram.messenger.web`
- Telegram beta: `org.telegram.messenger.beta`
- Telegram X: `org.thunderdog.challegram`

## v0.2 fixes

- Replaces the v0.1 `startService()` cold-start attempt with a direct Nexus client owned by the notification listener.
- Adds notification-listener reconnect handling.
- Adds Telegram direct-download and beta package variants.
- Settings now show notification grant, listener connection state, last captured sender/package, and shortcut ID when present.
- Saved audio is linked to its source notification target.
- GitHub Actions establishes a persistent debug signing identity from v0.2 onward.

## First v0.2 installation

Because v0.1 was built before persistent signing was established, uninstall v0.1 before installing v0.2. Then approve Voice Relay again in Nexus, grant Surfaces + Microphone, and enable Android Notification Access. Builds after v0.2 should update in place using the cached signing identity.

## Build contract

- Android Gradle Plugin 9.2.0
- Gradle 9.5.1
- JDK 17
- compileSdk / targetSdk 36
- minSdk 30
- Rokid Nexus SDK `sdk-v0.16.0`
