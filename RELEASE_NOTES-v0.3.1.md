# NoCloud Voice 0.3.1: model download fix + faster long dictations

**Everyone on 0.3.0 should update.** On 2026-11-07 the Hugging Face repo that hosts the Parakeet speech model replaced its files. 0.3.0 checks the exact size and checksum of every file it downloads, so since then the first-time model download fails on 0.3.0 for every new user. 0.3.1 downloads from pinned commits that match its checksums, so the download works again. If your model is already downloaded, it keeps working either way.

**Download:** `NoCloud-Voice-0.3.1.apk` (below, under Assets)
**SHA-256:** `8cb940f65158d3f061639dd1caefc4bac9103bb45562928f754c6fa89f2cb9d8`
**Signing:** debug-signed, same key and package as 0.3.0, so it installs over 0.3.0 as an update and keeps your settings, dictionary, and model. Android will warn you before installing; that's expected for sideloaded apps.

### What's new
- **Model download fixed** (see above).
- **Long dictations are much faster.** Audio is cut at natural pauses into 10–20 s pieces, and each finished piece is transcribed in the background while you keep talking. When you tap ✓, only the last piece is left. In desktop tests a 2-minute dictation went from about 9.5 s after the tap to about 0.5 s. Phones are slower than a desktop, but the improvement is similar.
- **More accurate long dictations with Moonshine.** It no longer repeats words or produces junk on clips over about a minute.
- **Lighter live preview**, so there's less heat and more CPU left for the final text.
- Smaller fixes: the dictionary is parsed once, history is saved after the text is inserted, and the timing line shows the time from tap to text.

### Phones
Made for any Android phone running Android 10 or newer with a 64-bit ARM (arm64-v8a) processor. Developed and tested on a Google Pixel. Please report which phones work: https://github.com/robdilella-apps/nocloud-voice/issues

### Known limitations
- The bubble doesn't start by itself after a reboot on Android 14+. Open the app and tap Start.
- Some password, banking, and web fields refuse typed or pasted text. The text is put on your clipboard instead.
- If you see two bubbles, the faded one is Android's accessibility shortcut. Turn it off in Settings > Accessibility > NoCloud Voice floating button > shortcut.
- English only.

Full install steps are in the README.
