# Voice Relay AIUI — v0.9.1 test build

Built from the final v0.8 Nexus plugin, with an AIUI glasses interface and an Android Bluetooth companion. This is a device-test build: Android compilation and automated behavior checks are covered; real glasses, pairing, playback and live messaging still need device verification.

## Update from AIUI v0.9: crypto and pairing fixes

Update **both** the Android APK and the AIUI project. This update uses the same app ID and explicitly selected signing certificate as the delivered AIUI v0.9 APK, so install it over **Voice Relay AIUI**. Keep the app installed and keep its data to retain your Telegram login and settings. It still installs separately from the older Nexus-only v0.8 app.

1. Close Voice Relay on the glasses. On the phone, install `RokidVoiceRelay-v0.9.1-AIUI-test.apk` as an update to Voice Relay AIUI.
2. Extract `VoiceRelay-AIUI-v0.9.1.zip`. Replace the existing AIUI project's files with the contents of the extracted `voice-relay` folder, including the new `lib/runtime.js`. Use **Build & Review → Package AIX**, then update the glasses resource package in Hi Rokid. Open Voice Relay and check that its first screen says **Voice Relay 0.9.1**.
3. Keep the phone unlocked and near the glasses. Open Voice Relay AIUI on the phone. Tap **Restart Bluetooth bridge**, wait for **Bridge ready**, then tap **3. Pair glasses (first time)**.
4. On the glasses, tap **Connect to phone once** and leave the page open. It should change from finding/connecting to the phone-approval instruction.
5. On the phone, tap **Approve glasses**. Confirm any Android Bluetooth pairing prompt on either device. Approval completes automatically after bonding. The glasses should continue into the inbox without a second Connect tap. Already-approved glasses can go straight to the inbox.

Back cancels a connection attempt. The app retries a failed initial Bluetooth connection once with a fresh scan, but never retries sending a message automatically. If status 8 remains, exit the glasses app, use Restart Bluetooth bridge, and retry the sequence once. If it still fails, record the exact status at the top of the phone companion and the glasses error. Do not reset all Bluetooth settings or unpair Hi Rokid as a first step.

The old `crypto is not defined` fault came from assuming a browser global existed on the glasses. Version 0.9.1 uses a portable SHA-256 implementation for transfer checksums and persistent counters for send-operation IDs; these IDs are not security keys. Bluetooth bonding and encrypted data characteristics still protect message/audio transfers. A separate public characteristic exposes only six bytes of protocol and approval status, allowing setup to finish before sensitive commands are attempted. Native callback/cleanup errors are contained, and old connection attempts cannot overwrite a new session.

Status 8 during connection is Android's connection-timeout status; it does not identify one unique cause. The changed pairing sequence addresses an app-side timing issue but still needs confirmation on your phone/glasses. [Android Bluetooth status definitions](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/stack/include/gatt_api.h).

## Install the two parts

