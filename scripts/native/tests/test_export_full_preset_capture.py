import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "export-full-preset-capture.py"
SPEC = importlib.util.spec_from_file_location("full_preset_export", SCRIPT)
EXPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EXPORT)


class FullPresetExportTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.bundle = self.root / "bundle"
        self.capture = self.root / "capture"
        self.bundle.mkdir()
        self.capture.mkdir()
        self.input = self.root / "input.stl"
        self.input.write_bytes(b"fixed model input")
        self.pin = "a" * 40
        files = {}
        for name, kind in (("printer", "machine"), ("process", "process"), ("filament", "filament")):
            path = self.bundle / f"{name}.json"
            path.write_text(json.dumps({"type": kind, "name": name,
                                        "machine_start_gcode": ""}), encoding="utf-8")
            files[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
        summary = {"variants": {}}
        for variant in EXPORT.VARIANTS:
            manifest = self.bundle / f"{variant}.json"
            manifest.write_text(json.dumps({"schema": "orca-native-presets-v1",
                "printer": "printer.json", "process": "process.json",
                "filaments": ["filament.json"], "catalog": []}), encoding="utf-8")
            metadata = {"manifest": manifest.name, "presetManifestSchema": "orca-native-presets-v1",
                        "presetManifestSha256": hashlib.sha256(manifest.read_bytes()).hexdigest(),
                        "presetFilesSha256": files, "sanitization": {"mode": "none", "overrides": {}}}
            summary["variants"][variant] = metadata
            gcode = self.capture / f"{variant}.gcode"
            gcode.write_text("; CONFIG_BLOCK_START\n; gcode_flavor = klipper\n; layer_height = 0.2\n"
                             "; CONFIG_BLOCK_END\n;LAYER_CHANGE\nG90\nM83\nG1 X10 E1\n"
                             "; estimated printing time (normal mode) = 2m\n", encoding="utf-8")
            report = EXPORT.analyze(gcode)
            report["parityProvenance"] = EXPORT.build_provenance(
                metadata, self.bundle, self.input, gcode, self.pin)
            (self.capture / f"{variant}.json").write_text(json.dumps(report), encoding="utf-8")
        (self.bundle / "summary.json").write_text(json.dumps(summary), encoding="utf-8")

    def validate(self):
        EXPORT.validate_capture(self.capture, self.bundle, self.pin, self.input)

    def test_valid_capture(self):
        self.validate()
        self.assertTrue((self.capture / "diagnostic.metrics.json").is_file())

    def test_changed_output_rejected(self):
        with (self.capture / "actual.gcode").open("a", encoding="utf-8") as output:
            output.write("G1 X20 E5\n")
        with self.assertRaisesRegex(ValueError, "provenance"):
            self.validate()

    def test_changed_parent_or_model_rejected(self):
        (self.bundle / "printer.json").write_text("{}", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            self.validate()

    def test_wrong_reported_config_rejected(self):
        path = self.capture / "actual.json"
        report = json.loads(path.read_text())
        report["config"]["layer_height"] = "0.3"
        path.write_text(json.dumps(report), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "configuration"):
            self.validate()

    def test_wrong_engine_pin_rejected(self):
        self.pin = "b" * 40
        with self.assertRaisesRegex(ValueError, "provenance"):
            self.validate()


if __name__ == "__main__":
    unittest.main()
