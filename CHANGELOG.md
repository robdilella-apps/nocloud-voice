# Changelog

## 0.3.1 (update recommended for everyone on 0.3.0)
- Fixed the first-time model download. The Hugging Face repo that hosts the Parakeet model replaced its files on 2026-11-07, so 0.3.0's size and checksum checks rejected every new download. 0.3.1 downloads from pinned commits that match its checksums. Models you already downloaded keep working.
- Long dictations are much faster. Audio is cut at natural pauses into 10–20 s pieces, and each finished piece is transcribed in the background while you keep talking. When you tap ✓, only the last piece is left. Before, the whole recording was processed in one pass, which got slower faster than the recording got longer.
- More accurate long dictations with Moonshine. On clips over about a minute it used to repeat words or produce junk.
- The live preview runs less often, so the phone has more CPU left for the final text and makes less heat.
- Smaller fixes: the custom dictionary is parsed once and reused, the recorder copies less memory, history is saved after the text is inserted, and the app's timing line shows the time from tap to text.

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
