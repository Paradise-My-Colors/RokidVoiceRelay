# Rokid Voice Relay v0.8 Beta

v0.8 focuses on **Nexus reliability, cleaner HUD behavior and experimental received voice-message playback**, while retaining the Telegram voice-reply and multi-conversation inbox functionality from earlier builds.

## Major changes

### More reliable HUD delivery

Voice Relay no longer gives up after only a few seconds when Nexus registration or the glasses link is temporarily unavailable. A newly captured message can remain pending for up to **120 seconds** and is retried as Nexus/link state changes.

This is intended to reduce cases where the phone receives and sounds a Telegram/WhatsApp notification but nothing appears on the glasses during a cold start or transient link interruption.

### More reliable glasses-side launch

Opening Voice Relay manually from the glasses no longer depends on a single surface-render attempt. The inbox retries rendering while the plugin/link becomes ready.

### Backdrop notifications

Incoming Voice Relay notices now request Nexus **backdrop mode**, hiding the rest of the HUD while the notice is active and helping prevent touches intended for Voice Relay from leaking to whatever UI is underneath.

### Bluetooth/Nexus-link operation

Voice Relay does not request or manage Wi-Fi. Ordinary notifications, input and microphone traffic use the existing Nexus phone/glasses link. The phone still needs internet connectivity for Telegram or WhatsApp, which can be Wi-Fi or mobile data.

### Voice-message detection and playback experiment

v0.8 detects likely received voice-message notifications and can expose a **Play** action. Playback works only when the source notification provides an Android-accessible audio URI and a Bluetooth audio output is available. Voice Relay intentionally refuses to fall back to the phone loudspeaker.

Telegram playback can be improved later with a dedicated TDLib media-download path. WhatsApp playback is inherently limited because personal WhatsApp notifications usually do not expose their encrypted voice-note media to other Android apps.

## Existing messaging features

- Telegram and WhatsApp notification capture.
- Persistent multi-conversation glasses inbox.
- Smart persistent notification de-duplication, including filtering Telegram reminder/re-post events.
- Old pending conversations stay in the inbox without repeatedly popping up when another conversation receives a genuinely new message.
- Rokid microphone recording without speech-to-text.
- Send / Retake / Cancel confirmation.
- Native Telegram OGG/Opus voice-note sending through TDLib.
- Experimental WhatsApp audio sending through Android-supported notification data reply, with voice-message intent fallback when available.
- Silent-mode suppression and optional suppression while the phone is unlocked.
- Android notification dismissal synchronized with the Voice Relay inbox.

## Important limitations

- WhatsApp voice sending is **experimental** and depends on interfaces exposed by the installed WhatsApp version. Some devices/versions may require phone confirmation or may expose no supported audio-send route.
- Received voice-message playback is **best effort**, not guaranteed.
- Nexus currently provides microphone audio to plugins but does not expose a generic arbitrary-audio stream from a plugin directly into the glasses speaker. v0.8 therefore uses Android's Bluetooth media route for playback.
- This remains a Beta/debug-signed build intended for hardware testing.

## Installation

Install `RokidVoiceRelay-v0.8.apk` directly over a compatible earlier Voice Relay build. The persistent development signer is retained, so normal in-place upgrades should preserve Telegram authorization, settings and app data.

Required setup remains:

1. Voice Relay approved in Rokid Nexus with **Surfaces** and **Microphone** access.
2. Voice Relay enabled under Android **Notification access**.
3. Stock Nexus Relay disabled if you want to avoid duplicate message notifications.
4. Telegram `api_id` / `api_hash` configured locally for Telegram native voice-note sending.

## Privacy

- No speech-to-text or voice transcription.
- Telegram credentials and TDLib database key use Android Keystore-backed encryption.
- Telegram voice replies are encoded to OGG/Opus before transmission.
- Voice Relay does not use an unofficial WhatsApp account/network protocol.

The release contains the APK and a SHA-256 checksum file.

---

Rokid Voice Relay is an independent project and is not affiliated with, endorsed by, or sponsored by Rokid, Telegram, WhatsApp, or Meta.
