# k cast

Show one Android phone's screen on another Android phone, over Wi-Fi. No account, no internet, no ads.

Made for filming with a phone camera and watching the live picture on a tablet: the phone that shares its screen can be the camera app, and the other device shows it full screen.

## How to use it

**On the phone to show:** open the app, pick a speed preset, tap **Share my screen** and allow screen capture. The app shows a 4-digit code and its Wi-Fi address.

**On the phone to watch:** open the app, type the 4-digit code, then tap the other phone's name in the list (it is found automatically on the same Wi-Fi) or type its address. The picture fills the screen.

Both phones must be on the same Wi-Fi network; a phone hotspot works. The code is checked when a viewer connects, so only someone with the code can watch.

## How it works

- Screen capture with `MediaProjection`, encoded to H.264 with `MediaCodec`.
- Video is sent over TCP on port 8554, one stream per viewer; several viewers can watch at once.
- The sender advertises itself with mDNS (`_kcast._tcp`), which is how the viewer finds it.
- Rotation on the sender is handled live: the encoder and virtual display are rebuilt and every viewer gets a new key frame.
- No audio, no clipboard, no remote control, and nothing leaves the local network.

## Speed presets

| Preset | Longest side | Bitrate |
| --- | --- | --- |
| Fast | 720 px | 2.5 Mbit/s |
| Balanced | 1024 px | 4 Mbit/s |
| Quality | 1280 px | 6 Mbit/s |

Fast gives the least delay, Quality the sharpest picture.

## Requirements

Android 8.0 (API 26) or newer on both phones. Nothing else to install on the computer side, because there is no computer side.

## Install

Download `kcast-v0.1.0.apk` from the [Releases page](https://github.com/karim-kamel-dev/k-cast/releases) and open it on both phones. Android will ask you to allow installing from your browser or file manager. The APK is not on Google Play.

The APK is signed with a key kept in this repository's Actions secrets, so a new build installs over the previous version. The `kcast-apk` artifact in the **Actions** tab is the same app, for anyone who prefers building it there.

## Build

Android SDK 34, JDK 17, Gradle 8.11.1. From the repository root:

```
gradle assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

## Status

Version 0.1.0. Works on the author's phones (a Tecno Camon 30 and a Redmi Pad SE). There are no automated tests and it has not been tried widely.

Known limits: no audio; both phones must be on a network that lets them reach each other, so guest Wi-Fi that isolates clients will not work; a locked or backgrounded sender still streams whatever Android allows it to capture.

## License

This project is licensed under the [MIT License](LICENSE).
