#!/usr/bin/env python3
"""Verify the experimental JNI engine or an APK containing it."""
import argparse
from dataclasses import dataclass
from pathlib import Path
import struct
import zipfile


ENGINE_NAME = "libprusaslicer-jni.so"
PAGE_SIZE = 16384
MACHINES = {"arm64-v8a": 183, "x86_64": 62}
PLATFORM_LIBRARIES = {b"libc.so", b"libdl.so", b"liblog.so", b"libm.so"}
PROGRAM_HEADER = struct.Struct("<IIQQQQQQ")
DYNAMIC_ENTRY = struct.Struct("<qQ")
PT_LOAD = 1
PT_DYNAMIC = 2
PT_GNU_RELRO = 0x6474e552
DT_NULL = 0
DT_NEEDED = 1
DT_STRTAB = 5
DT_STRSZ = 10


@dataclass(frozen=True)
class Segment:
    type: int
    flags: int
    offset: int
    address: int
    physical_address: int
    file_size: int
    memory_size: int
    alignment: int


def verify_elf(data: bytes, abi: str) -> None:
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Engine is not a little-endian 64-bit ELF.")
    elf_type, machine = struct.unpack_from("<HH", data, 16)
    if machine != MACHINES[abi]:
        raise ValueError(f"Engine architecture does not match {abi}.")
    if elf_type != 3:
        raise ValueError("Engine is not an ELF shared object.")
    header_offset, = struct.unpack_from("<Q", data, 32)
    entry_size, count = struct.unpack_from("<HH", data, 54)
    if header_offset < 64 or entry_size != PROGRAM_HEADER.size or header_offset + entry_size * count > len(data):
        raise ValueError("Invalid ELF program headers.")
    segments = [
        Segment(*PROGRAM_HEADER.unpack_from(data, header_offset + entry_size * index))
        for index in range(count)
    ]
    if any(segment.offset + segment.file_size > len(data) for segment in segments):
        raise ValueError("ELF segment extends beyond the file.")
    loads = [segment for segment in segments if segment.type == PT_LOAD]
    if not loads or any(
        segment.alignment < PAGE_SIZE
        or segment.alignment & (segment.alignment - 1)
        or (segment.address - segment.offset) % segment.alignment
        for segment in loads
    ):
        raise ValueError("Engine LOAD segments must support 16 KiB pages.")

    relro = next((segment for segment in segments if segment.type == PT_GNU_RELRO), None)
    if relro is None or not relro.memory_size or (relro.address + relro.memory_size) % PAGE_SIZE:
        raise ValueError("Engine GNU_RELRO must end on a 16 KiB boundary.")
    verify_dependencies(data, segments, loads)


def verify_dependencies(data: bytes, segments: list[Segment], loads: list[Segment]) -> None:
    dynamic = next((segment for segment in segments if segment.type == PT_DYNAMIC), None)
    if dynamic is None or dynamic.file_size % DYNAMIC_ENTRY.size:
        raise ValueError("Invalid ELF dynamic section.")
    entries = []
    for tag, value in DYNAMIC_ENTRY.iter_unpack(data[dynamic.offset:dynamic.offset + dynamic.file_size]):
        if tag == DT_NULL:
            break
        entries.append((tag, value))
    else:
        raise ValueError("Unterminated ELF dynamic section.")
    properties = dict(entries)
    size = properties.get(DT_STRSZ, 0)
    address = properties.get(DT_STRTAB, 0)
    load = next(
        (segment for segment in loads
         if segment.address <= address and address + size <= segment.address + segment.file_size),
        None,
    )
    if not size or load is None:
        raise ValueError("Invalid ELF dynamic string table.")
    start = load.offset + address - load.address
    strings = data[start:start + size]
    dependencies = set()
    for tag, index in entries:
        if tag != DT_NEEDED:
            continue
        end = strings.find(b"\0", index)
        if end < 0:
            raise ValueError("Invalid ELF dependency name.")
        dependencies.add(strings[index:end])
    unexpected = dependencies - PLATFORM_LIBRARIES
    if unexpected:
        names = ", ".join(name.decode("ascii", errors="backslashreplace") for name in sorted(unexpected))
        raise ValueError(f"Unexpected ELF dependencies: {names}.")
    if not {b"libc.so", b"liblog.so"} <= dependencies:
        raise ValueError("Engine is missing expected ELF dependencies.")


def verify_library(path: Path, abi: str) -> None:
    try:
        data = path.read_bytes()
    except OSError as exc:
        raise ValueError(f"Cannot read native library: {path.name}") from exc
    verify_elf(data, abi)


def verify_apk(path: Path, abi: str) -> None:
    with path.open("rb") as raw, zipfile.ZipFile(raw) as apk:
        libraries = [entry for entry in apk.infolist()
                     if entry.filename.startswith("lib/") and entry.filename.endswith(".so")]
        if [entry.filename for entry in libraries] != [f"lib/{abi}/{ENGINE_NAME}"]:
            raise ValueError(f"APK must contain only the {abi} experimental JNI library.")
        entry = libraries[0]
        if entry.compress_type == zipfile.ZIP_STORED:
            raw.seek(entry.header_offset + 26)
            name_size, extra_size = struct.unpack("<HH", raw.read(4))
            data_offset = entry.header_offset + 30 + name_size + extra_size
            if data_offset % PAGE_SIZE:
                raise ValueError("Uncompressed JNI library must start on a 16 KiB boundary.")
        elif entry.compress_type != zipfile.ZIP_DEFLATED:
            raise ValueError("Unsupported APK compression for the JNI library.")
        verify_elf(apk.read(entry), abi)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", required=True, choices=MACHINES)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--library", type=Path)
    source.add_argument("--apk", type=Path)
    args = parser.parse_args()
    try:
        if args.apk is not None:
            verify_apk(args.apk, args.abi)
        else:
            verify_library(args.library, args.abi)
    except (OSError, ValueError, struct.error, zipfile.BadZipFile) as exc:
        parser.error(str(exc))


if __name__ == "__main__":
    main()
