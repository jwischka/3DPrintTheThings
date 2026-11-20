# Slicing parity harness

The parity harness compares G-code exported by the desktop Orca CLI with G-code
produced by the Android engine. It reports CONFIG_BLOCK settings, layer marker
count, ordered tool changes, consumed filament length per tool, and duration
metadata. It accounts for retraction debt so an unretraction does not count as
new consumed filament. It normalizes line endings, comments, and command
whitespace by parsing commands; it does not compare bytes or claim exact parity.

Extrusion allows 0.5% difference per tool; duration allows 1%. Optional absolute
epsilons default to 0.1 mm and 1 second, and apply only when the compared value
reaches 100 mm extrusion or 60 seconds. This keeps the absolute floor from
masking differences on tiny prints. Config settings, layer count, and tool
sequence must match exactly. Comparisons fail closed if either file lacks one
balanced CONFIG_BLOCK, Klipper flavor metadata, layer markers, positive
extrusion, or positive duration.
Unknown macro commands and volumetric extrusion also make a comparison fail.
A baseline can capture successfully while remaining ineligible for comparison
because it contains opaque printer macros. Successful desktop slicing means only
the CLI produced a result; it is not a parity pass.

Run the dependency-free checks:

```text
python -m unittest discover -s tools/parity -p 'test_*.py'
python tools/parity/parity.py inspect candidate.gcode
python tools/parity/parity.py compare tools/parity/baselines/case.json android.gcode
```

Before comparing captures made through the native preset loader, bind their
metadata to the exact artifacts with the provenance gate:

```text
python tools/parity/provenance.py desktop.json desktop.gcode android.json android.gcode
python tools/parity/parity.py compare desktop.json android.gcode
```

The provenance gate requires schema version 1, a full engine source commit pin,
matching input-model SHA-256, matching manifest schema/name/hash, the same
hash map for the complete selected and inherited preset JSON closure, identical
sanitization declarations, and matching effective CONFIG_BLOCK values. Each
capture's nested `parityProvenance.gcodeSha256` is checked against the G-code
file bytes before the pair can proceed. Preset closure hashes are supplied by
the preset resolver; the gate validates their shape and pair equality, but
cannot prove that a resolver included every file unless its resolved-input
report is complete. The source pin is supplied by the build/CI invocation,
since the JNI capture cannot independently report its engine source revision.

A provenance match only establishes that both artifacts declare the same
inputs and that metadata points to the supplied files. It is not a parity
result and does not prove that a preset was applied. Run the unchanged strict
`parity.py compare` after the gate; exact configuration, structural metrics,
Klipper metadata, and unknown-macro rejection remain in force. A
`macro-free-diagnostic` capture is explicitly sanitized evidence and cannot be
presented as real U1 profile parity. Captures without complete provenance are
not eligible for matched-preset comparison.

The comparison command also accepts two G-code paths. Optional absolute
epsilons are set with `--extrusion-abs-eps-mm` and
`--duration-abs-eps-seconds`.

Capture a desktop baseline from G-code exported by an Orca CLI available to the
operator:

```text
python tools/parity/parity.py capture desktop.gcode tools/parity/baselines/case.json \
  --engine "OrcaSlicer <version-or-commit>" \
  --preset "Snapmaker U1 <preset-revision>" \
  --input-sha256 <sha256-of-input-model> \
  --cli-command "orca-slicer <input> --load-settings <process;machine> --load-filaments <filament> --slice 0"
```

Use relative or placeholder paths in `--cli-command`; do not store local
machine-specific paths. The resulting JSON records engine/preset provenance,
input and G-code SHA-256 values, plus structural metrics. Keep the original
G-code separately and do not add private models or user data to fixtures. The
manual `Capture desktop Orca baseline` workflow runs this process on a pinned
v2.5.0 Ubuntu CLI, using a 10 mm cube and the matching official Snapmaker U1,
Standard 0.20 mm, and Generic PLA presets. It uploads the raw G-code and JSON
report as a 30-day artifact; the report contains preset-file hashes. The Android
app's bundled profile assets are maintained separately from those desktop
profiles, so their hashes and effective settings must be aligned before a
cross-engine comparison can be interpreted.

Comparison output summarizes changed and missing config keys instead of dumping
both full dictionaries. Important setting values and bounded key lists make the
remaining mismatches reviewable; omitted-key counts are explicit. JSON comparison
output includes `config_diff` and omits raw config maps from its two summaries.
The `inspect` and `capture` commands retain full config maps, and equality is
still checked against every original setting before formatting the diagnostics.

Fixtures cover absolute and relative extrusion, G90/G91 and M82/M83 modal
commands, G92 E resets, retraction recovery, and tool changes. Extruder mode
follows Klipper semantics for this U1 target: G90/G91 selects the coordinate
mode, M83 forces relative extrusion, and M82 clears that force. See the
[Klipper G-code reference](https://www.klipper3d.org/G-Codes.html#extended-g-code-commands).
The parser does not simulate coordinates, firmware macros, volumetric extrusion,
or firmware execution time. For duration it uses maximum TIME_ELAPSED when
present, otherwise the explicit normal-mode estimate, then a generic estimate;
silent-mode estimates are ignored. Unrecognized bare macro commands are listed
as unsupported and prevent a comparison from reporting PASS.
