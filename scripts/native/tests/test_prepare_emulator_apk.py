import importlib.util
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "prepare-emulator-apk.py"
spec = importlib.util.spec_from_file_location("prepare_emulator_apk", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ElfArchitectureTest(unittest.TestCase):
    def test_selected_machine_matches(self):
        with tempfile.TemporaryDirectory() as directory:
            library = Path(directory) / "lib.so"
            for abi, machine in (("x86_64", 62), ("arm64-v8a", 183)):
                library.write_bytes(b"\x7fELF\x02\x01" + bytes(12) + machine.to_bytes(2, "little"))
                module.verify_library(library, abi)
                with self.assertRaisesRegex(ValueError, "architecture"):
                    module.verify_library(library, "arm64-v8a" if abi == "x86_64" else "x86_64")

    def test_missing_and_invalid_libraries_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            library = Path(directory) / "lib.so"
            with self.assertRaisesRegex(ValueError, "Cannot read"):
                module.verify_library(library, "x86_64")
            for data in (b"not ELF", b"\x7fELF\x01\x01" + bytes(14), b"\x7fELF\x02\x02" + bytes(14)):
                library.write_bytes(data)
                with self.assertRaisesRegex(ValueError, "not a little-endian 64-bit ELF"):
                    module.verify_library(library, "x86_64")


if __name__ == "__main__":
    unittest.main()