1. **On the Android phone:** install `RokidVoiceRelay-v0.9.1-AIUI-test.apk` as the separate **Voice Relay AIUI** companion. Keep v0.8 installed if you want to return to it. The new app has its own package ID and storage; it does not replace v0.8 or inherit its Telegram session.
2. Open **Voice Relay AIUI** on the phone. Tap **1. Enable notification access** and enable **Voice Relay AIUI** in Android's list. Disable access for the older **Voice Relay** to avoid duplicate alerts. Return and tap **2. Start Bluetooth bridge**. Allow Nearby devices and notifications. Leave Bluetooth enabled. If using background Nexus popups, approve the new **Voice Relay AIUI** plugin in Nexus with Surfaces and Microphone access and disable the older Voice Relay plugin/stock relay for these notifications.
3. Open **Telegram setup / login**, enter your own Telegram API credentials and complete login on the phone using the same Telegram account that receives the notifications. This new companion needs its own session even when v0.8 is already connected. These credentials are not included in the project.
4. **On a computer:** extract `VoiceRelay-AIUI-v0.9.1.zip`. Open [AIUI Studio Global](https://aiui-global.rokid.com/), sign in to your Rokid account, choose **Local import**, and select the extracted `voice-relay` folder containing `app.json`. Alternatively, import the `aiui/voice-relay` subdirectory from the repository's `codex/aiui-voice-relay-0.9` branch. Do not import the Android repository root.
5. In Studio, use **Build & Review → Package AIX**. In the Hi Rokid phone app, use **Settings → Developer → Update glasses resource package**. Use the same Rokid account in both places and wait for the successful download message. Say **“Hi Rokid, open Voice Relay.”** This follows [Rokid's official device-debugging workflow](https://github.com/jsar-project/AIUI/blob/main/documentation/0-guide/quickstart/quickstart.en-US.md). Store publication is separate and is not required for this development workflow.
6. In the phone companion, tap **3. Pair glasses (first time)**. Within three minutes, choose **Connect to phone** in the glasses app and leave the page open. Tap **Approve glasses** in the phone companion and confirm any Android Bluetooth pairing prompt. Approval completes automatically and the glasses continue into the inbox. Pairing and approval are needed once per glasses device.

The phone must support Bluetooth LE peripheral advertising. Android 11 or later is required. The glasses runtime must expose AIUI Bluetooth, microphone capture and AudioPlayer. The phone needs Internet for Telegram and WhatsApp; the glasses-to-phone bridge uses Bluetooth and has no IP address to configure.

If the earlier Hi Rokid resource-sync problem remains, complete that platform step first. Installing the companion cannot repair a missing Developer menu, account mismatch or a resource package that never reaches the glasses. A browser preview alone cannot verify this phone/glasses connection.

## Everyday use

Receive a new Telegram or WhatsApp notification after enabling notification access. Open Voice Relay, connect, and select the conversation. Swipe to select; tap to act; Back returns or discards.

- **Listen:** select a received voice note, then play it through the glasses. Telegram lists up to ten voice notes found in recent chat history. It verifies the chat ID before downloading. Expiring/self-destructing notes are excluded.
- **Reply → Record a voice reply:** tap to stop, listen to the recording if needed, choose **Send as voice note** or **Send as audio file**, and confirm the recipient before sending. Voice recordings stay audio; they are not transcribed. OGG recording is limited to 60 seconds; the WAV fallback is limited to 20 seconds. Large files take longer over Bluetooth.
- **Reply → Dictate a text note:** available when the glasses runtime supports speech recognition. Review the text, including **Read full text** for longer notes, then confirm. The runtime provides dictation; this app does not require an OpenAI key or generate automatic answers.
- **Read full message:** pages through the captured notification text, which is limited to 500 characters. The inbox is a saved conversation list, not a full messenger history.

## Telegram and WhatsApp behavior

| Action | Telegram | WhatsApp / WhatsApp Business |
| --- | --- | --- |
| Capture notifications | Supported when enabled and notification access is granted | Supported when enabled and notification access is granted |
| Listen on the glasses | Downloads a selected voice note through the logged-in Telegram client | Uses accessible notification audio; otherwise share the chosen voice message from WhatsApp to **Voice Relay AIUI** on the phone and select the matching conversation |
| Send a voice note | Native Telegram voice note after confirmation | Direct audio reply only when WhatsApp exposes a compatible notification action; otherwise finish through the phone share screen |
| Send an audio file | Telegram document containing the recording | Uses the supported audio reply/share route; WhatsApp controls whether it appears as a voice note or audio attachment |
| Send a text note | Telegram message | Exact notification reply action when available; otherwise phone share screen |

For a WhatsApp handoff, open the phone companion, tap **Finish reply on phone**, select the intended WhatsApp conversation and press Send there. Incoming WhatsApp sharing requires a saved notification from that conversation first. A **Passed to WhatsApp** receipt means WhatsApp accepted the handoff, not confirmed delivery. Voice-note versus attachment presentation cannot be forced for personal WhatsApp accounts through these interfaces.

Use the official Telegram Android app for verified AIUI routing. Telegram X and notifications without a valid chat shortcut can appear in the inbox, but listening/sending is refused if the exact chat cannot be verified. In group notifications, the captured title must match the Telegram chat. No recipient is guessed from a contact name.

## Settings on the glasses and phone

Open **Settings** from the inbox or a conversation. Changes are stored on the phone and apply to the companion's notification handling too.

| Setting | ON | OFF |
| --- | --- | --- |
| Telegram notifications | Capture and show Telegram conversations | Stop new Telegram capture and hide Telegram from the AIUI inbox |
| WhatsApp notifications | Capture and show WhatsApp conversations | Stop new WhatsApp capture and hide WhatsApp from the AIUI inbox |
| Hide when phone is unlocked | Suppress automatic alerts while the phone screen is on and unlocked | Allow alerts in that state |
| Respect Do Not Disturb | Suppress automatic alerts whenever Android DND is active, or its state cannot be read | Ignore DND in Voice Relay's alert filter |
| Respect Silent mode | Suppress automatic alerts when the phone ringer is Silent | Ignore ringer Silent mode in Voice Relay's alert filter |
| Nexus alerts when AIUI is closed | Use the existing Nexus plugin for background popups when available | Disable those popups |

DND and Silent are separate. To allow alerts while both are active, switch both filters off. This does not change Android's DND setting, system volume, Bluetooth routing or operating-system notification restrictions. Quiet settings suppress automatic alerts; you can still open saved messages and deliberately play audio. Disabling an app keeps its already-saved entries on the phone; enabling it makes them available again.

The AIUI page refreshes alert status while open. It does not promise to wake itself when closed. Keep Nexus enabled if you want the existing background HUD popups. Active AIUI use suppresses competing Nexus popups.

## Recovery

- **Phone not found:** keep the phone nearby; open the companion, start the bridge and retry Connect. Check Bluetooth and Nearby devices permission. If Android stops the bridge, use **Android battery settings** to allow it to keep running, then restart it. The service is not automatically restarted at phone boot.
- **Pairing/approval fails:** use **Restart Bluetooth bridge**, then Pair glasses to reopen the three-minute approval window. Connect once on the glasses, keep the page open and approve on the phone. If necessary, use **Forget approved glasses** on the phone and **Choose a different phone** in AIUI, then repeat app approval.
- **No conversations:** receive a fresh notification after granting access. Turn on the corresponding app toggle and use Refresh inbox. A muted chat that never posts a phone notification will not enter this notification-based inbox automatically.
- **Telegram recipient cannot be verified:** check the companion's Telegram account and receive a fresh notification from the official Telegram app. The app stops instead of choosing a chat by name.
- **No Telegram voice notes:** open the chat in Telegram, let it load, receive a fresh voice note and retry Listen. This build searches recent history, not an entire chat archive.
- **WhatsApp audio unavailable:** share that particular voice note from WhatsApp to Voice Relay on the phone, select its conversation, then refresh the glasses inbox.
- **Connection lost while sending:** reconnect and check the stored receipt. The app never automatically repeats an uncertain send. Check the actual chat before choosing **I checked the chat on my phone** or composing another reply.
- **Microphone or playback unavailable:** allow the requested microphone permission and use a glasses runtime exposing the documented media APIs. No phone-loudspeaker fallback is used by the AIUI player. Optional text dictation may be unavailable on some runtimes.
- **An imported/shared file expired:** share or record it again. Bridge audio is stored privately on the phone; files older than seven days are cleaned when the bridge starts. Telegram's own cache/session are managed separately by TDLib.

## Validation and source

The automated checks cover source structure, navigation, setting writes, recipient preservation, explicit confirmation, recording cancellation, late callbacks, interrupted-send recovery, text review, fragmented Unicode Bluetooth replies and audio checksums at small/large simulated MTUs. They use runtime mocks; they do not prove device rendering or live service compatibility.

The build compiles the Android APK and verifies its signature, independent application ID and launcher activity. The original upgrade check found that published v0.8 was signed with a different key from the retained development key. This build therefore installs separately as `com.paradisemc.rokid.aiui.voicerelay`, uses Nexus plugin ID `voicerelayaiui`, and explicitly selects the retained signer for future AIUI updates. It does not require deleting the older app or its data.

The branch is [codex/aiui-voice-relay-0.9](https://github.com/Paradise-My-Colors/RokidVoiceRelay/tree/codex/aiui-voice-relay-0.9). Source and build history are retained there. No APK installation, real account messaging or glasses deployment was performed during automated verification. To return to v0.8, stop this app's bridge, disable its notification access/Nexus plugin and re-enable the old app's access/plugin.

Before relying on the build, verify one short incoming voice note and one confirmed reply per messaging app on your devices. Check the unlocked-phone and DND toggles in both positions. WhatsApp handoffs and AIUI resource sync must be confirmed on the actual phone/glasses.
