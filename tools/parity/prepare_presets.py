#!/usr/bin/env python3
"""Copy one pinned Snapmaker U1 preset closure into native manifest fixtures."""

import argparse
import hashlib
import json
from pathlib import Path

SCHEMA = "orca-native-presets-v1"
SELECTED = {
    "machine": "Snapmaker U1 (0.4 nozzle).json",
    "process": "0.20 Standard @Snapmaker U1 (0.4 nozzle).json",
    "filament": "Snapmaker PLA Full Spectrum @U1 0.4 nozzle.json",
}
MAX_PRESET_BYTES = 1024 * 1024


def _read_preset(path: Path, expected_type: str) -> tuple[bytes, dict]:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_PRESET_BYTES:
        raise ValueError(f"Unsafe or oversized preset: {path}")
    data = path.read_bytes()
    value = json.loads(data)
    if not isinstance(value, dict) or value.get("type") != expected_type:
        raise ValueError(f"Wrong preset type: {path}")
    if not isinstance(value.get("name"), str) or not value["name"].strip():
        raise ValueError(f"Preset has no name: {path}")
    return data, value


def _catalog(directory: Path, kind: str) -> dict[str, Path]:
    names: dict[str, Path] = {}
    duplicates: set[str] = set()
    for path in sorted(directory.glob("*.json")):
        # Vendor folders also contain presets for other machine families and
        # occasional legacy entries with a different type. Validate the
        # selected closure strictly, but do not reject unrelated entries.
        try:
            _, value = _read_preset(path, kind)
        except ValueError:
            continue
        name = value["name"]
        if name in names:
            duplicates.add(name)
        names[name] = path
    for name in duplicates:
        names.pop(name)
    return names


def _closure(directory: Path, kind: str, leaf: str) -> list[Path]:
    names = _catalog(directory, kind)
    ordered: list[Path] = []
    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(path: Path) -> None:
        _, value = _read_preset(path, kind)
        name = value["name"]
        if name in visiting:
            raise ValueError(f"Preset inheritance cycle: {name}")
        if name in visited:
            return
        visiting.add(name)
        parent = value.get("inherits")
        if parent:
            if not isinstance(parent, str) or parent not in names:
                raise ValueError(f"Missing or ambiguous parent of {name}: {parent}")
            visit(names[parent])
        visiting.remove(name)
        visited.add(name)
        ordered.append(path)

    visit(directory / leaf)
    return ordered


def _write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def prepare(source: Path, output: Path) -> dict:
    vendor = source / "resources" / "profiles" / "Snapmaker"
    if output.exists():
        raise ValueError(f"Output must be new: {output}")
    selected = {kind: vendor / kind / filename for kind, filename in SELECTED.items()}
    closures = {kind: _closure(vendor / kind, kind, filename)
                for kind, filename in SELECTED.items()}
    output.mkdir(parents=True)
    all_originals: dict[str, str] = {}
    for kind, paths in closures.items():
        (output / kind).mkdir()
        for path in paths:
            relative = f"{kind}/{path.name}"
            data, _ = _read_preset(path, kind)
            (output / relative).write_bytes(data)
            all_originals[relative] = hashlib.sha256(data).hexdigest()

    actual_refs = {kind: f"{kind}/{path.name}" for kind, path in selected.items()}
    actual_catalog = sorted(set(all_originals) - set(actual_refs.values()))
    actual = {
        "schema": SCHEMA,
        "printer": actual_refs["machine"],
        "process": actual_refs["process"],
        "filaments": [actual_refs["filament"]] * 4,
        "catalog": actual_catalog,
    }
    _write_json(output / "actual.json", actual)

    # These are derived diagnostic children. Original pinned JSON bytes above remain
    # untouched and their hashes are recorded separately from generated children.
    diagnostic_values = {
        "machine": {
            "machine_start_gcode": "G28\nG92 E0",
            "machine_end_gcode": "M104 S0\nM140 S0",
            "change_filament_gcode": "",
            "machine_pause_gcode": "",
            "before_layer_change_gcode": "",
            "layer_change_gcode": "",
            "time_lapse_gcode": "",
        },
        "process": {"exclude_object": "0"},
        "filament": {"filament_start_gcode": [""], "filament_end_gcode": [""]},
    }
    diagnostic_refs: dict[str, str] = {}
    derived: dict[str, str] = {}
    for kind, overrides in diagnostic_values.items():
        _, original = _read_preset(selected[kind], kind)
        relative = f"{kind}/diagnostic_{kind}.json"
        diagnostic_refs[kind] = relative
        child = {"type": kind, "name": f"Diagnostic {original['name']}",
                 "inherits": original["name"], **overrides}
        _write_json(output / relative, child)
        derived[relative] = hashlib.sha256((output / relative).read_bytes()).hexdigest()
    diagnostic = {
        "schema": SCHEMA,
        "printer": diagnostic_refs["machine"],
        "process": diagnostic_refs["process"],
        "filaments": [diagnostic_refs["filament"]] * 4,
        "catalog": sorted(all_originals),
    }
    _write_json(output / "diagnostic.json", diagnostic)
    original_hashes = dict(sorted(all_originals.items()))
    derived_hashes = dict(sorted(derived.items()))
    diagnostic_hashes = dict(sorted({**all_originals, **derived}.items()))
    summary = {
        "selection": {"printer": SELECTED["machine"], "process": SELECTED["process"],
                      "filament": SELECTED["filament"]},
        "variants": {
            "actual": {
                "manifest": "actual.json",
                "presetManifestSchema": SCHEMA,
                "presetManifestSha256": hashlib.sha256((output / "actual.json").read_bytes()).hexdigest(),
                "presetFilesSha256": original_hashes,
                "sanitization": {"mode": "none", "overrides": {}},
            },
            "diagnostic": {
                "manifest": "diagnostic.json",
                "presetManifestSchema": SCHEMA,
                "presetManifestSha256": hashlib.sha256((output / "diagnostic.json").read_bytes()).hexdigest(),
                "presetFilesSha256": diagnostic_hashes,
                "sanitization": {
                    "mode": "macro-free-diagnostic",
                    "overrides": {f"{kind}/diagnostic_{kind}.json": overrides
                                  for kind, overrides in diagnostic_values.items()},
                },
            },
        },
    }
    _write_json(output / "summary.json", summary)
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path, help="pinned OrcaSlicer source root")
    parser.add_argument("--output", required=True, type=Path, help="new fixture directory")
    args = parser.parse_args()
    prepare(args.source.resolve(), args.output.resolve())


if __name__ == "__main__":
    main()
