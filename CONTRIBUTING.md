# Contributing to Trove

Trove is a small, personally maintained project — the process stays small too.

<p align="center"><a href="CONTRIBUTING.md">English</a> · <a href="CONTRIBUTING.zh-CN.md">简体中文</a></p>

## Bugs and ideas

Open an [issue](https://github.com/MisakaMkotoo/Trove/issues) — one topic per issue.

- For playback/sync bugs, include device model, Android version, and reproduction steps; logs (`adb logcat`) or a screen recording help a lot.
- NetEase online features depend on third-party endpoints and may stop working at any time. Stable reproductions are still welcome.
- UI proposals should include screenshots or reference images so the visual intent is clear.

## Code

- The default branch is `main`; there is no long-lived development branch. Fork, create a descriptive branch, and open a pull request against `main`.
- At minimum, make sure the project compiles before submitting: `./gradlew :androidApp:assembleProdDebug`.
- Follow the existing Kotlin + Jetpack Compose style. The UI targets an Apple Music–like feel; when changing animations or visuals, attach before/after captures.
- New user-facing strings must be added to both `values/` (English) and `values-zh/` (Simplified Chinese).
- Never commit `build/`, `.gradle/`, APKs, signing keystores, `local.properties`, `jniLibs/`, or any key/token.
- The project is GPL-3.0: contributions are licensed under the same terms.

## Relationship with upstream

This project derives from [Exodi-dio/tune](https://github.com/Exodi-dio/tune) but is maintained independently and does not automatically follow upstream. If you want an upstream change in this repository, send a separate PR and mention its origin in the description.

## Everything else

Setup help, device quirks, and feature discussions are welcome in Issues as well — prefix the title with `[Discussion]`.
