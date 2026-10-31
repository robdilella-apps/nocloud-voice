# NoCloud Voice 0.3.0: first public release

On-device speech-to-text for Android, tested on Google Pixel. You get a dictation keyboard and a floating tap-to-talk bubble. Your speech is turned into text on the phone itself, so it works with no signal and your audio never leaves the device.

**Download:** `NoCloud-Voice-0.3.0.apk` (below, under Assets)
**SHA-256:** `3fc9f066c1a886b8ae999482b2d46422bb5398deb9a459c5a0922c074e5f8f83`
**Signing:** debug-signed (no Play Store). Android will warn you before installing; that's expected for sideloaded apps.

### What's in it
- NoCloud Voice keyboard: hold the big bar to talk, or tap once to go hands-free.
- Floating bubble: tap to start, tap ✓ to insert into whatever text box you're in, tap ✕ to discard. Shows live words while you speak.
- Two offline models, downloaded once inside the app: Parakeet TDT 0.6B v2 (~662 MB, most accurate) or Moonshine Base (~287 MB, smaller and faster).
- Auto punctuation, filler-word removal, simple voice commands ("new line", "question mark"), custom word list, local history.

### Known limitations
- Tested on Pixel only. Other Android phones (arm64, Android 10+) may work. No iPhone version.
- The bubble doesn't start by itself after a reboot on Android 14+. Open the app and tap Start.
- Some password, banking, and web fields refuse typed or pasted text. The text is put on your clipboard instead.
- If you see two bubbles, the faded one is Android's accessibility shortcut. Turn it off in Settings > Accessibility > NoCloud Voice floating button > shortcut.
- English only.

Full install steps are in the README.
