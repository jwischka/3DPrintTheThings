import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "export-parity-capture.py"
spec = importlib.util.spec_from_file_location("export_parity_capture", SCRIPT)
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)


def sha(data):
    return hashlib.sha256(data).hexdigest()


class ExportParityCaptureTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.fixture = self.root / "cube_10mm.stl"
        self.fixture.write_bytes(b"solid cube\nendsolid cube\n")
        self.gcode = self.root / capture.GCODE_NAME
        self.gcode.write_bytes(b"G90\nG1 X1 E1\n")
        self.assets = self.root / "assets"
        profiles = {}
        for name in capture.PROFILES:
            path = self.assets / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(name.encode())
            profiles[name] = sha(path.read_bytes())
        self.metadata = self.root / capture.JSON_NAME
        self.report = {
            "schemaVersion": 1,
            "engineVersion": "Orca 2.2",
            "deviceAbis": ["x86_64", "x86"],
            "inputFixture": "tools/parity/fixtures/cube_10mm.stl",
            "inputSha256": sha(self.fixture.read_bytes()),
            "gcodeBytes": self.gcode.stat().st_size,
            "gcodeSha256": sha(self.gcode.read_bytes()),
            "parsedLayerCount": 50,
            "modelExtrudeMoves": 500,
            "sliceConfig": {"layerHeight": 0.2},
            "bundledProfileSha256": profiles,
        }
        self.save_report()

    def save_report(self):
        self.metadata.write_text(json.dumps(self.report), encoding="utf-8")

    def validate(self):
        capture.validate_capture(self.gcode, self.metadata, self.fixture, self.assets)

    def test_valid_capture(self):
        self.validate()

    def test_tampered_gcode_fails_even_with_same_byte_count(self):
        self.gcode.write_bytes(b"G90\nG1 X1 E2\n")
        with self.assertRaisesRegex(ValueError, "G-code SHA-256"):
            self.validate()

    def test_wrong_fixture_hash_fails(self):
        self.report["inputSha256"] = "0" * 64
        self.save_report()
        with self.assertRaisesRegex(ValueError, "input SHA-256"):
            self.validate()

    def test_capture_without_model_extrusion_fails(self):
        self.report["modelExtrudeMoves"] = 0
        self.save_report()
        with self.assertRaisesRegex(ValueError, "printable model extrusion"):
            self.validate()

    def test_wrong_bundled_profile_hash_fails(self):
        self.report["bundledProfileSha256"][capture.PROFILES[0]] = "0" * 64
        self.save_report()
        with self.assertRaisesRegex(ValueError, "bundled profile SHA-256"):
            self.validate()


if __name__ == "__main__":
    unittest.main()
