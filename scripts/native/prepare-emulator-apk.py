#!/usr/bin/env python3
"""Create a disposable x86_64 APK containing a verified experimental engine."""
import argparse
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path


def signature(name: str) -> bool:
    return bool(re.fullmatch(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))", name, re.IGNORECASE))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--engine-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--zipalign", required=True)
    parser.add_argument("--apksigner", required=True)
    args = parser.parse_args()
    if args.input.resolve() == args.output.resolve() or args.output.exists():
        parser.error("output must be a new file separate from the original APK")
    libraries = [args.engine_dir / "libprusaslicer-jni.so", args.engine_dir / "libc++_shared.so"]
    if any(not path.is_file() for path in libraries):
        parser.error("verified experimental engine and libc++_shared are required")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="native-apk-", dir=args.output.parent) as directory:
        unsigned = Path(directory) / "unsigned.apk"
        aligned = Path(directory) / "aligned.apk"
        with zipfile.ZipFile(args.input) as original, zipfile.ZipFile(unsigned, "w", allowZip64=True) as out:
            for entry in original.infolist():
                if entry.filename.startswith("lib/") or signature(entry.filename):
                    continue
                with original.open(entry) as src, out.open(entry, "w") as dst:
                    while chunk := src.read(1024 * 1024):
                        dst.write(chunk)
            for library in libraries:
                out.write(library, "lib/x86_64/" + library.name, compress_type=zipfile.ZIP_STORED)
        subprocess.run([args.zipalign, "-p", "-f", "4", str(unsigned), str(aligned)], check=True)
        subprocess.run([args.apksigner, "sign", "--ks", str(args.keystore), "--ks-key-alias", "androiddebugkey",
                        "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", str(args.output),
                        str(aligned)], check=True)
        subprocess.run([args.apksigner, "verify", str(args.output)], check=True)


if __name__ == "__main__":
    main()
