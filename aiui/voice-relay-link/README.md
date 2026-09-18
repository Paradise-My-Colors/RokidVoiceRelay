# Voice Relay Link 1.1.0

Use the ZIP exported by the updated phone app: **Export glasses setup ZIP**. It already contains your connection settings. Keep that personal ZIP private.

Extract it and import its **voice-relay-link** folder into AIUI Studio as a new project named **Voice Relay Link**. Select **Build & Review → Package AIX**, then Hi Rokid **Settings → Developer → Update glasses resource package**. Open **Voice Relay Link**, not the old Voice Relay agent. The opening screen must say **VOICE LINK 1.1.0**.

Phone and glasses must share Wi-Fi, or connect the glasses to the phone hotspot. Start **Phone link** in the phone app, then choose **Connect to phone** on the glasses. There is no Bluetooth Pair/Approve step. If the phone address changes, export and sync a fresh setup ZIP. Login and notification preferences are retained by an in-place APK update.

The generic developer template has no connection key. It can show its opening screen and Check runtime, but connect requires a phone-exported package. All operational code is bundled into pages/link/home.ink to prevent mixing older module files with this version.

Telegram supports voice playback, native voice/audio-file/text replies to verified recipients. WhatsApp audio requires an accessible attachment or a manual share into the companion; replies may require Finish reply on phone. Settings include unlocked-phone alerts, DND, Silent, Telegram and WhatsApp toggles. Every send requires explicit confirmation.

Messages and audio travel in authenticated AES-256-GCM-SIV envelopes. Only an app with the phone-generated setup key can open a session. Session keys are derived from a fresh Android-generated random salt; requests have directional nonces and replay protection. No cloud relay is used. AIUI Studio is used only to distribute your glasses app as before.

Physical device compatibility still needs a test. If an error appears before this new version label, verify that you opened Voice Relay Link and that Hi Rokid finished syncing its package. Use Check runtime and Connection details for version/capability and network errors.
