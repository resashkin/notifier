# Notifier settings Mini App

Flutter web app opened from the bot with `/settings`. See the [project README](../README.md#how-the-mini-app-works-without-a-server)
for how it exchanges data with the phone.

- `lib/model.dart` — state decoding (`?s=`) and the changes sent back. Keep in sync with
  `android/.../MiniAppState.kt`.
- `lib/telegram.dart` — minimal `dart:js_interop` wrapper over `Telegram.WebApp`.
- `lib/main.dart` — the settings screen, themed with Telegram's colors.

## Run locally

Uses the Flutter version pinned in `.fvmrc` ([fvm](https://fvm.app)).

```bash
fvm flutter run -d chrome
```

Outside Telegram it shows demo data, and **Save** shows the JSON it would send.

## Deploy

Pushing to `main` publishes it to GitHub Pages via `.github/workflows/miniapp.yml`.
