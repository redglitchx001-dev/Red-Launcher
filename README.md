# Red Launcher

**Red Launcher** is a Minecraft: Java Edition launcher for Android, based on
[Zalith Launcher 2](https://github.com/ZalithLauncher/ZalithLauncher2).

© 2026 RedGlitchX — creator & maintainer

- Project: <https://github.com/redglitchx001-dev/Red-Launcher>
- License: GPL-3.0 (see `LICENSE` and `NOTICE`)

---

## Install

Latest Debug build (arm64), re-uploaded on every successful build:

<https://github.com/redglitchx001-dev/Red-Launcher/releases/download/apk-latest/RedLauncherBeta-Debug-arm64.apk>

> The APK file name keeps the internal module name `RedLauncherBeta` on
> purpose (the module and Java package names are not renamed) — the app is
> **Red Launcher**.

Installing over a previous version works as long as the signing key matches.
If you get an install error, uninstall the old version first.

---

## Accounts

Open **Accounts → Add Account**. Three ways to play:

### 1. Offline account (no login, works out of the box)

Pick **Offline**, enter a username (3–16 letters/digits/`_`), optionally a
UUID (advanced), and confirm. A skin can be set afterwards with **Change
Skin** on the account. This creates a local account that authenticates
through the launcher's built-in offline server.

### 2. Auth server (ely.by, LittleSkin, …)

Pick **Add Auth**, paste the server URL (e.g. your ely.by / LittleSkin
server), save it, then log in with the account's email/username + password.
Skin and name from the server are used automatically.

### 3. Microsoft account

Microsoft login requires the launcher build to be configured with an OAuth
client id. See **Microsoft Login Setup** below. Without it, Microsoft login
fails with a clear "OAuth client id not configured" error, and options 1 and
2 keep working.

---

## Microsoft Login Setup (for the builder)

This is a one-time setup so that the build can authenticate against
Microsoft/Xbox Live.

1. Go to <https://portal.azure.com> → **App registrations** →
   **New registration**.
2. Name: `Red Launcher`. Supported account types: personal accounts
   (consumers). Registered platform: **Mobile & desktop application** (public
   client). No redirect URI is needed — the launcher uses the OAuth **device
   code flow**.
3. Copy the **Application (client) ID**.
4. In this GitHub repository: **Settings → Secrets and variables → Actions**
   → add a new secret named `OAUTH_CLIENT_ID` with the client id as value.

   ⚠️ **Do not put the client id into `gradle.properties`** — this repository
   is public. The commented line `#oauth_client_id=xxx` in
   `RedLauncherBeta/gradle.properties` stays commented; the value is read
   from the Actions secret at build time.
5. Rebuild the APK (Actions → **APK URL** → Run workflow → Debug) so the
   client id is embedded.

The requested OAuth scopes are `XboxLive.signin`, `offline_access`
(`openid`, `profile`, `email` are also requested for the sign-in flow).

---

## Discord Rich Presence

Settings → **Discord**:

- Paste your Discord user token (in-app instructions use the Bluecord app)
  and **Save & Connect**. The token is stored **encrypted on this device
  only** (EncryptedSharedPreferences) and never uploaded anywhere else.
- **Use URL assets** (on by default): the logo and your skin face are shown
  via public URLs, which works for everyone. If you own the Discord
  application used by the launcher, you can instead upload the logo as the
  art asset `red` and switch the toggle off — see `docs/discord/README.md`.
- Leaving the app stops the menu presence; while a game is running, the game
  presence (server, player count, play time) stays up.

> Using a user token for presence is against Discord's Terms of Service and
> can get the account banned. Use at your own risk.

---

## Building

Builds run **only** on GitHub Actions (local builds are not supported for
release):

```
./gradlew RedLauncherBeta:assembleDebug -Darch=arm64
```

- **APK URL** workflow: builds a Debug (or Release) arm64 APK and publishes
  it as the `apk-latest` GitHub release (permanent install link above), with
  a temporary-host fallback.
- Version lives in `RedLauncherBeta/gradle.properties`
  (`launcher_version_code` / `launcher_version_name`).

The Java/Kotlin package `com.movtery.zalithlauncher` and the `RedLauncherBeta`
module name are kept unchanged on purpose (JNI native symbols are bound to
them).

---

## License & attribution

Red Launcher is free software, licensed under **GPL-3.0**.

It is derived from **Zalith Launcher 2** (Copyright (C) 2025 MovTery and
contributors, GPL-3.0); its copyright and license notices are preserved in
the source tree and in `NOTICE`. Additional third-party code is listed in
`NOTICE` and in the in-app **About** screen (Acknowledgements / Libraries).
