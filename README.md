# Rokid Voice Relay — prototype v0.1

A small experimental Rokid Nexus plugin for recording **actual audio** from the Rokid glasses microphone instead of converting speech to text.

## v0.1 target flow

1. Telegram / WhatsApp notification arrives on the Android phone.
2. Voice Relay shows a Nexus notice on the glasses for **8 seconds** and requests a display wake.
3. Tap the **mic** action.
4. The notice closes and a recording surface opens.
5. Audio is captured directly from the Rokid/Nexus raw microphone stream at 16 kHz mono PCM.
6. Tap again to stop and save, or press Back to cancel.
7. The prototype writes a WAV file into `Music/RokidVoiceRelay` through Android MediaStore.

**v0.1 deliberately does not send the audio back to Telegram or WhatsApp.** The goal is to validate notification capture, HUD interaction, cold-start behavior, and Rokid microphone recording before implementing chat-specific delivery.

## Supported notification packages in v0.1

- WhatsApp: `com.whatsapp`
- WhatsApp Business: `com.whatsapp.w4b`
- Telegram: `org.telegram.messenger`
- Telegram X: `org.thunderdog.challegram`

## Nexus permissions

The plugin requests only:

- `surfaces`
- `microphone`

It does **not** request Nexus STT and does not declare Android `RECORD_AUDIO`, because the audio is delivered by Nexus from the glasses.

## Installation / first test

After an APK is built:

1. Install the APK on the Android phone.
2. Open **Rokid Nexus → Settings → Plugin access** and approve **Voice Relay**.
3. Grant the plugin **Surfaces** and **Microphone**.
4. Open Voice Relay's settings from Nexus and tap **Open Android notification access**.
5. Enable **Voice Relay**.
6. Wear/connect the Rokid glasses.
7. Tap **Send test message to glasses**.
8. The test band should appear for 8 seconds. Tap its mic action.
9. Speak, then tap once to stop.
10. Use **Play last recording** in the phone settings page to verify the glasses microphone capture.

Then send the phone a real Telegram or WhatsApp message and repeat.

## Build without Android Studio

The repository includes `.github/workflows/build-apk.yml`. Put these files into a GitHub repository and run **Actions → Build Voice Relay APK → Run workflow**. The workflow builds and uploads `app-debug.apk` as an Actions artifact.

Current build contract used by this prototype:

- Android Gradle Plugin 9.2.0
- Gradle 9.5.1
- JDK 17
- compileSdk / targetSdk 36
- minSdk 30
- Rokid Nexus published SDK `sdk-v0.16.0`

## Prototype caveat

The notification-listener → dormant plugin cold-start path is intentionally part of this first hardware test. If a popup works while Voice Relay has recently been opened but not after Android kills the process, that result is useful: v0.2 should adopt Relay's exact one-shot wake/registration mechanism rather than adding a permanent foreground process.
