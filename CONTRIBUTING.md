# Contributing

Thanks for taking a look. This is a small hobby project, so keep it simple:

- **Bugs:** open an issue. Include your phone model, Android version, which model you use (Parakeet or Moonshine), the app you were typing into, and what happened.
- **Pull requests:** keep them small and focused. Run `./gradlew assembleDebug testDebugUnitTest` before you open one.
- **The rule:** nothing leaves the phone. PRs that add analytics, crash reporting, cloud speech, or any network calls besides the model download won't be merged.
- No new heavy dependencies unless they're needed. The app uses the plain Android framework (no AndroidX).
- By contributing you agree your code is released under the MIT license.
