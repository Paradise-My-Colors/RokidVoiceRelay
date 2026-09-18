# Voice Relay Link 1.0.0 — new network edition

This version replaces the failing AIUI Bluetooth/GATT bridge with a local network connection. The Android companion retains Telegram login, received messages, settings and delivery receipts. The glasses get a new agent named **Voice Relay Link**, visibly marked **VOICE LINK 1.0.0**.

The previous 0.9.2 source already had no calls to global `crypto`. The repeated `crypto is not defined` report does not identify whether old resources were still loaded or an AIUI host module failed. This edition removes the Bluetooth dependency and the startup audio-module import, uses a distinct agent name and page route, and bundles its implementation into one page. Its startup and authenticated-encryption code have been executed in a real QuickJS engine without Web Crypto or Bluetooth. That is not a hardware test of the Rokid host.

## Required connection

Connect the glasses and phone to the **same Wi-Fi**, or connect the glasses to **your phone's hotspot**. Keep that connection while using the app. Telegram and WhatsApp need Internet on the phone as usual. Hi Rokid's existing pairing stays in place; there is no additional Voice Relay Bluetooth Pair/Approve step.

The phone generates the connection settings and a private key. A setup ZIP exported by your own phone is required for a working installation. The generic developer template has no key and shows setup help. Do not publish or share your phone-generated ZIP: it contains the key that authorizes access to this phone's Voice Relay link.

## Exact first setup

