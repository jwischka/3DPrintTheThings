# Experimental Android native builds

See [the native build design](../../docs/HLSD/native-build.md) for pinned inputs, artifact boundaries, compatibility checks, and inherited feature limits.

Run the `Experimental native dependency bundle` and `Experimental native extra dependencies` workflows with component `all` before `Experimental native engine build`. Both dependency workflows must complete for each desired ABI; component-only caches cannot satisfy an engine build. The engine workflow restores exact caches and uploads separate experimental binaries and an x86 emulator slicing report.

Local Linux x86_64 builds use `build-deps.sh`, `build-extra-deps.sh`, and `build-engine.sh`. Each script requires an explicit disposable work directory and NDK 26.1.10909125; inspect its required environment variables before invocation. These scripts do not support the Windows ARM64 host directly.
