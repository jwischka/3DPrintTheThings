#!/usr/bin/env python3
"""Structural G-code parity checks for the Android slicer experiment.

This deliberately compares observable structure and selected metadata, not
byte-for-byte output or printer behavior.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


CONFIG_DIFF_LIST_LIMIT = 16
IMPORTANT_CONFIG_KEYS = (
    "gcode_flavor", "layer_height", "initial_layer_print_height", "first_layer_height",
    "wall_loops", "sparse_infill_density", "outer_wall_speed", "inner_wall_speed",
    "filament_type", "filament_settings_id", "filament_diameter", "filament_max_volumetric_speed",
    "nozzle_diameter", "nozzle_temperature", "first_layer_temperature",
    "bed_temperature", "first_layer_bed_temperature", "machine_start_gcode",
    "machine_end_gcode", "extruder_count", "printer_model", "printer_settings_id",
    "printable_area", "printable_height",
)


def analyze(path: Path) -> dict:
    config: dict[str, str] = {}
    in_config = False
    config_starts = 0
    config_ends = 0
    layer_change_markers = 0
    change_layer_markers = 0
    z_layer_markers = 0
    current_tool = 0
    tool_sequence: list[int] = []
    force_relative_extrusion = False
    xyz_absolute = True
    e_position = 0.0
    retraction_debt: dict[int, float] = {}
    extrusion: dict[int, float] = {}
    max_elapsed = 0.0
    normal_estimated_seconds = None
    generic_estimated_seconds = None
    unsupported_commands: set[str] = set()

    for raw in iter_lines(path):
        line = raw.strip()
        upper = line.upper()
        if re.fullmatch(r";\s*CONFIG_BLOCK_START\s*", line, re.I):
            config_starts += 1
            in_config = True
            continue
        if re.fullmatch(r";\s*CONFIG_BLOCK_END\s*", line, re.I):
            config_ends += 1
            in_config = False
            continue
        if in_config and line.startswith(";") and "=" in line:
            key, value = line[1:].split("=", 1)
            config[key.strip()] = value.strip()
        if re.match(r";\s*LAYER_CHANGE\b", line, re.I):
            layer_change_markers += 1
        elif re.match(r";\s*CHANGE_LAYER\b", line, re.I):
            change_layer_markers += 1
        elif re.match(r";\s*Z\s*:", line, re.I):
            z_layer_markers += 1
        elapsed = re.match(r";\s*TIME_ELAPSED\s*:\s*([0-9.]+)", line, re.I)
        if elapsed:
            max_elapsed = max(max_elapsed, float(elapsed.group(1)))
        estimate = re.match(r";\s*estimated printing time(?: \(([^)]*)\))?\s*=\s*(.*)", line, re.I)
        if estimate and (not estimate.group(1) or estimate.group(1).strip().lower() == "normal mode"):
            seconds = parse_duration(estimate.group(2))
            if seconds is not None:
                if estimate.group(1):
                    normal_estimated_seconds = seconds
                else:
                    generic_estimated_seconds = seconds
        if line.startswith(";"):
            continue

        command = upper.split(";", 1)[0].strip()
        if command and not re.match(r"^(?:[GM]\d+|T\d+)(?=[A-Z\s]|$)", command):
            token = command.split(None, 1)[0]
            unsupported_commands.add(token[:80])
            continue
        if command == "G90":
            xyz_absolute = True
            continue
        if command == "G91":
            xyz_absolute = False
            continue
        if command == "M82":
            force_relative_extrusion = False
            continue
        if command == "M83":
            force_relative_extrusion = True
            continue
        tool_change = re.match(r"^T(\d+)(?=[A-Z\s]|$)", command)
        if tool_change:
            current_tool = int(tool_change.group(1))
            tool_sequence.append(current_tool)
            continue
        g_command = re.match(r"^G(\d+)(?=[A-Z\s]|$)", command)
        g_number = int(g_command.group(1)) if g_command else None
        if g_number == 92:
            params = parse_params(command)
            if e_parameter_error(command, params):
                unsupported_commands.add("invalid or repeated E parameter")
                continue
            if "E" in params:
                e_position = params["E"]
            continue
        if g_number not in (0, 1, 2, 3):
            if re.match(r"^M200(?=[A-Z\s]|$)", command) and parse_params(command).get("D", 0) > 0:
                unsupported_commands.add("M200 volumetric extrusion")
            elif g_number is not None and "E" in parse_params(command):
                unsupported_commands.add(f"{command.split()[0]} with E parameter")
            continue
        params = parse_params(command)
        if e_parameter_error(command, params):
            unsupported_commands.add("invalid or repeated E parameter")
            continue
        if "E" not in params:
            continue
        value = params["E"]
        e_absolute = xyz_absolute and not force_relative_extrusion
        if e_absolute:
            delta = value - e_position
            e_position = value
        else:
            delta = value
            e_position += value
        if delta > 0:
            recovered = min(delta, retraction_debt.get(current_tool, 0.0))
            retraction_debt[current_tool] = retraction_debt.get(current_tool, 0.0) - recovered
            consumed = delta - recovered
            if consumed > 0:
                extrusion[current_tool] = extrusion.get(current_tool, 0.0) + consumed
        elif delta < 0:
            retraction_debt[current_tool] = retraction_debt.get(current_tool, 0.0) - delta

    duration = max_elapsed if max_elapsed > 0 else (
        normal_estimated_seconds if normal_estimated_seconds is not None else generic_estimated_seconds
    )
    layers = (layer_change_markers if layer_change_markers > 0 else
              change_layer_markers if change_layer_markers > 0 else z_layer_markers)
    return {
        "config": dict(sorted(config.items())),
        "config_block_valid": config_starts == 1 and config_ends == 1 and not in_config,
        "layers": layers,
        "tool_sequence": tool_sequence,
        "extrusion_mm_by_tool": {str(k): round(v, 4) for k, v in sorted(extrusion.items())},
        "duration_seconds": round(duration, 2) if duration is not None else None,
        "unsupported_commands": sorted(unsupported_commands),
    }


def iter_lines(path: Path):
    with path.open(encoding="utf-8", errors="replace") as source:
        yield from source


def parse_params(command: str) -> dict[str, float]:
    result = {}
    number = r"[+-]?(?:\d+(?:\.\d*)?|\.\d+)"
    for key, value in re.findall(rf"([A-Z])\s*({number})", command):
        result[key] = float(value)
    return result


def e_parameter_error(command: str, params: dict[str, float]) -> bool:
    mentions = len(re.findall(r"E(?=[\s+\-.\d]|$)", command))
    return mentions > 1 or (mentions == 1 and "E" not in params)


def parse_duration(value: str) -> float | None:
    total = 0
    found = False
    for number, unit in re.findall(r"(\d+(?:\.\d+)?)\s*([dhms])", value.lower()):
        total += float(number) * {"d": 86400, "h": 3600, "m": 60, "s": 1}[unit]
        found = True
    return total if found else None


def compare(expected: dict, actual: dict, *, extrusion_abs_mm: float = 0.1,
            extrusion_rel: float = 0.005, duration_abs_seconds: float = 1.0,
            duration_rel: float = 0.01) -> list[str]:
    errors = []
    for label, report in (("expected", expected), ("actual", actual)):
        errors.extend(validate_report(label, report))
    if expected.get("config") != actual.get("config"):
        errors.append(format_config_diff(config_diff(expected.get("config", {}), actual.get("config", {}))))
    for key in ("layers", "tool_sequence"):
        if expected.get(key) != actual.get(key):
            errors.append(f"{key}: expected {expected.get(key)!r}, got {actual.get(key)!r}")
    tools = sorted(set(expected.get("extrusion_mm_by_tool", {})) |
                   set(actual.get("extrusion_mm_by_tool", {})))
    for tool in tools:
        a = float(expected.get("extrusion_mm_by_tool", {}).get(tool, 0))
        b = float(actual.get("extrusion_mm_by_tool", {}).get(tool, 0))
        scale = max(abs(a), abs(b))
        tolerance = scale * extrusion_rel
        if scale >= 100.0:
            tolerance = max(tolerance, extrusion_abs_mm)
        if abs(a - b) > tolerance:
            errors.append(f"tool {tool} extrusion: {a:.2f} vs {b:.2f} mm (tol {tolerance:.2f})")
    a, b = expected.get("duration_seconds"), actual.get("duration_seconds")
    if a is not None and b is not None:
        scale = max(abs(float(a)), abs(float(b)))
        tolerance = scale * duration_rel
        if scale >= 60.0:
            tolerance = max(tolerance, duration_abs_seconds)
        if abs(float(a) - float(b)) > tolerance:
            errors.append(f"duration: {a} vs {b} seconds")
    return errors


def _short_value(value) -> str:
    text = str(value).replace("\r", "\\r").replace("\n", "\\n")
    return text if len(text) <= 96 else f"{text[:93]}..."


def config_diff(expected, actual) -> dict:
    """Summarize exact config differences without dumping the full CONFIG_BLOCK."""
    expected = expected if isinstance(expected, dict) else {}
    actual = actual if isinstance(actual, dict) else {}
    expected_keys, actual_keys = set(expected), set(actual)
    changed = sorted(key for key in expected_keys & actual_keys if expected[key] != actual[key])
    missing_actual = sorted(expected_keys - actual_keys)
    missing_expected = sorted(actual_keys - expected_keys)
    important = {}
    for key in IMPORTANT_CONFIG_KEYS:
        if key in changed:
            important[key] = {"expected": _short_value(expected[key]), "actual": _short_value(actual[key])}
        elif key in missing_actual:
            important[key] = {"expected": _short_value(expected[key]), "actual": "<missing>"}
        elif key in missing_expected:
            important[key] = {"expected": "<missing>", "actual": _short_value(actual[key])}
    return {
        "changed_count": len(changed),
        "changed_keys": changed[:CONFIG_DIFF_LIST_LIMIT],
        "changed_keys_omitted": max(0, len(changed) - CONFIG_DIFF_LIST_LIMIT),
        "missing_in_actual_count": len(missing_actual),
        "missing_in_actual": missing_actual[:CONFIG_DIFF_LIST_LIMIT],
        "missing_in_actual_omitted": max(0, len(missing_actual) - CONFIG_DIFF_LIST_LIMIT),
        "missing_in_expected_count": len(missing_expected),
        "missing_in_expected": missing_expected[:CONFIG_DIFF_LIST_LIMIT],
        "missing_in_expected_omitted": max(0, len(missing_expected) - CONFIG_DIFF_LIST_LIMIT),
        "important_settings": important,
    }


def format_config_diff(diff: dict) -> str:
    parts = [
        "config mismatch: "
        f"{diff['changed_count']} changed, "
        f"{diff['missing_in_actual_count']} missing in actual, "
        f"{diff['missing_in_expected_count']} missing in expected"
    ]
    important = diff["important_settings"]
    if important:
        details = ", ".join(
            f"{key}={values['expected']!r} -> {values['actual']!r}"
            for key, values in important.items()
        )
        parts.append("important: " + details)
    for label, key in (("changed", "changed_keys"),
                       ("missing in actual", "missing_in_actual"),
                       ("missing in expected", "missing_in_expected")):
        names = diff[key]
        omitted = diff[key + "_omitted"]
        if names:
            suffix = f", ... (+{omitted})" if omitted else ""
            parts.append(f"{label} keys: " + ", ".join(names) + suffix)
    return "; ".join(parts)


def validate_report(label: str, report: dict, *, reject_unsupported: bool = True) -> list[str]:
    errors = []
    if not report.get("config"):
        errors.append(f"{label}: missing CONFIG_BLOCK settings")
    if not report.get("config_block_valid", False):
        errors.append(f"{label}: CONFIG_BLOCK markers are missing, duplicated, or unbalanced")
    if str(report.get("config", {}).get("gcode_flavor", "")).strip().lower() != "klipper":
        errors.append(f"{label}: only Klipper G-code flavor is supported")
    if not isinstance(report.get("layers"), int) or report["layers"] <= 0:
        errors.append(f"{label}: missing layer markers")
    duration = report.get("duration_seconds")
    if duration is None or duration <= 0:
        errors.append(f"{label}: missing positive duration metadata")
    extrusion = report.get("extrusion_mm_by_tool", {})
    if not extrusion or sum(extrusion.values()) <= 0:
        errors.append(f"{label}: missing positive extrusion data")
    unsupported = report.get("unsupported_commands", [])
    if reject_unsupported and unsupported:
        errors.append(f"{label}: unsupported commands: {', '.join(unsupported)}")
    return errors


def load_report(path: Path) -> dict:
    if path.suffix.lower() == ".json":
        return json.loads(path.read_text(encoding="utf-8"))
    return analyze(path)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    inspect_cmd = sub.add_parser("inspect", help="analyze one G-code file as JSON")
    inspect_cmd.add_argument("gcode", type=Path)
    capture = sub.add_parser("capture", help="capture a desktop CLI G-code baseline and provenance")
    capture.add_argument("gcode", type=Path, help="G-code exported by the desktop Orca CLI")
    capture.add_argument("output", type=Path, help="JSON metrics output")
    capture.add_argument("--engine", required=True, help="engine name and version/commit")
    capture.add_argument("--preset", required=True, help="preset name and version/revision")
    capture.add_argument("--preset-file", action="append", type=Path, default=[],
                         help="machine/process/filament preset input; may be repeated")
    capture.add_argument("--input-sha256", required=True, help="SHA-256 of the sliced input model")
    capture.add_argument("--cli-command", required=True, help="CLI invocation used; omit local absolute paths")
    check = sub.add_parser("compare", help="compare G-code files or a captured JSON baseline")
    check.add_argument("expected", type=Path)
    check.add_argument("actual", type=Path)
    check.add_argument("--json", action="store_true")
    check.add_argument("--extrusion-abs-eps-mm", type=float, default=0.1)
    check.add_argument("--duration-abs-eps-seconds", type=float, default=1.0)
    args = parser.parse_args(argv)
    if args.command == "inspect":
        print(json.dumps(analyze(args.gcode), indent=2, sort_keys=True))
        return 0
    if args.command == "capture":
        report = analyze(args.gcode)
        capture_errors = validate_report("capture", report, reject_unsupported=False)
        if capture_errors:
            raise SystemExit("\n".join(capture_errors))
        digest = hashlib.sha256()
        with args.gcode.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        preset_files = []
        for preset_file in args.preset_file:
            preset_digest = hashlib.sha256()
            with preset_file.open("rb") as source:
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    preset_digest.update(chunk)
            preset_files.append({"name": preset_file.name, "sha256": preset_digest.hexdigest()})
        report["provenance"] = {
            "engine": args.engine, "preset": args.preset,
            "preset_files": preset_files,
            "input_sha256": args.input_sha256, "cli_command": args.cli_command,
            "gcode_sha256": digest.hexdigest(),
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        return 0
    expected, actual = load_report(args.expected), load_report(args.actual)
    errors = compare(expected, actual,
                     extrusion_abs_mm=args.extrusion_abs_eps_mm,
                     duration_abs_seconds=args.duration_abs_eps_seconds)
    if args.json:
        expected_summary = {key: value for key, value in expected.items() if key != "config"}
        actual_summary = {key: value for key, value in actual.items() if key != "config"}
        print(json.dumps({
            "expected": expected_summary,
            "actual": actual_summary,
            "config_diff": config_diff(expected.get("config", {}), actual.get("config", {})),
            "errors": errors,
        }, indent=2))
    else:
        print("PARITY PASS" if not errors else "PARITY FAIL")
        for error in errors:
            print(f"- {error}")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
