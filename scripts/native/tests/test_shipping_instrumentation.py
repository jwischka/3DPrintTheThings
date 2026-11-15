import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "run-shipping-instrumentation.py"
spec = importlib.util.spec_from_file_location("shipping_instrumentation", SCRIPT)
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


def fake_elf(machine: int, payload: bytes) -> bytes:
    header = bytearray(20)
    header[:4] = b"\x7fELF"
    header[4] = 2  # ELFCLASS64
    header[5] = 1  # little-endian
    header[18:20] = machine.to_bytes(2, "little")
    return bytes(header) + payload


class ShippingApkInventoryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.checked_in = self.root / "jniLibs" / "arm64-v8a"
        self.checked_in.mkdir(parents=True)
        (self.checked_in / "libc++_shared.so").write_bytes(fake_elf(183, b"runtime"))
        (self.checked_in / "libprusaslicer-jni.so").write_bytes(fake_elf(183, b"native"))
        self.apk = self.root / "app.apk"
        self.checked_in_patch = patch.object(runner, "CHECKED_IN_LIB_DIR", self.checked_in)
        self.checked_in_patch.start()
        self.addCleanup(self.checked_in_patch.stop)

    def write_apk(self, *, machine=183, extra_abi=False, changed_payload=False):
        with zipfile.ZipFile(self.apk, "w") as archive:
            for name in ("libc++_shared.so", "libprusaslicer-jni.so"):
                payload = b"changed" if changed_payload and name == "libprusaslicer-jni.so" else name.encode()
                archive.writestr(f"lib/arm64-v8a/{name}", fake_elf(machine, payload))
            if extra_abi:
                archive.writestr("lib/x86_64/libextra.so", fake_elf(62, b"x86"))

    def test_valid_apk_inventory_matches_checked_in_arm64_libraries(self):
        with zipfile.ZipFile(self.apk, "w") as archive:
            for source in sorted(self.checked_in.glob("*.so")):
                archive.write(source, f"lib/arm64-v8a/{source.name}")
        report = runner.inventory_apk(self.apk)
        self.assertEqual(len(report["libraries"]), 2)
        self.assertTrue(all(item["elfMachineName"] == "AArch64" for item in report["libraries"]))
        self.assertEqual(report["checkedInArm64Libraries"]["libprusaslicer-jni.so"],
                         next(item["sha256"] for item in report["libraries"]
                              if item["path"].endswith("libprusaslicer-jni.so")))

    def test_rejects_wrong_elf_machine(self):
        self.write_apk(machine=62)
        with self.assertRaisesRegex(ValueError, "AArch64"):
            runner.inventory_apk(self.apk)

    def test_rejects_non_arm64_apk_entries(self):
        self.write_apk(extra_abi=True)
        with self.assertRaisesRegex(ValueError, "unexpected native ABIs"):
            runner.inventory_apk(self.apk)

    def test_rejects_hash_mismatch_with_checked_in_libraries(self):
        self.write_apk(changed_payload=True)
        with self.assertRaisesRegex(ValueError, "exactly match checked-in"):
            runner.inventory_apk(self.apk)


if __name__ == "__main__":
    unittest.main()
