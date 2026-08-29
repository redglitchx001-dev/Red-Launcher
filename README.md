# Red Launcher Beta

A custom Android launcher for **Minecraft: Java Edition**

- 🎨 Red "Liquid Glass" themed UI with a custom red logo
- 🎮 **Discord Rich Presence** — shows your game state in Discord (skin face, server IP, party size, play time)
- 📦 Built 100% in the cloud with **GitHub Actions** — no local toolchain needed

## 📲 Get the APK (easiest way)

1. Open the [Actions](../../actions) tab in this repository
2. Click **APK URL** → **Run workflow** → keep **Debug** selected
3. After ~9 minutes, open the run and go to the **Summary** tab
4. Copy the download link, open it on your phone, and install

## 🏗️ For developers

- Core launch engine: PojavLauncher JNI (see upstream project)
- App module: `RedLauncherBeta` — **do not rename**
- Java/Kotlin package: `com.movtery.zalithlauncher` — **must not be mass-renamed**: the native (JNI)
  symbols `Java_com_movtery_zalithlauncher_*` in `src/main/jni` must match exactly
- Builds are done only via GitHub Actions (Android NDK is not available in Termux/proot)
- The **Debug** variant is the tested one; Release requires signing secrets in the repo settings

### Key locations

| What | Where |
|------|-------|
| App UI / activities | `RedLauncherBeta/src/main/java/com/movtery/redlauncherbeta/` |
| Discord Rich Presence | `.../feature/discord/` (`DiscordManager`, `DiscordGateway`, `SkinFaceUtil`) |
| Native (JNI / EGL bridges) | `RedLauncherBeta/src/main/jni/` |
| Build workflows | `.github/workflows/` (`apk_url.yml` = one-click APK) |
| Project "bible" (rules & roadmap) | `ultra_bible_prompt.txt` |

## 📜 License

This project is licensed under the **[GPL-3.0 license](LICENSE)**.

Copyright © 2026 **RedGlitchX** — Red Launcher Beta.

Red Launcher Beta is an **unofficial modified version** of [ZalithLauncher2](https://github.com/ZalithLauncher/ZalithLauncher2).
Pursuant to Section 7 of the GPLv3, modified versions must clearly indicate that they are unofficial modified
versions, and the original upstream copyright and license notices are preserved.