1. Install **RokidVoiceRelay-Link-v1.0.0-test.apk** over the existing **Voice Relay AIUI** app. Do not uninstall or clear data. It has the same Android package ID and signing certificate as AIUI v0.9–0.9.2. The launcher is now named **Voice Relay Link**; its screen says **LINK 1.0.0 · Network edition**. The older Nexus-only v0.8 app is separate.
2. Put the glasses and phone on the same Wi-Fi, or enable your phone hotspot and connect the glasses to it. The phone screen shows the local address it will include in the setup package. No address needs to be typed into source code.
3. In the phone app, tap **2. Start phone link**. Allow notifications if requested. Wait for **Phone link ready · LINK 1.0.0**. Keep the phone app open during this first setup.
4. Tap **3. Export glasses setup ZIP**. In Android's save dialog choose **Downloads** and save **VoiceRelay-Link-Setup.zip**. Copy that ZIP to your computer, for example through USB. This exported file is the ready-configured glasses app.
5. Extract the ZIP. In [AIUI Studio Global](https://aiui-global.rokid.com/), use **Local import** and select the extracted **voice-relay-link** folder, with `app.json` directly inside it. Create the project as **Voice Relay Link** so it is distinct from the older Voice Relay agent. Do not ask Studio to regenerate or rewrite this source.
6. Select **Build & Review → Package AIX** and wait for success. In Hi Rokid on the phone, using the same account, select **Settings → Developer → Update glasses resource package**. Wait for **Agent resource package downloaded successfully**. These are [Rokid's documented deployment steps](https://github.com/jsar-project/AIUI/blob/main/documentation/0-guide/quickstart/quickstart.en-US.md).
7. Close the old Voice Relay agent. Say **“Hi Rokid, open Voice Relay Link.”** Before tapping anything, confirm the opening screen says **VOICE LINK 1.0.0**. Tap **Connect to phone** once. A successful connection opens **Inbox**; the phone says **Connected by Wi-Fi · Inbox ready · LINK 1.0.0**.
8. If the inbox is empty, tap **1. Enable notification access** on the phone and enable **Voice Relay AIUI** in Android's list (that system service keeps its existing name). Disable the older separate Voice Relay listener if it duplicates alerts. Receive a fresh Telegram or WhatsApp notification and select **Refresh inbox** on the glasses.
9. Use **Telegram setup / login** only if this companion is not already logged in. Use the same Telegram account that receives notifications. An in-place AIUI upgrade retains its session; no new login should be necessary solely for this update.

A browser preview can show the interface, but it is not proof of the deployed version on your glasses. A website preview may also be unable to reach your phone's private network from its host.

## Everyday use

Leave the phone link running; its ongoing notification shows that the service is active. Open **Voice Relay Link** on the glasses and tap **Connect to phone**. Swipe to choose, tap to act, and Back to return or cancel. Keep the agent page open while using it. If you stopped the service, tap **Start phone link** on the phone first.

If the phone's network address changes, the phone screen says **Phone address changed**. Export a new setup ZIP, import/update the Link project, Package AIX and sync again. The private key remains the same unless app data is cleared. A stable Wi-Fi address or a phone hotspot avoids frequent setup changes, although Android can change hotspot addresses too.

## Listen and reply

- **Listen:** choose a conversation and an incoming voice message. Telegram downloads a selected recent voice note from the verified Telegram chat. WhatsApp uses an accessible audio attachment or a voice file you explicitly shared into the companion. If WhatsApp did not expose audio, share that voice message from WhatsApp to **Voice Relay AIUI** on the phone, select its conversation and refresh the inbox.
- **Reply → Record a voice reply:** record on the glasses, tap Stop, review the recording, choose **Send as voice note** or **Send as audio file**, then confirm the recipient. Voice recordings stay audio. OGG recordings are limited to 60 seconds; the WAV fallback is limited to 20 seconds.
- **Reply → Dictate a text note:** available if the glasses expose speech recognition. Review the full text before confirming Send. This does not require an OpenAI key or generate an automatic AI answer.
- Telegram sends voice notes, audio files or text to an explicitly verified chat ID. WhatsApp direct replies depend on its notification actions. When direct delivery is unavailable, choose **Finish reply on phone**, select the recipient in WhatsApp, and send there. A phone handoff is never labeled as confirmed delivery.

## Notification settings

Open **Settings** from the glasses inbox, or use the switches in the phone app. Changes save immediately.

| Switch | When enabled |
| --- | --- |
| Telegram notifications | Capture/show Telegram conversations |
| WhatsApp notifications | Capture/show WhatsApp and WhatsApp Business conversations |
| Hide alerts while phone is unlocked | Quiet automatic glasses alerts while the phone is unlocked |
| Respect Do Not Disturb | Quiet automatic alerts while Android DND is active |
| Respect phone Silent mode | Quiet automatic alerts while the phone is in Silent mode |
| Nexus popups when AIUI is closed | Allow optional Nexus background popups when the foreground Link page is not active |

Turn **Respect Do Not Disturb** off to ignore DND for these app alerts. Unlocked/DND/Silent filters quiet automatic notices; saved messages remain available for manual inbox use. Turning Telegram or WhatsApp off hides that app and stops new capture. A closed AIUI page is not promised to wake automatically; background Nexus popups require Nexus and its plugin permissions.

## If something fails

- **You see `crypto is not defined` before VOICE LINK 1.0.0 appears:** confirm you opened **Voice Relay Link**, not Voice Relay, and that Hi Rokid finished the latest resource sync. The new bundled source contains no `crypto` or Bluetooth calls. If the correct new package still cannot render its opening screen, send a screenshot and the Hi Rokid/glasses versions; that points to package-loading or host-runtime behavior outside the phone connection.
- **Phone not reachable:** verify both devices share the same network, the phone link is running, and the current phone address matches the exported setup. Guest Wi-Fi may isolate devices; test with the phone hotspot. If the phone address changed, export and sync a new ZIP. No Bluetooth re-pairing is required.
- **Phone setup needed:** a generic template was imported. Use the ZIP generated by **Export glasses setup ZIP** in your phone app.
- **QuickJS/runtime error after opening:** use **Check runtime** and **Connection details**. The latter shows the failed step, address, runtime identification and complete last error in short pages. The phone's Connection details has a Copy button; it excludes the private key.
- **Recorder/playback unavailable:** Check runtime reports the capabilities exposed by the host. Microphone, audio playback and optional text recognition still require Rokid support and microphone permission. A network connection cannot add an absent native audio capability.
- **Lost connection during sending:** reconnect and check the saved receipt and actual chat. The app never automatically resends an uncertain message.

Do not uninstall the companion, clear Telegram data, reset all Bluetooth settings, or unpair Hi Rokid to address these connection errors.

## Implementation and validation

The bridge listens locally on TCP port 8766. HTTP transports authenticated AES-256-GCM-SIV envelopes, not message/audio plaintext. A 256-bit key is generated on Android and transferred only in the setup ZIP; it is not sent in requests. Each session derives a fresh key using HMAC-SHA256 and a fresh Android-generated 256-bit salt. Direction-separated nonces, authenticated request identifiers and monotonically increasing sequence numbers reject modified or replayed requests. Request sizes, open challenges and concurrent sockets are bounded. Unauthenticated endpoints return only protocol status/session challenges. No hosted relay or subscription is required.

The Java network server is tested with the real AIUI client logic over loopback sockets, including authentication, Unicode inbox data, all six preferences, multi-chunk audio upload/download, checksum verification, tampering, replay, cancellation, oversized requests and uncertain-send recovery. The shipped page is also executed in the QuickJS engine with Web Crypto and Bluetooth absent. The legacy behavior suite remains as a regression check. These tests do not use real Telegram/WhatsApp accounts and do not prove physical Rokid rendering, microphone or Bluetooth/Wi-Fi hardware behavior.

The build verifies the Android application ID, launcher and retained signing certificate. The APK contains the exact generic Link page used by the phone ZIP exporter. Source: [RokidVoiceRelay development branch](https://github.com/Paradise-My-Colors/RokidVoiceRelay/tree/codex/aiui-voice-relay-0.9).

Developer build: `npm ci --prefix tools/link-build`, `node tools/link-build/build.mjs`, then the existing Android build. The JavaScript AES implementation is bundled from [@noble/ciphers 1.3.0](https://github.com/paulmillr/noble-ciphers/tree/1.3.0) with its MIT license. The phone uses Bouncy Castle 1.81 for [AES-GCM-SIV](https://www.rfc-editor.org/rfc/rfc8452). Each encrypted response is bound to the exact request hash; repeated handshake responses cannot authenticate a different new request. The SIV mode also protects confidentiality if a network attacker replays an old challenge.
