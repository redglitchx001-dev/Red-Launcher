# Discord Rich Presence — logo asset (one-time setup)

Red Launcher shows its presence on Discord using the Discord application
**`1543238280496156714`** (the app id is built into the launcher).

By default the launcher uses **public URLs** for the images, so nothing needs
to be done:

| Image | Source |
|---|---|
| large image (logo) | `https://raw.githubusercontent.com/redglitchx001-dev/Red-Launcher/main/RedLauncherBeta/src/main/res/drawable/img_launcher.png` |
| small image (skin face) | `https://mc-heads.net/avatar/{account-uuid}` |

**Why the asset upload exists at all:** Discord *art assets* are sharper and
more reliable than external URLs (they are cached by Discord). If you are the
**owner of application `1543238280496156714`** in the Discord Developer
Portal, upload the logo once and switch the in-app toggle
*Settings → Discord → "Use URL assets"* **off**.

> ⚠️ Only the owner of the application can upload art assets (anyone else
> gets a 403). That is why the launcher no longer tries to upload anything
> with the user's token — the old "skin face upload" code was removed for
> exactly this reason (it also failed for every non-owner and sent the
> user's token to an application-management endpoint).

## Steps (one time)

1. Open <https://discord.com/developers/applications> and select the
   application with ID `1543238280496156714`.
2. In the left menu open **Rich Presence → Art Assets**.
3. Upload `red.png` (this folder, 1024×1024 — the logo square, no crop) and
   name it exactly **`red`**.
4. (Optional) the old asset name `skinface` is **no longer used** — the skin
   face is always fetched from `https://mc-heads.net/avatar/{uuid}`. A
   leftover `skinface` asset can be deleted.
5. In the app: **Settings → Discord → "Use URL assets" → off**, then
   **Save & Connect** again.

## Notes

- Art asset rules (Discord): square, PNG/JPG, at least 32×32, up to 1024×1024,
  max 1 MB. `red.png` here is 1024×1024 PNG.
- The small image (skin face) intentionally stays an external URL even when
  URL assets are switched off: it depends on the account and can never be a
  static asset.
- Do not attempt to upload assets from the app with a user token — see the
  warning above.
