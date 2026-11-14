#!/usr/bin/env python3
"""Build a disposable, signed UI-test APK without changing the original APK."""
import argparse
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path


def signature_entry(name: str) -> bool:
    return bool(re.fullmatch(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))", name, re.IGNORECASE))


def strip_apk(source: Path, destination: Path) -> None:
    if source.resolve() == destination.resolve():
        raise ValueError("The disposable APK must not overwrite the original")
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(destination, "w", allowZip64=True) as stripped:
        for entry in original.infolist():
            if entry.filename.startswith("lib/") or signature_entry(entry.filename):
                continue
            with original.open(entry) as src, stripped.open(entry, "w") as dst:
                while chunk := src.read(1024 * 1024):
                    dst.write(chunk)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--zipalign", required=True)
    parser.add_argument("--apksigner", required=True)
    args = parser.parse_args()
    if args.input.resolve() == args.output.resolve():
        parser.error("output must differ from input")
    if args.output.exists():
        parser.error("output already exists")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="ui-apk-", dir=args.output.parent) as directory:
        stripped = Path(directory) / "stripped.apk"
        aligned = Path(directory) / "aligned.apk"
        strip_apk(args.input, stripped)
        subprocess.run([args.zipalign, "-f", "4", str(stripped), str(aligned)], check=True)
        subprocess.run([args.apksigner, "sign", "--ks", str(args.keystore), "--ks-key-alias", "androiddebugkey",
                        "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", str(args.output),
                        str(aligned)], check=True)
        subprocess.run([args.apksigner, "verify", str(args.output)], check=True)


if __name__ == "__main__":
    main()
