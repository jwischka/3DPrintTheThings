#!/usr/bin/env python3
"""Install UI-only APKs and run the isolated UI instrumentation classes."""
import argparse
import re
import subprocess
import sys
from pathlib import Path


TEST_CLASS = "com.u1.slicer.browse.SiteBrowserInstrumentedTest,com.u1.slicer.ui.printer.PrinterSettingsInstrumentedTest"
RUNNER = "io.github.jwischka.threedprintthethings.test/androidx.test.runner.AndroidJUnitRunner"


def passed(output: str, returncode: int) -> bool:
    if returncode != 0 or re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|^INSTRUMENTATION_STATUS_CODE:\s*-[1234]\s*$|^INSTRUMENTATION_RESULT:\s*shortMsg=", output, re.IGNORECASE | re.MULTILINE):
        return False
    summary = re.search(r"\bOK\s*\(\s*(\d+)\s+tests?\s*\)", output)
    codes = re.findall(r"^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", output, re.MULTILINE)
    statuses = re.findall(r"^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*$", output, re.MULTILINE)
    return bool(summary and int(summary.group(1)) > 0 and codes and codes[-1] == "-1" and "0" in statuses)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", type=Path, required=True)
    parser.add_argument("--test", type=Path, required=True)
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--timeout", type=int, default=300)
    args = parser.parse_args()
    args.log.parent.mkdir(parents=True, exist_ok=True)
    with args.log.open("w", encoding="utf-8") as log:
        def capture_logcat() -> None:
            try:
                result = subprocess.run([args.adb, "logcat", "-d", "-t", "2000"],
                                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                        timeout=15)
                args.log.with_name(args.log.stem + "-logcat.log").write_bytes(result.stdout[:2 * 1024 * 1024])
            except (OSError, subprocess.TimeoutExpired):
                log.write("Unable to collect bounded logcat.\n")

        def run(command: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
            log.write("$ " + " ".join(command) + "\n")
            log.flush()
            try:
                result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                        text=True, errors="replace", timeout=timeout)
            except subprocess.TimeoutExpired as exc:
                output = exc.stdout or b""
                log.write(output.decode("utf-8", "replace") if isinstance(output, bytes) else output)
                log.write("\nCommand timed out\n")
                raise
            log.write(result.stdout)
            log.flush()
            return result

        try:
            for apk in (args.app, args.test):
                result = run([args.adb, "install", "-r", str(apk)], 120)
                if result.returncode != 0 or "Success" not in result.stdout:
                    return 1
            result = run([args.adb, "shell", "am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS, RUNNER], args.timeout)
            if not passed(result.stdout, result.returncode):
                log.write("Instrumentation failed or did not report a positive test count.\n")
                capture_logcat()
                return 1
            return 0
        except (OSError, subprocess.TimeoutExpired) as exc:
            log.write(f"Unable to complete browser instrumentation: {exc}\n")
            capture_logcat()
            return 1


if __name__ == "__main__":
    sys.exit(main())
