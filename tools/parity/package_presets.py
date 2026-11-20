#!/usr/bin/env python3
"""Package a prepared preset manifest and its exact files for Android import."""
import argparse
import json
from pathlib import Path
import zipfile


def package(root: Path, manifest: str, output: Path) -> None:
    root = root.resolve(strict=True)
    if Path(manifest).name != manifest or not manifest.endswith('.json'):
        raise ValueError('Manifest must be a JSON filename')
    data = (root / manifest).read_bytes()
    value = json.loads(data)
    if set(value) != {'schema', 'printer', 'process', 'filaments', 'catalog'} or value['schema'] != 'orca-native-presets-v1':
        raise ValueError('Expected a prepared v1 manifest')
    paths = {value['printer'], value['process'], *value['filaments'], *value['catalog']}
    files = {'manifest.json': data}
    for relative in sorted(paths):
        if not isinstance(relative, str) or '\\' in relative or ':' in relative or any(
            part in ('', '.', '..') for part in relative.split('/')
        ) or not relative.endswith('.json'):
            raise ValueError('Invalid preset filename')
        source = (root / relative).resolve(strict=True)
        if not source.is_relative_to(root) or relative == 'manifest.json':
            raise ValueError('Preset must stay inside the bundle')
        files[relative] = source.read_bytes()
    # Exclusive creation protects previous accepted fixture artifacts.
    with output.open('xb') as destination:
        with zipfile.ZipFile(destination, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for name, content in sorted(files.items()):
                entry = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(entry, content)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', required=True, type=Path)
    parser.add_argument('--manifest', default='actual.json')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    package(args.bundle, args.manifest, args.output)
