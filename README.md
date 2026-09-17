# Rokid Voice Relay

**AIUI development build: v0.9.0-aiui-beta.** This branch adds the AIUI glasses app, a paired Android Bluetooth bridge, Telegram voice-note download/playback and voice/file/text replies, WhatsApp sharing fallback, and in-app controls for unlocked-phone alerts, DND, Silent mode and each messaging app. Start with [AIUI setup](AIUI_SETUP.md). The standalone glasses project is in [`aiui/voice-relay`](aiui/voice-relay). Hardware verification is still required.

The documentation below describes the retained **v0.8 Nexus experience**. The AIUI interface and WhatsApp fallback differ; follow the AIUI setup guide for this build.

**Rokid Voice Relay** is an experimental Android companion plugin for **Rokid Nexus / Rokid Glasses**. It brings Telegram and WhatsApp notifications to the HUD, keeps a persistent glasses-side inbox, and lets the wearer record real voice replies using the Rokid microphone — without speech-to-text or language selection.

> **Current release:** v0.8 Beta  
> **Android:** 11+ (`minSdk 30`)  
> **Rokid Nexus SDK:** `sdk-v0.16.0`

## Highlights

- Telegram and WhatsApp conversation notifications on the Rokid HUD.
- ~8-second incoming-message notice with optional display wake.
- Native Nexus **backdrop mode** hides the underlying HUD while a message notice is active and helps prevent input leaking to the UI underneath.
- Persistent multi-conversation inbox on the glasses.
- Smart persistent de-duplication filters Telegram reminder/re-post events and prevents old pending messages from popping up again when a genuinely new notification arrives.
- More resilient notification delivery: temporarily blocked Nexus notices remain eligible for replay for up to 120 seconds instead of being discarded after a short cold-start/link delay.
- More resilient manual launch: the inbox retries rendering when Nexus/link readiness changes instead of relying on a single surface-open attempt.
- Audio recording directly from the Rokid glasses microphone (16 kHz mono PCM).
- **Send / Retake / Cancel** before a voice reply is transmitted.
- Telegram native voice-note sending through TDLib using OGG/Opus.
- Experimental WhatsApp audio-reply transport using supported Android notification/data-reply interfaces when exposed by the installed WhatsApp version, with Android voice-message intent fallback.
- Best-effort detection/playback of received voice messages when the source notification exposes an accessible audio URI.
- Phone Silent-mode suppression and optional suppression while the phone is unlocked.
- No Voice Relay Wi-Fi dependency: ordinary Voice Relay/Nexus plugin traffic uses the existing Nexus glasses link/Bluetooth bus. Internet access is still required on the phone for Telegram/WhatsApp themselves.

## Messaging support

### Telegram

Telegram notification capture, inbox routing, glasses-microphone recording and native voice-note sending are implemented. Supported packages:

- Telegram Play Store — `org.telegram.messenger`
- Telegram direct-download — `org.telegram.messenger.web`
- Telegram Beta — `org.telegram.messenger.beta`
- Telegram X — `org.thunderdog.challegram`

Voice Relay uses the Android conversation shortcut (for example `ndid_...`) as a routing hint and validates/resolves the target conversation through TDLib before sending. Telegram setup requires your own `api_id` and `api_hash` from `my.telegram.org`.

### WhatsApp

Notification capture and inbox display are supported for:

- WhatsApp — `com.whatsapp`
- WhatsApp Business — `com.whatsapp.w4b`

WhatsApp voice sending is **experimental**. Voice Relay first tries the original notification's Android data-reply channel when WhatsApp exposes an audio MIME type. If unavailable, it tries Android's standard voice-message-to-contact contract. Some WhatsApp versions may require confirmation on the phone, and some may expose neither route. Voice Relay does not use an unofficial WhatsApp network protocol.

## Received voice-message playback

v0.8 detects likely voice-message notifications and can offer **Play**. Playback currently requires the messaging notification to expose an Android-accessible audio URI and an available Bluetooth audio output. Voice Relay deliberately does not fall back to the phone loudspeaker.

- **Telegram:** notification-exposed audio can be played. A future TDLib media-download path can make this more reliable when the notification itself does not expose the media.
- **WhatsApp:** generally limited because personal WhatsApp notifications usually do not expose the underlying encrypted voice-note media file to other Android apps.

