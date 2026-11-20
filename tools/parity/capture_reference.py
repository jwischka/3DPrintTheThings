#!/usr/bin/env python3
"""Run an independent host executable and bind its captures to prepared presets."""
import argparse
import json
from pathlib import Path
import subprocess

from parity import analyze
from provenance import build_provenance


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--executable", required=True, type=Path)
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--resources", required=True, type=Path)
    parser.add_argument("--engine-pin", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output must be a new directory")
    summary = json.loads((args.bundle / "summary.json").read_text(encoding="utf-8"))
    args.output.mkdir(parents=True)
    for variant in ("actual", "diagnostic"):
        metadata = summary["variants"][variant]
        destination = args.output / variant
        destination.mkdir()
        with (destination / "reference.log").open("wb") as log:
            subprocess.run([str(args.executable.resolve()),
                            str((args.bundle / metadata["manifest"]).resolve()),
                            str(args.input.resolve()), str(destination.resolve()),
                            str(args.resources.resolve()), variant],
                           stdout=log, stderr=subprocess.STDOUT, check=True, timeout=600)
        gcode = destination / "cube.gcode"
        metrics = analyze(gcode)
        if not metrics["config_block_valid"] or metrics["layers"] <= 0 or sum(metrics["extrusion_mm_by_tool"].values()) <= 0:
            raise ValueError(f"{variant}: reference did not produce printable G-code")
        report = dict(metrics)
        report["parityProvenance"] = build_provenance(
            metadata, args.bundle, args.input, gcode, args.engine_pin)
        (destination / "capture.json").write_text(
            json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(f"Captured {variant} Linux reference; structural eligibility is checked separately")


if __name__ == "__main__":
    main()
