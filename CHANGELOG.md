# Changelog

## 0.3.0 (first public release as NoCloud Voice)
- Renamed to NoCloud Voice (previously a private build).
- Floating bubble: live preview of words while you talk. It uses the same model and needs no extra download, and you can turn it off.
- Instant feedback when you tap the check: a vibration, a spinner, "Transcribing…", then "✓ Inserted".
- Faster results: the model loads ahead of time and silence at the start and end is trimmed.
- The default dictionary now has neutral examples instead of personal ones.

## 0.2.x
- Added the floating tap-to-start/stop bubble (accessibility service plus overlay) that types into the focused field.
- More reliable insertion (set text, then paste, then clipboard), with diagnostics in the app.
- Fixed a duplicate bubble and explained Android's accessibility shortcut button.

## 0.1.x
- First on-device dictation keyboard (sherpa-onnx with Parakeet TDT 0.6B v2 or Moonshine Base).
- Hold-to-talk or tap for hands-free, auto punctuation, filler-word removal, voice commands, custom dictionary, local history.
