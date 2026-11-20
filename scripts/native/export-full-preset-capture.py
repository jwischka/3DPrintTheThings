#!/usr/bin/env python3
"""Export full-preset captures and independently verify their input/output bytes."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools/parity"))
from parity import analyze
from provenance import build_provenance

PACKAGE = "io.github.jwischka.threedprintthethings"
FIXTURE = REPO / "tools/parity/fixtures/cube_10mm.stl"
VARIANTS = ("actual", "diagnostic")


def validate_capture(directory: Path, bundle: Path, engine_pin: str,
                     fixture: Path = FIXTURE) -> None:
    summary = json.loads((bundle / "summary.json").read_text(encoding="utf-8"))
    for variant in VARIANTS:
        gcode, metadata = directory / f"{variant}.gcode", directory / f"{variant}.json"
        if not 0 < gcode.stat().st_size <= 32 * 1024 * 1024:
            raise ValueError("Invalid captured G-code size")
        if not 0 < metadata.stat().st_size <= 1024 * 1024:
            raise ValueError("Invalid capture metadata size")
        report = json.loads(metadata.read_text(encoding="utf-8"))
        expected = build_provenance(summary["variants"][variant], bundle,
                                    fixture, gcode, engine_pin)
        if report.get("parityProvenance") != expected:
            raise ValueError(f"{variant}: captured provenance does not match verified bytes")
        metrics = analyze(gcode)
        if not metrics["config_block_valid"] or report.get("config") != metrics["config"]:
            raise ValueError(f"{variant}: captured configuration does not match G-code")
        if metrics["layers"] <= 0 or sum(metrics["extrusion_mm_by_tool"].values()) <= 0:
            raise ValueError(f"{variant}: captured G-code has no printable extrusion")
        # Opaque macros are retained here. The unchanged parity comparator will
        # reject them; successful export is evidence of slicing, not parity.
        (directory / f"{variant}.metrics.json").write_text(
            json.dumps(metrics, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--engine-pin", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists():
        parser.error("output directory must be new")
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=".preset-capture-", dir=output.parent))
    try:
        for variant in VARIANTS:
            for extension in ("gcode", "json"):
                name = f"{variant}.{extension}"
                with (staging / name).open("wb") as destination:
                    subprocess.run([args.adb, "exec-out", "run-as", PACKAGE, "cat",
                                    f"files/full-preset-capture/{name}"],
                                   stdin=subprocess.DEVNULL, stdout=destination,
                                   stderr=subprocess.PIPE, timeout=90, check=True)
        validate_capture(staging, args.bundle, args.engine_pin)
        if output.exists():
            raise ValueError("output directory appeared during capture")
        staging.rename(output)
        print("Full-preset input and output hashes verified")
        return 0
    except (ValueError, KeyError, OSError, subprocess.SubprocessError) as error:
        print(f"Preset capture export failed: {error}", file=sys.stderr)
        return 1
    finally:
        if staging.exists():
            shutil.rmtree(staging)


if __name__ == "__main__":
    raise SystemExit(main())
