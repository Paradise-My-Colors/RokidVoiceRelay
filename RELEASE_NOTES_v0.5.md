# Rokid Voice Relay v0.5 Beta

v0.5 is the first formal GitHub release of Rokid Voice Relay.

Rokid Voice Relay is an Android companion plugin for Rokid Nexus / Rokid Glasses that displays supported messaging notifications on the glasses and allows Telegram conversations to be answered with a real voice note recorded directly from the Rokid microphone — without speech-to-text or language selection.

## Highlights

- Real Telegram notifications appear as an ~8-second Rokid HUD notice.
- The glasses display can wake for incoming supported notifications.
- Voice notes are recorded directly from the Rokid microphone.
- Telegram replies are sent as native voice messages through TDLib.
- A persistent pending-message inbox remains available after the temporary HUD notice disappears.
- Multiple pending conversations can be reviewed and answered from the glasses.
- Repeated messages from the same conversation are grouped into one inbox entry.
- Send / Retake / Cancel confirmation is shown before transmission.
- Successfully replied Telegram conversations are removed from the Voice Relay inbox.
- Dismissing the corresponding Android notification also removes its inbox entry.
- A playable WAV copy of the latest recording can be kept on the phone.

## New in v0.5

### Respect phone Silent mode

Enabled by default. When Android is in true Silent ringer mode, incoming messages are still captured into the Voice Relay inbox, but the glasses are not woken and no HUD notice is shown.

### Hide notifications while the phone is unlocked

An optional setting can suppress Rokid HUD notices while the phone screen is on and the keyguard is dismissed. The message is still stored in the Voice Relay inbox for later review/reply.

These filters affect only automatic HUD delivery. The manual test-notification button continues to work regardless of phone state.

## Telegram support

Voice-note sending is implemented for Telegram through TDLib. The app supports notification capture from:

- Telegram Play Store (`org.telegram.messenger`)
- Telegram direct-download (`org.telegram.messenger.web`)
- Telegram Beta (`org.telegram.messenger.beta`)
- Telegram X (`org.thunderdog.challegram`)

Telegram setup requires your own `api_id` and `api_hash` from `my.telegram.org`. Enter these only inside Voice Relay on your own phone. Do not share your API hash, Telegram login code, or 2FA password.

## WhatsApp status

Notification capture and inbox display are supported for WhatsApp and WhatsApp Business. Native WhatsApp voice-note sending is **not yet implemented**.

## Installation

1. Install `RokidVoiceRelay-v0.5.apk` on your Android phone.
2. In Rokid Nexus, approve Voice Relay with **Surfaces** and **Microphone** access.
3. Enable Voice Relay under Android **Notification access**.
4. Disable the stock Nexus Relay plugin to avoid duplicate notices/reply behavior; keep the main Rokid Nexus app running.
5. Open **Telegram setup / login** in Voice Relay and complete the one-time Telegram authorization.

v0.5 uses the same persistent development signer established for v0.2+, so it can update compatible earlier test versions in place.

## Privacy

- Notification text and pending inbox data are stored locally in the app's private Android storage.
- Telegram credentials and the TDLib database key are protected using Android Keystore-backed encryption.
- Voice replies are not transcribed.
- Rokid microphone audio is encoded to OGG/Opus before Telegram transmission.

## Requirements

- Android 11 or newer (`minSdk 30`)
- Rokid Nexus with compatible Rokid Glasses
- Nexus plugin permissions: Surfaces + Microphone
- Android Notification Access
- Telegram developer `api_id` / `api_hash` for Telegram sending

## Release status

This is a **Beta / experimental release** intended for testing. Telegram notification delivery, inbox handling, Rokid microphone recording, and Telegram voice-note sending have been validated on real hardware. WhatsApp voice-note sending remains future work.

The release includes a `.sha256` file so the downloaded APK can be integrity-checked.

---

Rokid Voice Relay is an independent project and is not affiliated with, endorsed by, or sponsored by Rokid, Telegram, or WhatsApp/Meta.
