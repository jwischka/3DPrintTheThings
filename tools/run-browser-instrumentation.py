#!/usr/bin/env python3
"""Install UI-only APKs and run the isolated UI instrumentation classes."""
import argparse
from collections import deque
import re
import subprocess
import sys
import threading
from pathlib import Path


TEST_CLASS = ",".join((
    "com.u1.slicer.browse.SiteBrowserInstrumentedTest",
    "com.u1.slicer.browse.BrowseLayoutInstrumentedTest",
    "com.u1.slicer.ui.printer.PrinterSettingsInstrumentedTest",
    "com.u1.slicer.ui.LiveSpoolMappingInstrumentedTest",
    "com.u1.slicer.library.LibraryScreenInstrumentedTest",
    "com.u1.slicer.network.SimulatedU1InstrumentedTest",
    "com.u1.slicer.LongOpServiceInstrumentedTest",
    "com.u1.slicer.viewer.GcodeRendererContextInstrumentedTest",
))
EXPECTED_TESTS = None
RUNNER = "io.github.jwischka.threedprintthethings.test/androidx.test.runner.AndroidJUnitRunner"


class LiveLogcat:
    """Keep a bounded host-side tail even when the emulator disappears."""
    def __init__(self, adb: str, path: Path):
        self.adb, self.path = adb, path
        self.lines = deque(maxlen=2000)

    def __enter__(self):
        self.process = subprocess.Popen(
            [self.adb, "logcat", "-v", "threadtime", "-s", "AndroidRuntime:E", "libc:F",
             "DEBUG:F", "ActivityManager:I", "lmkd:I", "SlicerVM:I", "WorkflowE2E:I", "SAPIL:I"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        def collect():
            while line := self.process.stdout.readline(4096):
                self.lines.append(line)
        self.thread = threading.Thread(target=collect, daemon=True)
        self.thread.start()
        return self

    def __exit__(self, *_):
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        self.thread.join(timeout=5)
        self.path.write_bytes(b"".join(self.lines.copy())[-2 * 1024 * 1024:])
        if not self.thread.is_alive():
            self.process.stdout.close()


def passed(output: str, returncode: int, expected_tests: int | None = None,
           expected_classes: tuple[str, ...] = ()) -> bool:
    if returncode != 0 or re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|^INSTRUMENTATION_STATUS_CODE:\s*-[1234]\s*$|^INSTRUMENTATION_RESULT:\s*shortMsg=", output, re.IGNORECASE | re.MULTILINE):
        return False
    summary = re.search(r"\bOK\s*\(\s*(\d+)\s+tests?\s*\)", output)
    codes = re.findall(r"^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", output, re.MULTILINE)
    statuses = re.findall(r"^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*$", output, re.MULTILINE)
    if not summary or not codes or codes[-1] != "-1":
        return False
    if expected_classes:
        completed_classes = set()
        test_class = None
        for line in output.splitlines():
            if line.startswith("INSTRUMENTATION_STATUS: class="):
                test_class = line.partition("=")[2].strip()
            elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
                if line.partition(":")[2].strip() == "0" and test_class:
                    completed_classes.add(test_class)
                test_class = None
        if not completed_classes.issuperset(expected_classes):
            return False
    count = int(summary.group(1))
    return (count > 0 and (expected_tests is None or count == expected_tests)
            and statuses.count("1") == count and statuses.count("0") == count
            and all(status in ("0", "1") for status in statuses))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", type=Path, required=True)
    parser.add_argument("--test", type=Path, required=True)
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--expected-tests", type=int, default=EXPECTED_TESTS)
    args = parser.parse_args()
    if args.expected_tests is not None and args.expected_tests <= 0:
        parser.error("--expected-tests must be positive")
    expected_classes = tuple(selector for selector in TEST_CLASS.split(",") if "#" not in selector)
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
            log.write(f"\nexit={result.returncode}\n")
            log.flush()
            return result

        try:
            for apk in (args.app, args.test):
                result = run([args.adb, "install", "-r", str(apk)], 120)
                if result.returncode != 0 or "Success" not in result.stdout:
                    return 1
            with LiveLogcat(args.adb, args.log.with_name(args.log.stem + "-live-logcat.log")):
                result = run([args.adb, "shell", "am", "instrument", "-w", "-r", "-e", "class",
                              TEST_CLASS, RUNNER], args.timeout)
                if not passed(result.stdout, result.returncode, args.expected_tests, expected_classes):
                    log.write("Instrumentation failed or selected tests did not complete.\n")
                    capture_logcat()
                    return 1
            return 0
        except (OSError, subprocess.TimeoutExpired) as exc:
            log.write(f"Unable to complete browser instrumentation: {exc}\n")
            capture_logcat()
            return 1


if __name__ == "__main__":
    sys.exit(main())
