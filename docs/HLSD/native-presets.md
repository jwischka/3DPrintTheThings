# Native full-preset configuration

The experimental engine accepts complete printer, process and filament presets
through the existing `loadProfile` JNI method. This path exists because the legacy
slice configuration exposes only a subset of Orca settings. It is opt-in: the
normal APK retains its inherited native library. The experimental build selects
this manifest format in its dedicated slicing workflow.

The `experimental` Android build is a separately installed application with the
`.presets` application ID suffix, a dedicated launcher, and separate application
storage. The build requires an explicit engine directory and verifies the ABI
and checksums before packaging the selected libraries.

## Prepare workspace

The Prepare tab brings together the imported model, print setup, and slice
result. A settings sheet selects the bundle, printer, process, and ordered
filament slots. The workspace stacks these panels on narrow displays and puts
the model and setup side by side on wider displays. Navigation moves from a
bottom bar to a side rail in landscape and wide windows. Short windows keep
actions and setup in one scrollable panel so large-text error messages cannot
hide the settings needed for recovery. Model dimensions come
from the preview bounds; slice time and material estimates come from the native
result and remain absent when unavailable. Android's document picker handles
preset import, model import, and G-code export.

Browse and Library share the workspace navigation. Downloaded models and files
selected from archives enter the same bounded copy and preview pipeline as
document imports. The active session owns its model copy independently of the
durable library, and retains a sanitized source link. Catalog services never
initialize a second native scene or the legacy slicing container. See
[model browsing and downloads](model-browsing.md) for transfer ownership and
navigation behavior.

Preset import accepts a ZIP containing `manifest.json` at its root and exactly
the referenced JSON files. Archive and expanded sizes, individual files, entry
counts, paths, names, categories and inheritance are checked before an atomic
publish to private storage. Failed imports cannot publish a partial bundle.
Printer, process and ordered filament selections produce a separate v2 manifest
with its required parent closure. The original catalog remains available for
later selections. Selections saved when slicing persist across application recreation.

Import copies one STL or single-plate 3MF to private cache storage. Preview
extraction holds the native scene mutex while loading the file and copying
render batches into Android-owned buffers. The native preview scene is released
before the live model is cleared and the mutex is returned. These buffers
preserve 3MF transforms and file-indexed filament colors and can be uploaded
again after EGL context loss. The snapshot packs colors into its interleaved
vertex data, which bounds retained CPU and GPU memory without a separate color
buffer. The snapshot allows up to 700,000 triangles emitted by the native scene,
including additional facets created when expanding color paint. Native buffers
briefly coexist with the snapshot during copying. Metadata fallback retains a
slot for every part, including parts inheriting the object tool, to avoid
shifting later assignments.
The viewport fits the model and supports orbit,
pan, and zoom. The imported preview retains its camera pose across tab switches
and activity rotation, with zoom relative to the fitted viewport. Fit resets
that pose; a new import starts fitted. A rejected replacement leaves the previous model available.
A display-budget or preview export failure leaves the imported file available
for slicing.

Slicing reloads the cached source under the same mutex and uses the selected
geometry-only manifest. Export copies the generated G-code bytes. Changing
inputs or encountering a failed import or slice clears the exportable result.
Cancellation keeps the workflow busy until the native operation returns and its
model and profile state have been reset.

## Printer tab

The Printer tab monitors a separately configured Moonraker endpoint with HTTP
GET requests. Job progress prefers the reported display progress, including
M73 updates, then file progress and the job status value. The dashboard shows
elapsed time, printer-reported remaining time, layer counts, and bed and tool
temperatures when available. Polling runs while the tab is visible and the
activity is started. Failed polls retain the last reported readings with a
stale indication. Non-ready firmware and paused or failed jobs can show a bounded,
plain-text reason reported by the printer; retained reasons are labelled stale,
and active or completed jobs do not show old exceptions. Editing the endpoint or
key restarts monitoring with a new connection.

Camera snapshots use Moonraker discovery and same-host fallback URLs,
including the printer's web port. The dashboard reuses a working webcam URL
until it fails, bounds response and decode sizes, and timestamps receipt of a
snapshot. The `monitor.jpg` fallback can serve a cached image with unknown
capture time; while using it, discovery periodically checks for a recovered
webcam. The optional API key uses the private no-backup store and
attaches only within the configured scheme, host, port, and path prefix.
The camera can expand to a full-window view using the same frame, polling loop,
and freshness labels as the dashboard.
Monitoring requests disable redirects. Shared credential and endpoint rules
are documented under [Moonraker connections and transfers](printer-connection.md).

