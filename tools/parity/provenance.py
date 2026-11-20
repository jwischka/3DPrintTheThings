#!/usr/bin/env python3
"""Strict provenance binding for paired desktop and Android G-code captures.

This gate proves that two artifacts declare the same slicing inputs and that
each metadata file is bound to the G-code bytes being compared. It does not
replace parity.compare or establish that a preset was applied by the engine.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path, PurePosixPath

from parity import analyze, config_diff, format_config_diff


PROVENANCE_KEY = "parityProvenance"
MANIFEST_SCHEMA = "orca-native-presets-v1"
HEX_256 = re.compile(r"^[0-9a-f]{64}$")
SOURCE_PIN = re.compile(r"^(?:[0-9a-f]{40}|[0-9a-f]{64})$")


class ProvenanceError(ValueError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _safe_relative_json_path(value: object) -> str:
    if not isinstance(value, str) or not value or "\\" in value or ":" in value:
        raise ProvenanceError(f"invalid preset path: {value!r}")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in ("", ".", "..") for part in value.split("/")) or path.suffix.lower() != ".json":
        raise ProvenanceError(f"invalid preset path: {value!r}")
    return value


def _validate_sanitization(value: object) -> dict:
    if not isinstance(value, dict) or set(value) != {"mode", "overrides"}:
        raise ProvenanceError("sanitization must contain exactly mode and overrides")
    mode, overrides = value["mode"], value["overrides"]
    if mode not in ("none", "macro-free-diagnostic") or not isinstance(overrides, dict):
        raise ProvenanceError("invalid sanitization mode or overrides")
    if mode == "none" and overrides:
        raise ProvenanceError("sanitization mode none cannot declare overrides")
    if mode == "macro-free-diagnostic" and not overrides:
        raise ProvenanceError("diagnostic sanitization must declare deterministic overrides")
    for path, settings in overrides.items():
        _safe_relative_json_path(path)
        if not isinstance(settings, dict) or not settings:
            raise ProvenanceError(f"invalid diagnostic overrides for {path!r}")
    return value


def _validate_hash_map(value: object) -> dict[str, str]:
    if not isinstance(value, dict) or len(value) < 3:
        raise ProvenanceError("presetFilesSha256 must contain the full machine/process/filament closure")
    result = {}
    for path, digest in value.items():
        safe_path = _safe_relative_json_path(path)
        if safe_path in result or not isinstance(digest, str) or not HEX_256.fullmatch(digest):
            raise ProvenanceError(f"invalid preset hash entry for {path!r}")
        result[safe_path] = digest
    return dict(sorted(result.items()))


def build_provenance(bundle: dict, bundle_root: Path, input_path: Path, gcode_path: Path,
                     engine_source_pin: str, sanitization: dict | None = None) -> dict:
    """Build nested provenance from one prepare_presets summary variant.

    ``bundle`` is ``summary['variants']['actual']`` or ``diagnostic``. The
    manifest and every declared closure file are rehashed from ``bundle_root``;
    its catalog plus selected references must enumerate exactly that closure.
    """
    if not isinstance(bundle, dict):
        raise ProvenanceError("preset bundle metadata must be an object")
    if not isinstance(engine_source_pin, str) or not SOURCE_PIN.fullmatch(engine_source_pin):
        raise ProvenanceError("engine source pin must be a full 40- or 64-character lowercase commit hash")
    if bundle.get("presetManifestSchema") != MANIFEST_SCHEMA:
        raise ProvenanceError("unsupported or missing preset manifest schema")
    manifest_hash = bundle.get("presetManifestSha256")
    if not isinstance(manifest_hash, str) or not HEX_256.fullmatch(manifest_hash):
        raise ProvenanceError("invalid or missing preset manifest SHA-256")
    manifest = bundle.get("manifest")
    if not isinstance(manifest, str) or not manifest.endswith(".json") or "/" in manifest or "\\" in manifest:
        raise ProvenanceError("manifest must be a safe JSON filename")
    sanitizer = _validate_sanitization(sanitization if sanitization is not None else bundle.get("sanitization"))
    root = Path(bundle_root).resolve(strict=True)
    manifest_path = _confined_file(root, manifest)
    manifest_bytes = manifest_path.read_bytes()
    if hashlib.sha256(manifest_bytes).hexdigest() != manifest_hash:
        raise ProvenanceError("preset manifest bytes do not match summary SHA-256")
    try:
        manifest_value = json.loads(manifest_bytes)
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise ProvenanceError("preset manifest is not valid JSON") from exc
    if not isinstance(manifest_value, dict) or manifest_value.get("schema") != MANIFEST_SCHEMA:
        raise ProvenanceError("preset manifest schema is invalid")
    preset_hashes = _verify_preset_closure(root, manifest_value, bundle.get("presetFilesSha256"))
    _verify_declared_overrides(root, preset_hashes, sanitizer)
    return {
        "schemaVersion": 1,
        "engineSourcePin": engine_source_pin,
        "inputSha256": sha256_file(Path(input_path)),
        "gcodeSha256": sha256_file(Path(gcode_path)),
        "presetManifestSchema": MANIFEST_SCHEMA,
        "presetManifest": manifest,
        "presetManifestSha256": manifest_hash,
        "presetFilesSha256": preset_hashes,
        "sanitization": sanitizer,
    }


def _confined_file(root: Path, relative: str) -> Path:
    safe = _safe_relative_json_path(relative)
    candidate = root / safe
    try:
        resolved = candidate.resolve(strict=True)
        resolved.relative_to(root)
    except (OSError, ValueError) as exc:
        raise ProvenanceError(f"preset path escapes bundle or is missing: {relative}") from exc
    if not resolved.is_file():
        raise ProvenanceError(f"preset path is not a file: {relative}")
    return resolved


def _verify_preset_closure(root: Path, manifest: dict, declared: object) -> dict[str, str]:
    hashes = _validate_hash_map(declared)
    paths = []
    for key in ("printer", "process"):
        paths.append(_safe_relative_json_path(manifest.get(key)))
    filaments = manifest.get("filaments")
    catalog = manifest.get("catalog")
    if not isinstance(filaments, list) or not filaments or not isinstance(catalog, list):
        raise ProvenanceError("preset manifest requires nonempty filaments and a catalog list")
    paths.extend(_safe_relative_json_path(value) for value in filaments)
    paths.extend(_safe_relative_json_path(value) for value in catalog)
    if len(set(paths)) != len(hashes) or set(paths) != set(hashes):
        raise ProvenanceError("manifest references do not exactly enumerate the preset hash closure")
    for relative, expected_hash in hashes.items():
        actual_hash = sha256_file(_confined_file(root, relative))
        if actual_hash != expected_hash:
            raise ProvenanceError(f"preset bytes do not match summary SHA-256: {relative}")
    return hashes


def _verify_declared_overrides(root: Path, hashes: dict[str, str], sanitization: dict) -> None:
    for relative, overrides in sanitization["overrides"].items():
        if relative not in hashes:
            raise ProvenanceError(f"sanitization override is outside preset hash closure: {relative}")
        try:
            value = json.loads(_confined_file(root, relative).read_text(encoding="utf-8"))
        except (UnicodeError, json.JSONDecodeError) as exc:
            raise ProvenanceError(f"diagnostic preset is invalid JSON: {relative}") from exc
        if not isinstance(value, dict) or any(value.get(key) != expected for key, expected in overrides.items()):
            raise ProvenanceError(f"diagnostic preset bytes do not contain declared overrides: {relative}")


def _load_json(path: Path) -> dict:
    def unique_object(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise ProvenanceError(f"duplicate JSON key in {path.name}: {key}")
            value[key] = item
        return value

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ProvenanceError(f"cannot read capture JSON {path.name}: {exc.__class__.__name__}") from exc
    if not isinstance(value, dict):
        raise ProvenanceError(f"capture JSON {path.name} must contain an object")
    return value


def _validate_capture(report: dict, gcode_path: Path, label: str) -> dict:
    provenance = report.get(PROVENANCE_KEY)
    required = {"schemaVersion", "engineSourcePin", "inputSha256", "gcodeSha256",
                "presetManifestSchema", "presetManifest", "presetManifestSha256",
                "presetFilesSha256", "sanitization"}
    if not isinstance(provenance, dict) or required - provenance.keys():
        missing = sorted(required - provenance.keys()) if isinstance(provenance, dict) else sorted(required)
        raise ProvenanceError(f"{label}: missing parityProvenance fields: {', '.join(missing)}")
    if provenance["schemaVersion"] != 1:
        raise ProvenanceError(f"{label}: unsupported provenance schemaVersion")
    if not isinstance(provenance["engineSourcePin"], str) or not SOURCE_PIN.fullmatch(provenance["engineSourcePin"]):
        raise ProvenanceError(f"{label}: engineSourcePin must be a full commit hash")
    for key in ("inputSha256", "gcodeSha256", "presetManifestSha256"):
        if not isinstance(provenance[key], str) or not HEX_256.fullmatch(provenance[key]):
            raise ProvenanceError(f"{label}: invalid {key}")
    if provenance["presetManifestSchema"] != MANIFEST_SCHEMA:
        raise ProvenanceError(f"{label}: unsupported preset manifest schema")
    manifest = provenance["presetManifest"]
    if not isinstance(manifest, str) or not manifest.endswith(".json") or "/" in manifest or "\\" in manifest:
        raise ProvenanceError(f"{label}: invalid presetManifest")
    provenance["presetFilesSha256"] = _validate_hash_map(provenance["presetFilesSha256"])
    provenance["sanitization"] = _validate_sanitization(provenance["sanitization"])
    if sha256_file(gcode_path) != provenance["gcodeSha256"]:
        raise ProvenanceError(f"{label}: G-code bytes do not match parityProvenance.gcodeSha256")
    parsed = analyze(gcode_path)
    if report.get("config") != parsed["config"]:
        raise ProvenanceError(f"{label}: capture config does not match G-code CONFIG_BLOCK")
    if report.get("config_block_valid") is not True or parsed["config_block_valid"] is not True:
        raise ProvenanceError(f"{label}: G-code CONFIG_BLOCK is missing or unbalanced")
    return {"provenance": provenance, "config": parsed["config"]}


def validate_pair(expected_json: Path, expected_gcode: Path,
                  actual_json: Path, actual_gcode: Path) -> list[str]:
    """Return errors when captures are not strictly bound to identical inputs."""
    left = _validate_capture(_load_json(expected_json), expected_gcode, "expected")
    right = _validate_capture(_load_json(actual_json), actual_gcode, "actual")
    errors = []
    for key in ("engineSourcePin", "inputSha256", "presetManifestSchema", "presetManifest",
                "presetManifestSha256", "presetFilesSha256", "sanitization"):
        if left["provenance"][key] != right["provenance"][key]:
            errors.append(f"provenance mismatch: {key}")
    if left["config"] != right["config"]:
        errors.append(format_config_diff(config_diff(left["config"], right["config"])))
    return errors


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("expected_json", type=Path)
    parser.add_argument("expected_gcode", type=Path)
    parser.add_argument("actual_json", type=Path)
    parser.add_argument("actual_gcode", type=Path)
    args = parser.parse_args(argv)
    try:
        errors = validate_pair(args.expected_json, args.expected_gcode, args.actual_json, args.actual_gcode)
    except ProvenanceError as exc:
        errors = [str(exc)]
    print("PROVENANCE MATCH" if not errors else "PROVENANCE FAIL")
    for error in errors:
        print(f"- {error}")
    if not errors:
        print("Run the unchanged parity.py compare next; provenance match alone is not a parity pass.")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
