# Native full-preset configuration

The experimental engine accepts complete printer, process and filament presets
through the existing `loadProfile` JNI method. This path exists because the legacy
slice configuration exposes only a subset of Orca settings. It is opt-in: the
normal APK retains its inherited native library, and its UI does not select this
manifest format.

A JSON manifest has exactly five fields: `schema` (`orca-native-presets-v1`),
`printer`, `process`, `filaments` (ordered filenames), and `catalog` (inherited
preset filenames). Paths are relative to the manifest directory and must resolve
inside it. The manifest is limited to 64 KiB; each preset to 1 MiB; the selected
filament list to 16 entries; and the catalog to 128 entries. Files are interpreted
by the pinned Orca JSON configuration parser. Unknown options, substitutions,
duplicate names, missing parents, category conflicts and inheritance cycles are
errors. Custom settings are represented by derived preset files, preserving an
explicit, hashable inheritance chain.

`sapil_presets.cpp` resolves each chain against the upstream defaults, normalizes
instantiable presets, and composes their values through `PresetBundle::full_config`.
System preset metadata follows the upstream vendor-import rules, including
filament identity. User-derived presets retain their parent relationship. Filament
order and duplicates are meaningful: they determine the configuration vector
slots. FFF technology, nozzle dimensions, available filament indices and extruder
topology are checked before composition can silently clamp invalid selections.

The engine protects preset state with a mutex. A successful manifest load enables
full-preset mode; a failed load invalidates that mode and blocks slicing. It cannot
silently retain a previous preset or fall back to defaults after a failure.
`loadProfile("")` explicitly resets to legacy mode. A slice copies the loaded
configuration while holding the mutex, then releases it before processing.

In full-preset mode, STL scene geometry supplies the model and placement; the
preset bundle supplies the slicing settings. The legacy SliceConfig overrides,
embedded-project setting whitelist and legacy bed-centering adjustment are
bypassed. Embedded 3MF projects, layer overrides, and object/volume configuration
other than filament assignment are rejected because their precedence has not
been defined for this format. Upstream print validation failures are fatal.

The `full_preset_loaded` diagnostic records relative input filenames, filament
count and configuration-key count. It does not serialize settings or macros into
the ordinary diagnostic stream. Test captures separately retain effective G-code
configuration and hashes for the engine source, model, manifest and every parent
preset. See [the parity harness](slicing-parity.md) for the evidence required to
compare these captures.

The Linux reference builds the pinned upstream core through its own CMake targets
with real host dependencies. It does not use the Android dependency shims or JNI
slicing pipeline. For unchanged vendor presets it independently loads the vendor
bundle through upstream's importer and requires exact configuration equality with
the manifest loader before slicing. The explicitly sanitized diagnostic case uses
derived presets with recorded macro changes; it cannot establish real-printer
equivalence. Neither emulator execution nor host configuration equality establishes
physical printer acceptance.
