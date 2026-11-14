# 3DPrintTheThings agent instructions

Android fork of `taylormadearmy/u1-slicer-for-android`: Kotlin/Compose app with a prebuilt Orca-derived JNI engine, targeting Snapmaker U1. Product and package identity: `3DPrintTheThings`, applicationId `io.github.jwischka.threedprintthethings`; retain namespace `com.u1.slicer` for JNI compatibility.

## Build and tests

Use JDK 17 and Android SDK 34. Set `local.properties` for the local SDK; do not commit it.

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew testDebugUnitTest --tests 'com.u1.slicer.SomeTest'
```

Run the relevant unit tests before each commit. Run at most two Gradle builds at once. Native slicing tests require the owner's arm64 phone. Browser-only instrumentation may run on a Linux x86_64 emulator using the separate UI test APK that omits native libraries; never use that APK for slicing, printing, or distribution. Hardware checks and all real printer interaction are owner-run. Never start a real print; use upload-only flows for send testing.

## Boundaries and safety

- Phase 1 is Kotlin-only. Do not change native code, JNI declarations, `NativeLibrary.kt` signatures, or fields in `data/SliceConfig` and `data/ModelInfo`; the shipped `.so` binds to them by name. Do not initialize the Orca submodule before Phase 2.
- Keep out of `SlicerViewModel.kt` and `MainActivity.kt` except at the seam named by the task. Do not edit `startSlicing` in Phase 1.
- Never weaken or delete a test assertion. Upstream structural/source-grep tests may need a same-commit update when legitimate changes break them; explain the reason.
- Never log or commit API keys, cookies, access codes, signing keys, or other credentials. Settings backups must exclude secrets by default.
- Do not change versions, release files, test counts, or `BACKLOG.md`; release and backlog maintenance belong to the maintainer.

## Upstream contributions

Confirm a reported defect with a test against upstream `main` before filing an issue. Keep each upstream PR to one bug and include a test. Disclose AI assistance in the PR body. Do not post issues, PRs, releases, or tags without the owner's explicit go-ahead.

## Reference

The detailed upstream test-class index is in [`docs/test-catalog.md`](docs/test-catalog.md); counts there are snapshots, not build expectations. The full project handoff and phase plan are maintained outside the active brief.
