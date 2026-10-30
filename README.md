# Nyxel Android

- Application ID: `com.nyxel.music`
- Android minimum: 8.0 / API 26
- UI: original Nyxel HTML/CSS character and player UI packaged locally in Android WebView.
- Local music: MediaStore library scan + Media3 ExoPlayer.
- Background playback: MediaSessionService with Android media notification and lock-screen controls.
- Dynamic Island: user-enabled floating overlay with playback controls.
- Updates: checks the latest GitHub Release, downloads `Nyxel.apk` with progress, verifies the APK package ID, then opens Android's package installer.

## Direct APK releases

Every push to `main` that changes app code or the build workflow triggers **Build and Publish Nyxel APK**. The workflow builds a signed release APK and publishes the raw `Nyxel.apk` file directly under GitHub **Releases**. It also keeps a short-lived Actions artifact for build inspection.

## One-time signing setup

In GitHub, open **Settings → Secrets and variables → Actions → New repository secret** and add:

- `NYXEL_KEYSTORE_BASE64`: the release keystore file encoded as Base64.
- `NYXEL_KEYSTORE_PASSWORD`: keystore password.
- `NYXEL_KEY_ALIAS`: key alias.
- `NYXEL_KEY_PASSWORD`: key password.

The keystore must be the one intended for Nyxel. Do not rotate or replace it after distributing an installable APK: Android requires the same signing certificate for in-place updates. Keep these values private; never commit the keystore or passwords to this repository.

## Versioning and installation

The workflow assigns an increasing `versionCode` to each release. The in-app updater compares that code with the installed build, downloads the newest release asset, and hands it to Android for the user's final install confirmation. Android may ask once for permission to install unknown apps from Nyxel.

The application ID remains `com.nyxel.music`; changing it would make Android treat the app as a separate installation.


## Nyxel AI

Nyxel AI uses a provider-independent architecture so cloud and on-device models can be switched without rewriting the app.

- OpenRouter chat API adapter with a user-supplied key encrypted by Android Keystore.
- Planned local inference through Google's LiteRT-LM Kotlin API.
- No API key or model weights are bundled into the APK.
- See [AI architecture](docs/AI_ARCHITECTURE.md) for provider strategy, model candidates and implementation status.

Cloud free tiers may be rate-limited or change. Local model speed depends on the phone hardware.
