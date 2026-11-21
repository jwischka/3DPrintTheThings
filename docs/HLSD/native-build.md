# Experimental native build

The Android app ships a prebuilt arm64 Orca-derived JNI library. The experimental
native workflow builds a separate engine for `arm64-v8a` and `x86_64`; its output
does not replace `app/src/main/jniLibs` or enter a distribution APK.

The engine source is the exact Git gitlink recorded at
`app/src/main/cpp/orcaslicer` in the workflow commit. `build-engine.sh` fetches
that commit into a fresh disposable directory. NDK `26.1.10909125` and Android
API 26 are fixed build inputs. `scripts/native/sources.lock` and
`extra-sources.lock` pin source revisions and archive hashes for the base and extra native
dependencies. For each ABI, the dependency workflows build separate base and
extra prefixes. GitHub Actions caches those prefixes under keys containing the
ABI, NDK generation, source locks, and build scripts; the engine workflow
restores both with an exact key and stops if either is absent. A separate
compiler cache accelerates rebuilding the same engine pin but is not a source
of dependencies.

The JNI engine version string currently contains a hardcoded Android ARM64
label even for x86_64 builds. Use the recorded artifact hash and build ABI for
binary provenance rather than inferring architecture from that string.

The build overlays the restored base prefix through symlinks in its disposable
`extern` directory, copies the checked-in serial TBB shim, and adds real NLopt,
JPEG, PNG, crypto, and libnoise libraries from the extra prefix. CMake compiles the pinned
Orca sources and the checked-in JNI bridge in Release mode. Each ABI produces
its own experimental engine artifact and build report. No prefix or output is
written back to the checked-in native libraries.

The build retains feature limitations inherited from the Android native source:
STEP and SVG loading are stubs, and text-shape and SLA/OpenVDB source paths are
excluded. The serial TBB shim changes performance, ordering and cancellation
behavior for the operations it implements. The Boost.Locale shim does not
provide Unicode normalization: Android filenames are not guaranteed to be NFC,
and normalization may fail without a converter facet. The fallback fuzzy-skin
shim disables grouping and Arachne application and leaves polygons unchanged;
the experimental real-dependency build instead compiles the pinned fuzzy-skin
implementation with libnoise. Geometry, support, Unicode and fuzzy-skin parity
with the desktop engine remain unverified.

The build checks ELF architecture, 16 KiB LOAD and RELRO alignment for the JNI
library, the expected compiler version, and the exact 60 exported
`Java_com_u1_slicer_NativeLibrary_` symbols against the shipped arm64 library.
The experimental engine links the NDK C++ runtime statically because it is the
app's only native shared library. This avoids distributing the NDK 26 shared
runtime, which has 4 KiB LOAD alignment. Both maximum and common linker page sizes
are 16 KiB. The build rejects non-platform dynamic dependencies and records hashes
of the JNI library, the v2 preset capability marker, and `cxx-runtime.txt`.

Each smoke job uses the engine artifact for its selected ABI. It builds ordinary debug
and test APKs, creates a disposable app APK containing that experimental JNI
library, and signs that APK and an isolated test APK with
the same ephemeral debug key. Packaging checks ELF architecture and page alignment
and preserves the original APK hash. PackageManager's selected ABI is checked
after installation. Linux x86_64 emulators run the x86 engine directly and the
ARM64 engine through Android's native bridge. Both variants exercise STL and
dual-color 3MF slicing, temperature commands, relative retraction, tool remapping,
bed bounds, cancellation and recovery, and a 10 mm cube capture through the real
JNI bridge. The runner requires matching selected, started, and successful test
counts and rejects skipped or failed tests. The cube capture rejects output without parsed model
extrusion and records G-code, the public SliceConfig fields, engine version, runtime ABIs,
and input/profile/output hashes. Raw STL uses direct SliceConfig with printer
machine G-code; recording bundled profile hashes does not mean their complete
settings were embedded. Export checks the fixture and output hashes before
publishing the capture artifact. The smoke exercises native slicing in an
emulator; printer hardware acceptance remains an owner-run check.

The same isolated job starts the in-memory U1 simulator. Its fixed model endpoint
serves a bounded, hash-checked cube fixture and can truncate that response to test
download cleanup and retry. A component test downloads into the durable library,
clicks the real Library Open action, slices, parses the preview, and verifies the
exact G-code upload hash in simulator state. A MainActivity test navigates through
the debug Library entry point and drives Prepare, Slice, Preview and Upload Only;
it checks the staged, remapped output against the accepted upload. Tests verify
the fixed simulator marker and endpoint before mutations and never send printer
commands to a configurable external destination.
The completed workflow retains Library, Prepare, Preview and upload confirmation
screenshots from the actual app, with hashes alongside the test results.

Separate app processes exercise the core native cases, geometry regressions,
the full MainActivity workflow, and [full-preset loading](native-presets.md).
Geometry cases cover preview rotation, repeated
rotation stability, placement, scaling, triangle batches and scene-handle release.
Every session requires every selected test to pass. A bounded host-side
logcat tail survives emulator loss, while host memory and kernel diagnostics
help distinguish Android assertions from runner failures.

The standalone Full Presets APK uses the `experimental` build type and application
ID `io.github.jwischka.threedprintthethings.presets`. It requires a native build
output directory containing the JNI library, `full-preset-schema.txt`,
`cxx-runtime.txt` declaring `c++_static`, and their
`sha256.txt` receipt. Validation runs even when Gradle has cached the packaging
steps. Missing files, an unsupported ABI, a missing v3 marker or mismatched
checksums stop the build. A leftover shared C++ runtime is rejected. The
experimental variant compresses its JNI entry so Android extracts it at install
time; this avoids the uncompressed-library ZIP alignment limitation in AGP 8.2.
Debug and Release retain the checked-in ARM64 libraries and their packaging.

```sh
./gradlew assembleExperimental -PfullPresetEngineDir=/absolute/native/output -PfullPresetAbi=arm64-v8a
```

The native CI job builds and installs this APK separately for each ABI. Its
variant-specific instrumentation imports prepared U1 bundles and a model through
document URIs, drives the Slice and filament controls, checks exact-byte document
export, and exercises failure recovery and stale-result invalidation. The test
source set is enabled with `-PpresetInstrumentation=true`. Captured screenshots
and test logs accompany the APK artifact. `tools/parity/package_presets.py`
packages a prepared manifest and its exact preset files as an importable ZIP.

A separate Android 15 x86_64 emulator job requires `getconf PAGE_SIZE` to return
16384 and runs the standalone import, slice and export workflow. This exercises
native code on 16 KiB pages in addition to the 4 KiB ABI smoke jobs. The APK
verifier checks the ELF LOAD segments, RELRO boundary, dynamic dependencies and
compressed or 16 KiB-aligned ZIP entry. Alignment checks alone do not establish
runtime correctness. Physical device acceptance remains distinct from emulation.

The shipping-engine probe installs the original ARM64 app and instrumentation
APKs on a Google APIs x86_64 emulator with native-bridge support. It verifies
the packaged libraries against the checked-in ARM64 hashes, requires
PackageManager to select `arm64-v8a`, and captures a real cube slice plus runtime
ABI provenance. APK hashes must remain unchanged. A separate process drives the
same full MainActivity workflow with those original APKs. This exercises the inherited
engine under translation; it does not establish physical ARM64 performance or
device compatibility. The probe runs manually or when its own workflow or
runner changes on the development branch.
