# Notifier

Forward your Android phone's notifications to a Telegram bot, so you can read them anywhere — for example on an iPhone, with
the Android phone left in a drawer.

- **One Telegram message per notification.** Chat apps' "3 new messages" bundles are split into separate messages.
- **Smart mute and rewrite.** Rules by app, chat, words or regex: drop, send silently or keep for a digest a few times
  a day; always forward what matters (codes, family); turn noisy texts into one short line. Edited in a Mini App in
  Telegram or on the phone, with a live preview on your recent notifications.
- **Controlled from Telegram.** `/pause` forwarding, `/rules`, or open the settings Mini App.
- **Nothing lost offline.** Notifications that can't be sent wait in a queue and follow, with their original time,
  once the internet is back — after a message saying how long it was gone.
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

### 2. Install the Android app

Download the APK from [Releases](https://github.com/resashkin/notifier/releases/latest) and open it on the phone
(allow your browser or file manager to install apps when asked). After that the app updates itself — see
[Updates](#updates).

Or build it yourself. Requires the Android SDK and JDK 17+ (Android Studio's bundled JDK works).

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open `android/` in Android Studio and run it. Minimum Android version: 10.

### 3. Configure it on the phone

The app has four tabs at the bottom: **Health**, **Telegram**, **Notifications**, **Settings**.

1. **Health** → **Grant notification access** → enable Notifier.
   On Android 13+, sideloaded apps need *App info → ⋮ → Allow restricted settings* first.
2. **Health** → **Allow running in background** (otherwise battery optimization may stop it).
3. **Telegram**: paste the bot token, send `/start` to your bot, tap **Detect** (fills the chat ID), then **Save & send test**.
4. **Settings**: turn on **Forward notifications to Telegram**.

USB debugging is not needed afterwards — you can turn developer options off again (some banking apps check for it).

## Using it from Telegram

- `/rules` — the rules; `/rule` as a reply to a forwarded message opens the rule editor with that notification.
- `/digest` — send what's waiting for the digest now.
- `/pause [minutes]`, `/resume`, `/status` (includes phone health), `/update`.
- `/settings` → **⚙️ Settings** button → Mini App with rules, per-app switches, digest times, pause and options.

The bot answers in Russian or English, following your Telegram language.

Only the chat ID configured on the phone can control it. **Stop bot** on the app's Telegram tab turns commands and
buttons off; notifications are still forwarded.

## Updates

The app updates itself from this repository's GitHub Releases — no cable, no root:

- It checks once a day (and on `/update` or **Settings → Check for updates**), downloads the new APK, verifies it is
  a newer Notifier and installs it. The bot reports *Installing Notifier 0.2.0…* and then *✅ Notifier updated to 0.2.0*.
- **Android 12+:** silent, from the second update on (the first one, and Play Protect for an unknown app, ask once).
- **Android 10–11:** Android always asks: tap the *Update Notifier* notification, then **Update**.
- Turn **Install updates automatically** off to only get a message, then install with `/update`.
- Allow it once: **Settings → Allow installing updates**.

### Publishing a release

Push a tag; [`android-release.yml`](.github/workflows/android-release.yml) builds the APK (version from the tag) and
creates the release:

```bash
git tag v0.2.0
git push origin v0.2.0
```

Every release must be signed with the same key, or phones refuse the update. The workflow takes it from four repository
secrets: `NOTIFIER_KEYSTORE_BASE64` (`base64 -i release.jks`), `NOTIFIER_KEYSTORE_PASSWORD`, `NOTIFIER_KEY_ALIAS`,
`NOTIFIER_KEY_PASSWORD`. Create a key once with
`keytool -genkeypair -v -keystore release.jks -alias notifier -keyalg RSA -keysize 4096 -validity 36500`
and keep it safe — never commit it.

**Forks:** use your own key and secrets, and set *Releases from GitHub* in the app's Settings tab (or
`DEFAULT_UPDATE_REPO` in `Prefs.kt`) to your repository.

## When the internet drops

- Notifications that can't be sent are queued (on the phone, survives restarts) and listed on the **Notifications**
  tab with **Retry now** / **Discard all**; the tab shows how many are waiting.
- After a minute offline the phone shows a *No internet since …* notification; the **Health** tab shows a banner and
  the list of recent outages.
- When the connection is back the bot posts *⚠️ No internet 14:02 – 14:17 (15 min), 3 notifications were delayed*,
  then sends the queued ones oldest first, silently, each marked with its original time (🕓 14:05).
  If Telegram itself can't be reached, the queue is retried with backoff (30 s up to 15 min).
- Outages under a minute that delayed nothing are ignored.

## Phone health

`/status` and the Mini App's **Phone** section show battery level, temperature, voltage and health, charging state
(AC/USB/wireless, current, time to full), Wi-Fi (signal, band, link speed, IP), mobile network (operator, signal, data,
roaming), internet reachability and uptime. Read on demand — nothing runs in the background.

Two fields need optional permissions, requested by **Show Wi-Fi name & mobile network type** in the app:
the Wi-Fi name (location, *Allow all the time*, since `/status` is answered in the background) and the mobile network
type (phone state). Everything else works without them.

## Rules

Three kinds, checked in this order:

1. **Always forward** — exceptions: a matching notification is forwarded even if a mute rule matches it.
2. **Mute** — **drop** it, send it **silently**, or keep it for the **digest**. If several match, the strictest wins
   (drop, then digest, then silent). Mute rules look at the original text.
3. **Rewrite** — what is sent: cut phrases, a regex and links, or parse the text with a regex
   (`Покупка (?<amount>[\d ]+)р (?<shop>.+?) Баланс`) and fill a template (`💳 −{amount} ₽ · {shop}`). Only the first
   matching rewrite rule applies; if its template regex doesn't match, the cleaned text is sent.

Conditions are combined with AND: app, chat or sender (the notification title, exactly), words or phrases (any of
them), regex. Letter case and ё/е don't matter. The per-app switches are rules too (app → drop).

**Templates** in the Mini App add ready-made rules for common noise: bank ads in SMS (900, VTB, T-Bank) into the
digest, "couldn't reach you" SMS as one line, Sber 900 purchases and Raiffeisen Serbia (Moja mBanka) payments as
`💳 −amount · where`. They're in [`miniapp/assets/presets.json`](miniapp/assets/presets.json) with made-up samples that
both engines are tested on; send a pull request to add yours.

The **digest** goes out at fixed times (09:00, 13:00, 19:00 by default), grouped by app, with sound; an empty digest
isn't sent. The phone and the Mini App run the same engine on the same test cases ([`spec/rules-cases.json`](spec)),
so the Mini App's preview matches what the phone does.

## How the Mini App works without a server

1. `/settings`: the phone replies with a keyboard button whose link carries the current state
   (`?s=` = gzip + base64url JSON: options, pause, apps seen, recent notifications, rule changes).
2. The Mini App (static page on GitHub Pages) shows it; **Save** calls `Telegram.WebApp.sendData` with only the
   changes (≤ 4 KB).
3. The phone receives them as a `web_app_data` message via its `getUpdates` polling and applies them.

Rules soon outgrow a link, so the Mini App keeps a copy in Telegram CloudStorage (per user and bot) and the link only
carries what changed since the copy the Mini App last confirmed. Each save names the revision it started from; edits
made on the phone in the meantime are kept ([`miniapp/lib/sync.dart`](miniapp/lib/sync.dart)).

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
