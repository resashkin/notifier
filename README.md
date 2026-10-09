# Notifier

Forward your Android phone's notifications to a Telegram bot, so you can read them anywhere — for example on an iPhone, with
the Android phone left in a drawer.

- **One Telegram message per notification.** Chat apps' "3 new messages" bundles are split into separate messages.
- **Controlled from Telegram.** Mute an app with a button under its message, `/pause` forwarding, or open a settings
  Mini App with per-app switches.
- **No server.** The phone talks to the Telegram Bot API directly; the bot token never leaves the phone.

```
Android phone                                   Telegram
┌──────────────────────────────┐   sendMessage   ┌──────────────┐
│ ForwarderService             │ ──────────────▶ │  your bot    │ ──▶ you (any device)
│ (NotificationListenerService)│                 │   chat       │
│ BotController (getUpdates)   │ ◀────────────── │              │ ◀── buttons, /commands,
└──────────────────────────────┘  taps, commands └──────────────┘     Mini App "Save"
```

## Parts

| Folder | What | Stack |
|---|---|---|
| [`android/`](android) | The forwarder app. Reads notifications, sends them to the bot, obeys bot commands. | Kotlin, no dependencies |
| [`miniapp/`](miniapp) | Settings Mini App opened from the bot (`/settings`). Hosted on GitHub Pages. | Flutter web |
| [`panel/`](panel) | Optional Mac web panel that reads a USB/Wi-Fi-connected phone over `adb`. Handy for debugging. | Python stdlib + HTML |

## Setup

### 1. Create a bot

Talk to [@BotFather](https://t.me/BotFather), `/newbot`, and copy the token.

### 2. Build and install the Android app

Requires the Android SDK and JDK 17+ (Android Studio's bundled JDK works).

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open `android/` in Android Studio and run it. Minimum Android version: 10.

### 3. Configure it on the phone

1. Open **Notifier** → **Grant notification access** → enable Notifier.
   On Android 13+, sideloaded apps need *App info → ⋮ → Allow restricted settings* first.
2. **Allow running in background** (otherwise battery optimization may stop it).
3. Paste the bot token, send `/start` to your bot, tap **Detect** (fills the chat ID), then **Send test**.
4. Turn on **Forward notifications to Telegram**.

USB debugging is not needed afterwards — you can turn developer options off again (some banking apps check for it).

## Using it from Telegram

- Under each forwarded message: **🔕 Mute <app>** (one tap to undo) and **⏸ Pause 1h**.
- `/pause [minutes]`, `/resume`, `/muted`, `/status` (includes phone health).
- `/settings` → **⚙️ Settings** button → Mini App with per-app switches, pause and rules.

Only the chat ID configured on the phone can control it.

## Phone health

`/status` and the Mini App's **Phone** section show battery level, temperature, voltage and health, charging state
(AC/USB/wireless, current, time to full), Wi-Fi (signal, band, link speed, IP), mobile network (operator, signal, data,
roaming), internet reachability and uptime. Read on demand — nothing runs in the background.

Two fields need optional permissions, requested by **Show Wi-Fi name & mobile network type** in the app:
the Wi-Fi name (location, *Allow all the time*, since `/status` is answered in the background) and the mobile network
type (phone state). Everything else works without them.

## How the Mini App works without a server

1. `/settings`: the phone replies with a keyboard button whose link carries the current state
   (`?s=` = gzip + base64url JSON: rules, pause, apps seen with their mute state).
2. The Mini App (static page on GitHub Pages) shows it; **Save** calls `Telegram.WebApp.sendData` with only the
   changes (≤ 4 KB).
3. The phone receives them as a `web_app_data` message via its `getUpdates` polling and applies them.

The hosted page holds no data. To use your own deployment, enable GitHub Pages (*Settings → Pages → Source: GitHub Actions*)
on your fork — [`.github/workflows/miniapp.yml`](.github/workflows/miniapp.yml) builds and publishes `miniapp/` — and put
its URL in the Android app.

## Notes

- **Notification loops:** if the bot's chat is also open in Telegram on the same phone, its messages would be forwarded
  again. Notifications from the bot's own chat and repeats of recently sent text are skipped.
- **Android 15+** hides one-time codes (OTP) from third-party notification listeners.
- **Emulators/containers** (e.g. redroid) work for most apps, but banking apps typically refuse them (Play Integrity,
  screen-capture protection), which is why this project targets a real phone.

## License

[MIT](LICENSE)