## Manifest and slicing

A v1 JSON manifest has exactly five fields: `schema` (`orca-native-presets-v1`),
`printer`, `process`, `filaments` (ordered filenames), and `catalog` (inherited
preset filenames). The v2 manifest uses `orca-native-presets-v2` and requires a
sixth field, `"project_settings": "geometry-only"`. Paths are relative to the
manifest directory and must resolve
inside it. The manifest is limited to 64 KiB; each preset to 1 MiB; the selected
filament list to 16 entries; and the catalog to 128 entries. Files are interpreted
by the pinned Orca JSON configuration parser. Unknown options, substitutions,
duplicate names, missing parents, category conflicts and inheritance cycles are
errors. Custom settings are represented by derived preset files, preserving an
explicit, hashable inheritance chain. A category-specific list permits nine
retired or presentation-only fields present in the pinned U1 vendor files;
upstream discards these fields too. The original JSON bytes remain in the input
hashes. All other unknown fields are rejected, including misspelled settings.

`sapil_presets.cpp` resolves each chain against the upstream defaults, normalizes
instantiable presets, and composes their values through `PresetBundle::full_config`.
System preset metadata follows the upstream vendor-import rules, including
filament identity. User-derived presets retain their parent relationship. Filament
order and duplicates are meaningful: they determine the configuration vector
slots. FFF technology, nozzle dimensions, available filament indices and extruder
topology are checked before composition can silently clamp invalid selections.
Explicit non-FFF technology is rejected in every machine preset before looking
up technology-specific defaults: this engine has no SLA default preset.

The engine protects preset state with a mutex. A successful manifest load enables
full-preset mode; a failed load invalidates that mode and blocks slicing. It cannot
silently retain a previous preset or fall back to defaults after a failure.
`loadProfile("")` explicitly resets to legacy mode. A slice copies the loaded
configuration while holding the mutex, then releases it before processing.

The preset bundle supplies slicing settings and bypasses the legacy SliceConfig
overrides, embedded-project settings whitelist and legacy bed-centering
adjustment. V1 requires STL and rejects layer overrides and object or volume
configuration beyond filament assignment.

V2 accepts STL and 3MF. It slices a copy of the scene, dropping embedded project
settings, per-material, per-object, per-volume and per-layer settings, custom
layer G-code and manual brim points. Explicit filament assignments are retained
and must refer to a slot in the selected manifest. Imported 3MF location, scale,
rotation and printable status are retained. Raw STL scenes are placed on the bed
at the bounding-box center of the selected printer's printable area. Color-painted
facets are retained and must reference available selected filament slots 1 through
4; higher paint states are rejected because the pinned core can fold them onto
physical tool indices. Painting for supports, seams, fuzzy skin and exterior facets,
along with modifier and negative volumes, is rejected. Missing filament slots
produce a notice directing the user to Print setup. The slicing palette has one
entry per selected slot, using preset default colors or a valid default; the core
uses this palette's length to determine the available painted states. Preview
colors still come from the imported model. Resetting to legacy mode leaves
the loaded source scene and its embedded settings available. Upstream print
validation failures are fatal.

The `full_preset_loaded` diagnostic records relative input filenames, filament
count and configuration-key count. It does not serialize settings or macros into
the ordinary diagnostic stream. Test captures separately retain effective G-code
configuration and hashes for the engine source, model, manifest and every parent
preset. See [the parity harness](slicing-parity.md) for the evidence required to
compare these captures.

The Linux reference builds the pinned upstream core through its own CMake targets
with real host dependencies. It does not use the Android dependency shims or JNI
slicing pipeline. The pinned fork's external tree-support diagnostic hook writes
to standard error on the host; it cannot alter geometry or slicing decisions.
For unchanged vendor presets it independently loads the vendor
bundle through upstream's importer and requires exact configuration equality with
the manifest loader before slicing. The explicitly sanitized diagnostic case uses
derived presets with recorded macro changes; it cannot establish real-printer
equivalence. Neither emulator execution nor host configuration equality establishes
physical printer acceptance.
