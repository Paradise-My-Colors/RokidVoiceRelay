# Rokid Voice Relay

**Rokid Voice Relay** is an experimental Android companion plugin for **Rokid Nexus / Rokid Glasses** that brings Telegram and WhatsApp notifications to the glasses and lets you reply to Telegram conversations with a real voice note recorded directly from the Rokid microphone.

The project was built specifically to avoid speech-to-text. Voice replies remain language-agnostic: speak Arabic, German, English, or switch between them naturally without selecting a recognition language.

> **Current release:** v0.5 Beta  
> **Android:** 11+ (`minSdk 30`)  
> **Rokid Nexus SDK:** `sdk-v0.16.0`

## What it does

- Shows supported Telegram / WhatsApp notifications as an ~8 second HUD notice on Rokid Glasses.
- Can wake the glasses display for an incoming message.
- Records actual audio from the Rokid glasses microphone.
- Sends recorded audio to Telegram as a native Telegram voice message using TDLib.
- Keeps a persistent pending-message inbox on the glasses after the temporary popup disappears.
- Supports multiple pending conversations and lets you move between them from the glasses.
- Groups repeated messages from the same conversation into one pending conversation entry.
- Removes an inbox entry when the corresponding Android notification is dismissed/removed or after a Telegram voice reply is successfully sent.
- Offers **Send / Retake / Cancel** after recording instead of transmitting immediately.
- Stores a playable WAV copy of the most recent recording on the phone.
- Can suppress HUD notifications when the phone is in **Silent** mode.
- Can optionally suppress HUD notifications while the phone is **unlocked and in active use**.
- Suppressed notifications are still captured in the Voice Relay inbox, so they can be answered later.

## Current messaging support

### Telegram

Notification capture and native voice-note sending are implemented.

Supported Android packages include:

- Telegram Play Store — `org.telegram.messenger`
- Telegram direct-download — `org.telegram.messenger.web`
- Telegram Beta — `org.telegram.messenger.beta`
- Telegram X — `org.thunderdog.challegram`

Voice Relay uses the Android conversation shortcut (for example `ndid_...`) as a routing hint and validates/resolves the corresponding Telegram conversation through TDLib before sending.

### WhatsApp

Notification capture and inbox display are implemented for:

- WhatsApp — `com.whatsapp`
- WhatsApp Business — `com.whatsapp.w4b`

**Sending native WhatsApp voice notes is not implemented yet.** Android notification `RemoteInput` only supports text replies, so WhatsApp requires a different transport strategy than Telegram.

## Glasses experience

Typical flow:

```text
Telegram notification arrives
        ↓
8-second Rokid HUD notice
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
        ↓
Pending inbox entry removed after success
```

If the temporary notice is ignored, the conversation remains available in the Voice Relay inbox on the glasses.

### Inbox controls

- **Left / Up:** previous pending conversation
- **Right / Down:** next pending conversation
- **Center / Enter:** record a voice reply
- During recording: **Center / Enter** stops recording
- Confirmation screen: **Center / Enter** sends, **Up / Left** retakes, **Back** cancels

## Phone-aware notification filters

v0.5 adds two independent HUD filters:

- **Respect phone Silent mode** — enabled by default. If Android ringer mode is Silent, the message is stored in the inbox but the glasses are not woken and no HUD notification is shown.
- **Hide HUD notifications while phone is unlocked** — optional. If enabled, messages received while the phone screen is on and the keyguard is dismissed are stored without interrupting the glasses.

The test-notification button intentionally ignores these filters so the Nexus connection can always be tested.

## Setup

1. Install the APK on the Android phone paired with Rokid Nexus.
2. In **Rokid Nexus → Plugin access**, approve **Voice Relay** with **Surfaces** and **Microphone** access.
3. In Voice Relay, open **Android notification access** and enable Voice Relay.
4. Disable the stock **Nexus Relay** notification plugin to avoid duplicate notices or competing reply behavior. Keep the main **Rokid Nexus** app running.
5. For Telegram voice-note sending, obtain your own Telegram `api_id` and `api_hash` from `my.telegram.org` and enter them only in the local Telegram setup screen inside Voice Relay.
6. Complete Telegram login on the phone. Login codes, 2FA passwords, and the API hash should not be shared with anyone.

## Privacy and local data

- Notification text and the Voice Relay inbox are stored locally in the app's private Android preferences.
- Telegram API credentials and the TDLib database key are protected using Android Keystore-backed encryption.
- Voice recordings are captured as 16 kHz mono PCM from the Rokid microphone. A WAV copy can be published to `Music/RokidVoiceRelay` for testing/playback.
- Telegram voice replies are converted to OGG/Opus before sending through TDLib.
- The app does not require speech recognition and does not transcribe your voice reply.

## Build details

- Kotlin / Android
- Android Gradle Plugin 9.2.0
- Gradle 9.5.1
- JDK 17
- compileSdk / targetSdk 36
- minSdk 30
- Rokid Nexus SDK `sdk-v0.16.0`
- TDLib 1.8.65 Android native libraries are downloaded from a pinned release during CI and verified by SHA-256 before packaging.
- GitHub Actions uses a persistent development signing key so v0.2+ builds can update in place during testing.

## Status

v0.5 is a **Beta / experimental build**, not a production-hardened release. Telegram notification delivery, multi-conversation inbox handling, Rokid microphone capture, and Telegram voice-note sending have been validated on real hardware. WhatsApp voice-note sending remains future work.

## Disclaimer

This is an independent project and is not affiliated with, endorsed by, or sponsored by Rokid, Telegram, or WhatsApp/Meta.
