#!/usr/bin/env python3
"""Export and validate the app-private Android cube slicing capture."""

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
FIXTURE = REPO / "tools/parity/fixtures/cube_10mm.stl"
ASSETS = REPO / "app/src/main/assets"
PROFILES = (
    "orca_profiles/printer/snapmaker_u1.json",
    "orca_profiles/process/standard_0.20mm.json",
    "orca_profiles/filament/pla.json",
)
PACKAGE = "io.github.jwischka.threedprintthethings"
GCODE_NAME = "cube_10mm.android.gcode"
JSON_NAME = "cube_10mm.android.json"
MAX_GCODE_BYTES = 32 * 1024 * 1024
MAX_JSON_BYTES = 256 * 1024
HEX_SHA256 = re.compile(r"[0-9a-f]{64}\Z")


def digest(path: Path) -> str:
    sha = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            sha.update(chunk)
    return sha.hexdigest()


def validate_capture(gcode: Path, metadata: Path, fixture: Path = FIXTURE,
                     assets: Path = ASSETS) -> None:
    gcode_size = gcode.stat().st_size
    json_size = metadata.stat().st_size
    if not 0 < gcode_size <= MAX_GCODE_BYTES:
        raise ValueError("captured G-code is empty or exceeds the cube capture limit")
    if not 0 < json_size <= MAX_JSON_BYTES:
        raise ValueError("capture provenance is empty or too large")
    report = json.loads(metadata.read_text(encoding="utf-8"))
    if not isinstance(report, dict) or report.get("schemaVersion") != 1:
        raise ValueError("unsupported capture provenance")
    if report.get("inputFixture") != "tools/parity/fixtures/cube_10mm.stl":
        raise ValueError("capture names a different input fixture")
    if not isinstance(report.get("engineVersion"), str) or not report["engineVersion"].strip():
        raise ValueError("capture lacks engine version")
    abis = report.get("deviceAbis")
    if not isinstance(abis, list) or not abis or not all(isinstance(abi, str) and abi for abi in abis):
        raise ValueError("capture lacks device ABI provenance")
    if not isinstance(report.get("sliceConfig"), dict) or not report["sliceConfig"]:
        raise ValueError("capture lacks serialized SliceConfig")
    if type(report.get("gcodeBytes")) is not int or report["gcodeBytes"] != gcode_size:
        raise ValueError("captured G-code byte count does not match provenance")
    if report.get("gcodeSha256") != digest(gcode):
        raise ValueError("captured G-code SHA-256 does not match provenance")
    if report.get("inputSha256") != digest(fixture):
        raise ValueError("capture input SHA-256 does not match repository fixture")
    hashes = report.get("bundledProfileSha256")
    if not isinstance(hashes, dict) or set(hashes) != set(PROFILES):
        raise ValueError("capture lacks bundled profile hashes")
    for profile in PROFILES:
        claimed = hashes[profile]
        if not isinstance(claimed, str) or not HEX_SHA256.fullmatch(claimed) or claimed != digest(assets / profile):
            raise ValueError(f"bundled profile SHA-256 differs: {profile}")


def export_file(adb: str, remote_name: str, destination: Path, limit: int) -> None:
    remote = f"files/parity-capture/{remote_name}"
    with destination.open("wb") as output:
        try:
            result = subprocess.run(
                [adb, "exec-out", "run-as", PACKAGE, "cat", remote],
                stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.PIPE,
                timeout=90, check=False,
            )
        except subprocess.TimeoutExpired as exc:
            raise ValueError(f"adb timed out exporting {remote_name}") from exc
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", "replace").strip()[:300]
        raise ValueError(f"adb could not export {remote_name}: {detail or 'unknown error'}")
    if not 0 < destination.stat().st_size <= limit:
        raise ValueError(f"exported {remote_name} is empty or exceeds its size limit")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="new directory for verified capture files")
    parser.add_argument("--adb", default="adb", help="adb executable")
    args = parser.parse_args(argv)
    output = args.output.resolve()
    if output.exists():
        parser.error("--output must name a new directory")
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=f".{output.name}.staging-", dir=output.parent))
    try:
        gcode = staging / GCODE_NAME
        metadata = staging / JSON_NAME
        export_file(args.adb, GCODE_NAME, gcode, MAX_GCODE_BYTES)
        export_file(args.adb, JSON_NAME, metadata, MAX_JSON_BYTES)
        validate_capture(gcode, metadata)
        if output.exists():
            raise ValueError("output directory appeared during export")
        staging.rename(output)
        print(f"Verified Android capture: {output}")
        return 0
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"Capture export failed: {exc}", file=sys.stderr)
        return 1
    finally:
        if staging.exists():
            shutil.rmtree(staging)


if __name__ == "__main__":
    sys.exit(main())