A failed/unavailable Play attempt does not remove the message; the wearer can still record a reply.

## Glasses experience

```text
New message
    ↓
Backdrop HUD notice
    ↓
Tap Voice note
    ↓
Record from Rokid microphone
    ↓
Tap to stop
    ↓
Send / Retake / Cancel
    ↓
Telegram native voice message
```

If the temporary notice is ignored, the conversation remains in the Voice Relay inbox.

### Inbox controls

- **Left / Up:** previous pending conversation
- **Right / Down:** next pending conversation
- **Center / Enter:** record a voice reply
- If the selected item is recognized as a voice message, **Center / Enter first attempts Play**; after playback/unavailable status, tap again to reply
- During recording: **Center / Enter** stops recording
- Confirmation: **Center / Enter** sends, **Up / Left** retakes, **Back** cancels

## Reliability and de-duplication

Voice Relay distinguishes actual new messaging events from Android/Telegram notification re-posts. Event identities are persisted across process restarts, and currently active notifications are seeded when the listener reconnects so a restart does not create a burst of old HUD popups.

If Nexus is temporarily not registered/ready or the glasses link is recovering, an incoming notice stays pending for up to two minutes and is retried when link/registration state changes. Manual inbox opening also retries rather than relying on one surface-render attempt.

## Phone-aware notification filters

Two independent filters affect automatic HUD delivery only; suppressed messages remain available in the inbox:

- **Keep glasses quiet when phone is Silent** — enabled by default.
- **Hide HUD notifications while phone is unlocked** — optional; applies while the display is on and keyguard is dismissed.

The manual test-notification button intentionally ignores these filters.

## Connectivity

Voice Relay does not request or manage Wi-Fi and does not intentionally open Wi-Fi Settings. Ordinary plugin commands, notices, input and microphone traffic use the Nexus phone/glasses link. You can test Voice Relay with **phone Wi-Fi off**, Bluetooth/Nexus connected, and mobile data providing internet connectivity.

If Wi-Fi Settings opens while using Voice Relay, report the exact sequence that caused it so the Nexus/Rokid link-state path can be investigated.

## Setup

1. Install the APK on the Android phone paired with Rokid Nexus.
2. In **Rokid Nexus → Plugin access**, approve **Voice Relay** with **Surfaces** and **Microphone** access.
3. In Voice Relay, open **Android notification access** and enable Voice Relay.
4. Disable the stock **Nexus Relay** plugin to avoid duplicate notifications/reply handling. Keep the main Rokid Nexus system enabled.
5. For Telegram sending, obtain your own Telegram `api_id` and `api_hash` from `my.telegram.org` and enter them only in Voice Relay's local Telegram setup screen.
6. Complete Telegram login on the phone. Do not share the API hash, login code or 2FA password.

v0.8 uses the same persistent development signer as previous test builds and can update compatible v0.2+ installations in place.

## Privacy and security

- Notification text and the pending inbox are stored locally in the app's private Android storage.
- Telegram API credentials and the TDLib database key use Android Keystore-backed encryption.
- Voice Relay does not perform speech-to-text and does not transcribe voice replies.
- Telegram voice replies are encoded to OGG/Opus before transmission through TDLib.
- WhatsApp integration avoids unofficial account/network protocols.

## Build details

- Kotlin / Android
- Android Gradle Plugin 9.2.0
- Gradle 9.5.1
- JDK 17
- compileSdk / targetSdk 36
- minSdk 30
- Rokid Nexus SDK `sdk-v0.16.0`
- TDLib 1.8.65 Android native libraries are downloaded from a pinned release during CI and SHA-256 verified before packaging
- Persistent development signing key for in-place test upgrades

## Status

v0.8 is a **Beta / experimental release**. Telegram notification capture, glasses microphone recording, inbox handling and Telegram voice-note sending have been exercised on real hardware. The v0.8 Nexus reliability/backdrop changes and received-media playback path are active hardware-testing features. WhatsApp sending and received voice playback depend on capabilities exposed by the installed WhatsApp/Android notification implementation.

## Disclaimer

This is an independent project and is not affiliated with, endorsed by, or sponsored by Rokid, Telegram, WhatsApp, or Meta.
