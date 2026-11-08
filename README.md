# Reels TV

A remote-friendly Android TV interface for watching Instagram Reels. Reels TV opens Instagram's official website in a WebView and plays available video in a TV-oriented player with a simple icon-based control rail.

This is an independent project. It is not affiliated with, endorsed by, or an official product of Instagram, Meta, or Xunlei. Instagram's website and available features may change without notice.

## Features

- Navigate to the previous or next Reel with a TV remote.
- Like a Reel when the corresponding Instagram control is available; a filled heart indicates a confirmed like.
- Rotate a video left and switch between automatic zoom and original proportions. Display preferences are remembered per Reel on the device.
- Use the official web page with a remote-controlled pointer when needed.
- Receive on-screen messages in English, Chinese, or Japanese, following the device language. Other languages fall back to English.
- Pause automatic connections when Instagram requests verification or returns a rate-limit response. You decide when to continue on the official page.

The application does not guarantee that a particular video, control, or account session will remain available. Do not treat it as a way to bypass verification or account restrictions.

## Device requirements

- Android 8.0 (API 26) or later, with an Android TV-compatible remote and working WebView.
- **32-bit ARM (`armeabi-v7a`) support.** This build has been tested on one Sony Android TV 10 device; compatibility with other devices and 64-bit-only devices has not been established.
- Network access to Instagram and its media servers. Sign in on the official page when prompted. The app has its own WebView session; it does not import the session from the official Instagram app.

The optional Instagram Remote Assistant accessibility service is for controlling the separate official Instagram app. Reels TV itself does not require enabling it. If enabled, Android grants the service access to window information, directional keys and gesture simulation; the service filters keys only while the official Instagram app is active and the keyboard is closed.

### Saving to Xunlei

The blue cloud-and-lightning button is an integration with a separate, customized CleanYourTV inbox application (`com.gathin.tvhome.visual`). **That companion application is not included in this repository.** Without the compatible companion, the button reports that the save entry is unavailable. It sends the current Reel URL, media source URL and media user agent to that companion; its actual saving behavior and destination depend on the companion's configuration. This project is not a standalone Xunlei uploader.

## Build and install

You need JDK 17, Android SDK Platform 34 and Build Tools 34.0.0. Set `ANDROID_HOME` to your Android SDK directory, or use Android Studio's local SDK configuration. The included Gradle wrapper downloads Gradle 8.9 with checksum verification on first use; Gradle also downloads the Android plugin and declared dependencies.

```sh
./gradlew assembleRelease lintRelease
```

On Windows, use `gradlew.bat` instead. The APK is generated at:

```text
build/outputs/apk/release/InstagramRemote-release.apk
```

The existing release configuration signs the APK with the **local Android debug key** for development convenience. Each computer may have a different key. Android only permits an in-place update when the package name and signing certificate match the installed app. Do not uninstall an existing installation just to work around a signature mismatch: doing so removes its local data and WebView session. A public distribution should use an appropriately managed signing key and satisfy the applicable third-party license requirements.

To install a compatible build on a connected device:

```sh
adb install -r build/outputs/apk/release/InstagramRemote-release.apk
```

This repository distributes source code, not a pre-signed APK.

## Remote controls

| Control | Action |
| --- | --- |
| Up / down | Select a button in the sidebar |
| OK | Activate the selected button |
| Left | Enter the Instagram page and use the pointer |
| Right at the page edge | Return to the sidebar |
| Play / pause | Pause or resume the video |
| Back, Home, volume | Normal system behavior |

Buttons may be unavailable while a page or video is loading or when Instagram does not expose the corresponding action. Automatic zoom may crop content; use the picture button to restore the original proportions.

## Tests

The JavaScript tests use Node.js 18 or newer and do not contact Instagram:

```sh
node --test tests/*.test.cjs
```

The `debug` build uses the separate `com.gathin.instagramremote.checks` application ID and removes network permissions. Its device-side safety checks also require locally supplied test videos in the test app's cache. Those video fixtures are **not included** in this repository, so the complete device-side playback suite cannot be reproduced from this repository alone. The JavaScript tests and local device checks do not prove how the live Instagram website or an individual account will behave.

## Privacy and data

The app connects to Instagram and Facebook media hosts for the official page and playback. Its WebView stores its own session and cookies on the device; the separate official Instagram app does not share them automatically. The app stores per-video rotation and zoom preferences locally. Android backup is disabled for this application. No account credentials, cookies, personal viewing history, private network settings, or signing keys are included in this repository.

If you enable the optional accessibility service or use the companion save integration, review the permissions and data flow described above. Content availability depends on the service and the account's access.

## License and attribution

Project source is distributed under the [MIT License](LICENSE). The project was developed alongside [CleanYourTV](https://github.com/Gathin23/cleanyourtv); its copyright notice is retained. The Gradle wrapper, AndroidX libraries, and LibVLC are separate third-party components with their own licenses; see [THIRD_PARTY.md](THIRD_PARTY.md). License obligations for a distributed APK can differ from those for this source repository.
