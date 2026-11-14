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

The build checks ELF architecture, 16 KiB LOAD alignment for the new JNI
library, the expected compiler version, and the exact 60 exported
`Java_com_u1_slicer_NativeLibrary_` symbols against the shipped arm64 library.
It records hashes of the engine and the NDK `libc++_shared.so`. The latter's
LOAD alignment is reported separately, so this workflow does not establish
runtime compatibility with a 16 KiB page-size device.

The x86 smoke job uses only the x86 engine artifact. It builds ordinary debug
and test APKs, creates a disposable app APK containing the experimental x86 JNI
library and NDK C++ runtime, and signs that APK and an isolated test APK with
the same ephemeral debug key. On a Linux x86_64 emulator, it runs the bundled
tetrahedron slicing instrumentation test through the real JNI bridge and
requires a positive instrumentation result. The smoke exercises native slicing
in an emulator; printer hardware acceptance remains an owner-run check.
