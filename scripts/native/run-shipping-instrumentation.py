#!/usr/bin/env python3
"""Run one ARM64 shipping-APK slice capture on an x86_64 Android emulator."""

import argparse
import hashlib
import importlib.util
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path


PACKAGE = "io.github.jwischka.threedprintthethings"
TEST_CLASS = "com.u1.slicer.slicing.AndroidParityCaptureTest#captureCube10mmGcode"
RUNNER = "io.github.jwischka.threedprintthethings.test/androidx.test.runner.AndroidJUnitRunner"
TARGET_LIBRARY = "lib/arm64-v8a/libprusaslicer-jni.so"
REPO = Path(__file__).resolve().parents[2]
CHECKED_IN_LIB_DIR = REPO / "app/src/main/jniLibs/arm64-v8a"
MAX_LOG_BYTES = 8 * 1024 * 1024

INSTRUMENTATION_RUNNER_PATH = REPO / "tools/run-browser-instrumentation.py"
RUNNER_SPEC = importlib.util.spec_from_file_location("instrumentation_runner", INSTRUMENTATION_RUNNER_PATH)
if RUNNER_SPEC is None or RUNNER_SPEC.loader is None:
    raise RuntimeError("Cannot load shared instrumentation result parser")
INSTRUMENTATION_RUNNER = importlib.util.module_from_spec(RUNNER_SPEC)
RUNNER_SPEC.loader.exec_module(INSTRUMENTATION_RUNNER)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def inventory_apk(path: Path) -> dict:
    libraries = []
    with zipfile.ZipFile(path) as apk:
        for item in sorted(apk.infolist(), key=lambda entry: entry.filename):
            if not item.filename.startswith("lib/") or not item.filename.endswith(".so"):
                continue
            digest = hashlib.sha256()
            with apk.open(item) as source:
                header = source.read(20)
                if len(header) != 20 or header[:4] != b"\x7fELF":
                    raise ValueError(f"non-ELF packaged library: {item.filename}")
                machine = int.from_bytes(header[18:20], "little")
                digest.update(header)
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(chunk)
            libraries.append({
                "path": item.filename,
                "bytes": item.file_size,
                "sha256": digest.hexdigest(),
                "elfMachine": machine,
                "elfMachineName": "AArch64" if machine == 183 else str(machine),
            })
    target = next((lib for lib in libraries if lib["path"] == TARGET_LIBRARY), None)
    if target is None or target["elfMachine"] != 183:
        raise ValueError("APK must contain an AArch64 libprusaslicer-jni.so")
    non_arm_entries = [lib["path"] for lib in libraries if not lib["path"].startswith("lib/arm64-v8a/")]
    if non_arm_entries:
        raise ValueError(f"shipping APK contains unexpected native ABIs: {non_arm_entries}")
    wrong_arch = [lib["path"] for lib in libraries if lib["path"].startswith("lib/arm64-v8a/") and lib["elfMachine"] != 183]
    if wrong_arch:
        raise ValueError(f"non-AArch64 libraries in arm64-v8a directory: {wrong_arch}")
    checked_in = {source.name: sha256_file(source) for source in sorted(CHECKED_IN_LIB_DIR.glob("*.so"))}
    packaged = {Path(lib["path"]).name: lib["sha256"] for lib in libraries}
    if not checked_in or packaged != checked_in:
        raise ValueError("APK ARM64 libraries do not exactly match checked-in jniLibs hashes")
    return {
        "apk": str(path), "apkSha256": sha256_file(path),
        "checkedInArm64Libraries": checked_in,
        "libraries": libraries,
    }


def run(adb: str, args: list[str], timeout: int = 30) -> subprocess.CompletedProcess:
    return subprocess.run([adb, *args], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          text=True, errors="replace", timeout=timeout, check=False)


