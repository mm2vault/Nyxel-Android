# Nyxel Android 2.0

- Application ID: `com.nyxel.music`
- Version: `2.0.0` (versionCode 1)
- Minimum Android: 8.0 / API 26
- UI: original Nyxel HTML/CSS character and player UI, packaged locally in Android WebView.
- Local music: MediaStore library scan + Media3 ExoPlayer.
- Background playback: MediaSessionService with Android media notification and lock-screen controls.
- YouTube links: official embedded player while app is foregrounded; no audio extraction or background-play workaround.

## Build
Push to main or manually run **Actions → Build Nyxel APK**. The workflow builds a debug APK and uploads it as an artifact. Debug APK is for testing, not production distribution.

## Signing and updates
For install-over updates, keep the same application ID and sign every release with the same release keystore. This first setup deliberately does not invent or generate a production signing key. A release keystore and in-app updater are later tasks.
