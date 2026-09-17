# Voice Relay AIUI

Standalone AIUI project for the v0.9 Voice Relay Android companion. Import **this folder**, containing `app.json`, into [AIUI Studio Global](https://aiui-global.rokid.com/). No npm install is needed. Use **Build & Review → Package AIX**, then update the glasses resource package from Hi Rokid's Developer settings.

Install the matching **Voice Relay AIUI** Android companion alongside v0.8, enable its notification access, complete its own Telegram login, start its Bluetooth bridge, pair the glasses and approve them on the phone. Disable the older app's notification access to avoid duplicate alerts. Select **Connect to phone** in this agent. Full installation and recovery steps are in `SETUP.md` in the downloadable project.

The page provides an inbox, voice playback, confirmed voice/audio-file/text replies, and Telegram, WhatsApp, unlocked-phone, DND, Silent and Nexus alert controls. Use swipe, tap and Back. Voice capture is not transcribed; dictation is an explicit alternative.

Telegram requires the companion's Telegram login and a verified notification chat ID. WhatsApp media access and direct audio replies depend on the installed app's exposed Android interfaces; otherwise import the incoming voice note by sharing it to the companion and finish outgoing replies on the phone. AIUI is a foreground interface; optional Nexus popups provide existing background alerts.

This is a test build. Source/behavior checks and Android compilation do not replace actual glasses rendering, pairing, recording, playback and live messaging checks. See [complete setup](https://github.com/Paradise-My-Colors/RokidVoiceRelay/blob/codex/aiui-voice-relay-0.9/AIUI_SETUP.md).
