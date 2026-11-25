# LOKA-V2-VAMAX — Stage 1

An Android app that turns your phone and a VR viewer into a mixed reality headset:
camera passthrough → one picture per eye → lens distortion → ARCore tracking → windows fixed in the room → gaze control (gaze + dwell).

## Get the APK (no Android Studio needed)
1. Create a new GitHub repository and upload everything in this folder, including `.github`.
2. Open the **Actions** tab → **Build APK** → wait about 4 minutes.
3. Download the artifact named **LokaMR-apk**, unzip it, and you will find `app-debug.apk`.
4. Install it on your phone (allow installs from unknown sources).

## Running it
- Your phone must support ARCore. If "Google Play Services for AR" is missing, the app asks you to install it.
- Put the phone in the viewer and move your head slowly for 3–4 seconds until the windows appear.
- Look at any tile on the panel for **1.2 seconds** to activate it.

| Tile | What it does |
|---|---|
| Recenter | Brings the windows back in front of you |
| IPD (eye distance) | 58 / 60 / 63 / 66 / 69 mm |
| Lens | Mild / Standard / Strong (distortion strength) |
| Browser, YouTube, Voice | Coming in the next stages |

Volume keys: **Up** = recenter, **long-press Up** = eye distance, **Down** = lens.

## Files
- `StereoRenderer.kt`: the core: camera, windows, both eyes and distortion
- `Ui.kt`: the panels (launcher and status)
- `Tuning.kt`: values you can adjust
- `MainActivity.kt`: ARCore session, permissions and keys
