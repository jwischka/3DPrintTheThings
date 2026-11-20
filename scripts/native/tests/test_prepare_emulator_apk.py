import importlib.util
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "verify_engine_binary.py"
spec = importlib.util.spec_from_file_location("verify_engine_binary", SCRIPT)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)


def engine_elf(machine=62, load_alignment=16384, relro_end=0x8000, runtime=None):
    header = bytearray(64)
    header[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<HHI", header, 16, 3, machine, 1)
    struct.pack_into("<Q", header, 32, 64)
    struct.pack_into("<HHH", header, 52, 64, 56, 4)

    strings = b"\0libc.so\0liblog.so\0"
    entries = [(5, 0x600), (1, 1), (1, 9)]
    if runtime:
        entries.append((1, len(strings)))
        strings += runtime + b"\0"
    entries.extend(((10, len(strings)), (0, 0)))
    dynamic = b"".join(struct.pack("<qQ", *entry) for entry in entries)

    def segment(kind, flags, offset, address, file_size, memory_size, alignment):
        return struct.pack("<IIQQQQQQ", kind, flags, offset, address, address, file_size, memory_size, alignment)

    data = bytearray(0x1000)
    data[:64] = header
    segments = b"".join([
        segment(1, 5, 0, 0, 0x800, 0x800, load_alignment),
        segment(1, 6, 0x800, 0x4800, 0x800, 0x3800, load_alignment),
        segment(2, 6, 0x880, 0x4880, len(dynamic), len(dynamic), 8),
        segment(0x6474e552, 4, 0x800, 0x4800, 0x800, relro_end - 0x4800, 1),
    ])
    data[64:64 + len(segments)] = segments
    data[0x600:0x600 + len(strings)] = strings
    data[0x880:0x880 + len(dynamic)] = dynamic
    return bytes(data)


class ElfArchitectureTest(unittest.TestCase):
    def test_selected_machine_matches(self):
        with tempfile.TemporaryDirectory() as directory:
            library = Path(directory) / "lib.so"
            for abi, machine in (("x86_64", 62), ("arm64-v8a", 183)):
                library.write_bytes(engine_elf(machine))
                module.verify_library(library, abi)
                with self.assertRaisesRegex(ValueError, "architecture"):
                    module.verify_library(library, "arm64-v8a" if abi == "x86_64" else "x86_64")

    def test_missing_and_invalid_libraries_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            library = Path(directory) / "lib.so"
            with self.assertRaisesRegex(ValueError, "Cannot read"):
                module.verify_library(library, "x86_64")
            for data in (b"not ELF", b"\x7fELF\x01\x01" + bytes(58), b"\x7fELF\x02\x02" + bytes(58)):
                library.write_bytes(data)
                with self.assertRaisesRegex(ValueError, "not a little-endian 64-bit ELF"):
                    module.verify_library(library, "x86_64")


class EngineCompatibilityTest(unittest.TestCase):
    def test_all_load_segments_must_support_16_kib_pages(self):
        with self.assertRaisesRegex(ValueError, "LOAD segments"):
            module.verify_elf(engine_elf(load_alignment=4096), "x86_64")
        data = bytearray(engine_elf())
        struct.pack_into("<Q", data, 64 + 56 + 16, 0x4900)
        with self.assertRaisesRegex(ValueError, "LOAD segments"):
            module.verify_elf(bytes(data), "x86_64")

    def test_relro_must_end_on_a_16_kib_boundary(self):
        with self.assertRaisesRegex(ValueError, "GNU_RELRO"):
            module.verify_elf(engine_elf(relro_end=0x7000), "x86_64")

    def test_aligned_jni_cannot_depend_on_a_shared_cpp_runtime(self):
        with self.assertRaisesRegex(ValueError, r"Unexpected ELF dependencies: libc\+\+_shared\.so"):
            module.verify_elf(engine_elf(runtime=b"libc++_shared.so"), "x86_64")


class ApkCompatibilityTest(unittest.TestCase):
    def write_apk(self, path, compression, *, stored_offset=None, include_runtime=False):
        entry = zipfile.ZipInfo("lib/x86_64/libprusaslicer-jni.so")
        if stored_offset is not None:
            padding = stored_offset - 30 - len(entry.filename)
            entry.extra = struct.pack("<HH", 0xCAFE, padding - 4) + bytes(padding - 4)
        with zipfile.ZipFile(path, "w") as apk:
            apk.writestr(entry, engine_elf(), compress_type=compression)
            if include_runtime:
                apk.writestr("lib/x86_64/libc++_shared.so", b"unexpected runtime")

    def test_compressed_or_16_kib_aligned_jni_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "engine.apk"
            self.write_apk(apk, zipfile.ZIP_DEFLATED)
            module.verify_apk(apk, "x86_64")
            self.write_apk(apk, zipfile.ZIP_STORED, stored_offset=16384)
            module.verify_apk(apk, "x86_64")

    def test_4_kib_zip_alignment_is_rejected_even_with_an_aligned_elf(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "engine.apk"
            self.write_apk(apk, zipfile.ZIP_STORED, stored_offset=4096)
            with self.assertRaisesRegex(ValueError, "Uncompressed JNI library"):
                module.verify_apk(apk, "x86_64")

    def test_an_extra_shared_library_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "engine.apk"
            self.write_apk(apk, zipfile.ZIP_DEFLATED, include_runtime=True)
            with self.assertRaisesRegex(ValueError, "APK must contain only"):
                module.verify_apk(apk, "x86_64")


if __name__ == "__main__":
    unittest.main()