def save_text(path: Path, text: str) -> None:
    path.write_text(text[:MAX_LOG_BYTES], encoding="utf-8", errors="replace")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", required=True, type=Path, help="original assembleDebug APK")
    parser.add_argument("--test", required=True, type=Path, help="matching assembleDebugAndroidTest APK")
    parser.add_argument("--report-dir", required=True, type=Path)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--timeout", type=int, default=900)
    args = parser.parse_args(argv)
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    if args.report_dir.exists():
        parser.error("--report-dir must be a new directory")
    if not args.app.is_file() or not args.test.is_file():
        parser.error("both APK files must exist")
    args.report_dir.mkdir(parents=True, exist_ok=False)
    runner_log = args.report_dir / "runner.log"
    diagnostics_log = args.report_dir / "device-diagnostics.txt"
    log = []

    def record(label: str, result: subprocess.CompletedProcess) -> str:
        output = result.stdout or ""
        log.extend([f"$ {label}", output, f"exit={result.returncode}"])
        return output

    def fail(message: str) -> int:
        log.append(f"FAIL: {message}")
        save_text(runner_log, "\n".join(log) + "\n")
        try:
            recent = run(args.adb, ["logcat", "-d", "-t", "2000"], timeout=20)
            save_text(args.report_dir / "logcat.txt", recent.stdout)
        except (OSError, subprocess.TimeoutExpired):
            pass
        print(message, file=sys.stderr)
        return 1

    try:
        inventory = {
            "app": inventory_apk(args.app),
            "test": {"apk": str(args.test), "apkSha256": sha256_file(args.test)},
            "selectedTest": TEST_CLASS,
        }
        (args.report_dir / "apk-inventory.json").write_text(
            json.dumps(inventory, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        device = record(f"{args.adb} get-state", run(args.adb, ["get-state"]))
        if device.strip() != "device":
            return fail("adb device is unavailable")
        props = {}
        for prop in ("ro.product.cpu.abi", "ro.product.cpu.abilist",
                     "ro.dalvik.vm.native.bridge", "ro.build.fingerprint"):
            output = record(f"getprop {prop}", run(args.adb, ["shell", "getprop", prop]))
            props[prop] = output.strip()
        save_text(diagnostics_log, "Emulator properties:\n" + json.dumps(props, indent=2) + "\n")
        if "x86_64" not in props["ro.product.cpu.abilist"].split(","):
            return fail("target device is not an x86_64 emulator")
        app_install = record("adb install app", run(args.adb, ["install", "-r", str(args.app)], timeout=120))
        if not re.search(r"(?:^|\n)Success(?:\r?\n|$)", app_install):
            return fail("could not install original app APK")
        test_install = record("adb install test", run(args.adb, ["install", "-r", str(args.test)], timeout=120))
        if not re.search(r"(?:^|\n)Success(?:\r?\n|$)", test_install):
            return fail("could not install original instrumentation APK")

        package_dump = record(f"dumpsys package {PACKAGE}", run(args.adb, ["shell", "dumpsys", "package", PACKAGE], timeout=40))
        if not package_dump:
            return fail("dumpsys package returned no package diagnostics")
        primary_abi = re.search(r"\bprimaryCpuAbi=([^\s,]+)", package_dump)
        library_dir = re.search(r"\b(?:nativeLibraryDir|legacyNativeLibraryDir)=([^\s,]+)", package_dump)
        if not primary_abi or primary_abi.group(1) != "arm64-v8a":
            return fail("PackageManager did not select arm64-v8a for the shipping APK")
        package_diag = ["Emulator properties:", json.dumps(props, indent=2),
                        f"primaryCpuAbi={primary_abi.group(1)}",
                        f"nativeLibraryDir={library_dir.group(1) if library_dir else '<not reported; APK loading is permitted>'}",
                        "", package_dump]
        save_text(diagnostics_log, "\n".join(package_diag))

        with INSTRUMENTATION_RUNNER.LiveLogcat(args.adb, args.report_dir / "live-logcat.log"):
            instrument_result = run(args.adb,
                ["shell", "am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS, RUNNER],
                timeout=args.timeout)
        instrumentation = record(
            f"am instrument exact selector {TEST_CLASS}",
            instrument_result,
        )
        try:
            recent_logcat = run(args.adb, ["logcat", "-d", "-t", "2000"], timeout=20)
            if recent_logcat.returncode == 0:
                save_text(args.report_dir / "logcat.txt", recent_logcat.stdout)
            else:
                log.append("Could not collect logcat after instrumentation.")
        except (OSError, subprocess.TimeoutExpired):
            log.append("Could not collect logcat after instrumentation.")
        if not INSTRUMENTATION_RUNNER.passed(instrumentation, instrument_result.returncode, expected_tests=1):
            return fail("ARM64 shipping-APK cube instrumentation did not pass exactly one test")
        capture_result = run(args.adb,
            ["exec-out", "run-as", PACKAGE, "cat", "files/parity-capture/cube_10mm.android.json"],
            timeout=30)
        if capture_result.returncode != 0:
            return fail("could not read the completed cube capture provenance")
        capture = json.loads(capture_result.stdout)
        supported_abis = capture.get("deviceAbis")
        if not isinstance(supported_abis, list) or "arm64-v8a" not in supported_abis:
            return fail("app runtime does not advertise arm64-v8a support through native bridge")
        log.extend(["Runtime Build.SUPPORTED_ABIS:", json.dumps(supported_abis)])
        save_text(diagnostics_log, diagnostics_log.read_text(encoding="utf-8") +
                  "\nRuntime Build.SUPPORTED_ABIS:\n" + json.dumps(supported_abis) + "\n")
        if inventory["app"]["apkSha256"] != sha256_file(args.app):
            return fail("app APK hash changed during instrumentation")
        if inventory["test"]["apkSha256"] != sha256_file(args.test):
            return fail("instrumentation APK hash changed during instrumentation")
        log.append("PASS: exact one-test ARM64 shipping APK capture; local APK hashes unchanged")
        save_text(runner_log, "\n".join(log) + "\n")
        print("Passed ARM64 shipping APK cube capture on x86_64 emulator")
        return 0
    except (OSError, ValueError, zipfile.BadZipFile, subprocess.TimeoutExpired) as exc:
        return fail(f"shipping instrumentation runner failed: {exc}")


if __name__ == "__main__":
    sys.exit(main())
