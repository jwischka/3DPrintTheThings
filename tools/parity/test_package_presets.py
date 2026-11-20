import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from package_presets import package


class PackagePresetsTest(unittest.TestCase):
    def test_exact_closure_and_repeatable_zip(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            manifest = dict(schema='orca-native-presets-v1', printer='p.json', process='s.json',
                            filaments=['f.json', 'f.json'], catalog=['parent.json'])
            (root / 'actual.json').write_text(json.dumps(manifest))
            for name in ('p.json', 's.json', 'f.json', 'parent.json', 'unrelated.json'):
                (root / name).write_bytes(b'{"exact": "bytes"}\n')
            package(root, 'actual.json', root / 'first.zip')
            package(root, 'actual.json', root / 'second.zip')
            self.assertEqual((root / 'first.zip').read_bytes(), (root / 'second.zip').read_bytes())
            with zipfile.ZipFile(root / 'first.zip') as archive:
                self.assertEqual(set(archive.namelist()), {'manifest.json', 'p.json', 's.json', 'f.json', 'parent.json'})
                self.assertEqual(archive.read('manifest.json'), (root / 'actual.json').read_bytes())
                self.assertEqual(archive.read('f.json'), (root / 'f.json').read_bytes())
            with self.assertRaises(FileExistsError):
                package(root, 'actual.json', root / 'first.zip')

    def test_path_escape_fails_before_output(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 's.json').write_text('{}')
            (root / 'f.json').write_text('{}')
            for bad in ('../escape.json', '/absolute.json', 'a\\b.json', 'C:/a.json'):
                (root / 'actual.json').write_text(json.dumps(dict(schema='orca-native-presets-v1',
                    printer=bad, process='s.json', filaments=['f.json'], catalog=[])))
                with self.assertRaises(ValueError):
                    package(root, 'actual.json', root / 'output.zip')
                self.assertFalse((root / 'output.zip').exists())
