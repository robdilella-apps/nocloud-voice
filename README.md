# NoCloud Voice

Speech-to-text for Android that runs entirely on your phone. You get a dictation **keyboard** and a **floating tap-to-talk bubble** that types into almost any app. It works with no cell signal and nothing you say leaves the phone.

> Tested on Google Pixel. Other Android phones may work. iPhone is not supported.

## Why on-device?
Cloud dictation apps send your audio to a server. If you walk outside and lose signal halfway through a sentence, the text can just disappear. NoCloud Voice runs the speech model on the phone, so:
- it works in airplane mode, in basements, and in dead zones
- your voice and your text never leave the device
- there's no account, subscription, or server

The design is inspired by desktop push-to-talk dictation tools.

## Features
- **Keyboard:** a big hold-to-talk bar (or tap once for hands-free), plus ABC / undo / space / delete / enter keys.
- **Floating bubble:** tap to start, see words appear live, tap ✓ to insert into the text box you're in, or ✕ to throw it away. You can drag it anywhere.
- **Offline models** (via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)):
  - NVIDIA **Parakeet TDT 0.6B v2**: best English accuracy, with punctuation and capitals built in.
  - **Moonshine Base**: smaller and faster, slightly less accurate.
- Removes filler words ("um", "uh"), handles voice commands ("new line", "new paragraph", "question mark", "exclamation point"), and has your own "heard => written" word list.
- Keeps a history of your last 500 dictations, stored only on the phone, tap to copy.
- English only for now.

## Screenshots
_Coming soon._
<!-- docs/keyboard.png, docs/bubble.png, docs/setup.png -->

## Install on a Pixel (from GitHub Releases)
1. On your phone, open this repo's **[Releases](https://github.com/robdilella-apps/nocloud-voice/releases)** page and tap **NoCloud-Voice-0.3.0.apk** under *Assets*.
2. Chrome may say the file could be harmful. Tap **Download anyway**.
3. When it finishes, tap **Open**. (Or open the **Files** app > **Downloads** and tap the APK.)
4. If you see *"For your security, your phone is not allowed to install unknown apps from this source"*, tap **Settings**, turn on **Allow from this source**, then go back.
5. Tap **Install**. If Play Protect warns that it doesn't recognize the app, tap **More details > Install anyway**. It's flagged only because it's not from the Play Store.
6. Open **NoCloud Voice** and work through the steps on screen:
   1. **Microphone:** tap *Grant microphone permission > While using the app*.
   2. **Speech model:** pick Parakeet or Moonshine and tap *Download*. Wi-Fi is recommended. This is the only time the app uses the internet.
   3. **Keyboard:** tap *Open keyboard settings*, turn on **NoCloud Voice**, and accept Android's standard "this keyboard may collect text" warning. Then use *Switch keyboard now*, or the keyboard icon at the bottom of any keyboard.
   4. **Floating bubble (optional):**
      - allow notifications and *Display over other apps*
      - tap *Open NoCloud Voice App info*, then **⋮** (top right) > **Allow restricted settings**. Sideloaded apps need this once.
      - tap *Turn on accessibility*, then **NoCloud Voice floating button** > on
      - tap *Start*
7. Want to check the download? Compare its SHA-256 with the one in the release notes.

## Known limitations
- **No auto-start after a reboot on Android 14+.** Android doesn't let a background app start the microphone, so open the app and tap Start after you restart the phone.
- **Some fields refuse typed text.** This includes some password fields, banking apps, and some web or custom text boxes. When that happens, the text goes to the clipboard so you can paste it.
- **Two bubbles?** The faded one is Android's own *accessibility shortcut* button, not this app. Turn it off in Settings > Accessibility > NoCloud Voice floating button > shortcut toggle.
- Model sizes: Parakeet needs about 662 MB of storage and roughly 1 GB of RAM while running. Moonshine needs about 287 MB.
- The first dictation after the app starts is slower while the model loads.
- Only arm64 phones (all recent Pixels). The APK is debug-signed, not from the Play Store. Android 17's Advanced Protection mode may turn off the accessibility permission.
- Testing so far has been on Pixel. Other phones may work, but there are no promises. **iPhone is not supported.**

## Build from source
Requirements: JDK 17, Android SDK (platform 35, build-tools 35), internet access for Gradle the first time.
```bash
git clone <this repo> && cd nocloud-voice
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # your SDK path
./gradlew assembleDebug testDebugUnitTest
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
The build uses AGP 8.7.3, Kotlin 2.0.21, and Gradle 8.10.2. The code is plain Android framework with no AndroidX, minSdk 29, and targetSdk 35. The default target is arm64-v8a; use `-Pabis=arm64-v8a,x86_64` for an emulator. sherpa-onnx comes as a prebuilt AAR in `app/libs/`.

## Models
The models are **not** in the repo or the APK. The app downloads them once from Hugging Face over HTTPS and checks each large file's SHA-256:
| Model | Download | License |
|---|---|---|
| Parakeet TDT 0.6B v2 int8 | ~662 MB | CC-BY-4.0 (NVIDIA) |
| Moonshine Base EN int8 | ~287 MB | MIT |

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Privacy
- Your audio is processed on the phone and never uploaded.
- The internet permission is used only to download the model files from Hugging Face.
- No analytics, ads, crash reporting, or accounts.
- History and settings stay in the app's private storage. Android backup is turned off for the app.
- The accessibility service only acts when you tap ✓, and only on the text field you're focused on.

## License
MIT for the NoCloud Voice code ([LICENSE](LICENSE)). Third-party libraries and models have their own licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
