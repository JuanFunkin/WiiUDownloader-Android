# WiiUDownloader for Android

Android port of WiiUDownloader. The download, ticket and decryption code is the
same Go core as the desktop app (compiled with `gomobile`); the interface is a
native Kotlin / Jetpack Compose app.

## Features

- Search the Wii U library by name or Title ID; filter by type (games, updates,
  DLC, demos) and region.
- Tap a game and tick **Update** and/or **DLC** to queue them together with it.
- Pick the destination folder yourself (Android's folder picker).
- Decryption is optional (Settings), and so is deleting the encrypted files afterwards.
- Pause, resume and cancel from the app or from the notification. Downloads keep
  running in the background.
- English and Spanish. The app follows the system language; on Android 13+ you can
  also set it per app (Settings → Apps → WiiUDownloader → Language).

## How the folder choice works

Android does not let native code write into an arbitrary folder by path. Each title
is therefore downloaded into the app's own storage first and, once it is finished,
copied into the folder you picked. You need free space for one full title at a time
(more if you decrypt and keep the encrypted files too). Interrupted downloads resume.

## Getting the APK

Every build is done by GitHub Actions (`.github/workflows/android.yml`):

- Push to `main` (or `android-port`) → open **Actions → Android** and download the
  `WiiUDownloader-Android` artifact.
- Push a tag such as `v1.0.0` → the APKs are attached to a GitHub release.
- Or use **Actions → Android → Run workflow**.

There is a single APK that contains both the 64-bit (`arm64-v8a`) and the 32-bit
(`armeabi-v7a`) builds, so the same file installs on any ARM phone or tablet.

### Keep the same signature between builds (recommended)

Without a keystore the APK is signed with a throwaway key that changes every build, so
you would have to uninstall before updating. Create a key once and store it as
repository secrets (Settings → Secrets and variables → Actions):

```bash
keytool -genkeypair -v -keystore release.jks -alias wiiudl -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks     # paste the output into ANDROID_KEYSTORE_BASE64
```

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | base64 of `release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | `wiiudl` (the alias you used) |
| `ANDROID_KEY_PASSWORD` | the key password |

Keep `release.jks` somewhere safe; never commit it.

## Building locally

Requires Go, JDK 17, the Android SDK (platform 35) and NDK 27.

```bash
python grabTitles.py                      # writes db.go (title database)
go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init
go get golang.org/x/mobile/bind
gomobile bind -target=android/arm64,android/arm -androidapi 26 -javapkg io.github.juanfunkin \
  -o android/app/libs/wiiu.aar ./wiiumobile
cd android && ./gradlew assembleDebug
```

## Layout

| Path | What |
|---|---|
| `wiiumobile/` | gomobile-friendly Go API over the core (search, related titles, download queue) |
| `android/app/src/main/java/.../` | Kotlin app: `Repo` (shared state), `DownloadService`, `Delivery` (copy to the chosen folder) |
| `android/app/src/main/java/.../ui/` | Compose screens: Library, Queue, Settings |
| `.github/workflows/android.yml` | CI that builds the AAR and the APKs |

The other workflows (`linux.yml`, `windows.yml`, `macos.yml`) are the desktop builds from
upstream and only run on tags or `BUILD_NOW` commits; disable them in the Actions tab of
your fork if you do not need them.

## License

GPLv3, same as upstream. Based on [Xpl0itU/WiiUDownloader](https://github.com/Xpl0itU/WiiUDownloader).
