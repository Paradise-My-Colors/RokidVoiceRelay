# Voice Relay AIUI 0.9.2

Standalone AIUI project for the matching Android companion. Import **this folder**, with `app.json` at its root, into [AIUI Studio Global](https://aiui-global.rokid.com/). No npm install is needed. Use **Build & Review → Package AIX**, then Hi Rokid **Settings → Developer → Update glasses resource package**. Confirm the opening screen says **Voice Relay 0.9.2**.

Install the matching 0.9.2 APK over the existing AIUI companion. Start the phone bridge and wait for **Registered: 4 characteristics · Advertising ready**. For recovery after the previous failures, restart the glasses once while leaving the phone bridge running. Tap **Pair glasses** on the phone, then **Connect to phone once** on the glasses. Approve only when the glasses ask for it. Success means **Inbox** on the glasses.

This version retries exact service discovery, enumerates available services, avoids duplicate native disconnect calls, and saves **Connection details** for missing-service and QuickJS failures. It keeps the no-Web-Crypto implementation from 0.9.1. A still-pending native timeout requires restarting the glasses rather than overlapping another connection attempt. An uncertain message send is never retried automatically.

For daily use, leave the phone bridge running, open Voice Relay on the glasses and tap Connect. Pairing is not needed each time. Use **Settings** in the inbox or phone companion for Telegram/WhatsApp, unlocked-phone alerts, DND and Silent mode.

The included `SETUP.md` in the downloadable ZIP has the full instructions. In the repository see `../../AIUI_SETUP.md`. Automated tests use simulated Bluetooth; physical pairing and audio remain device tests.
