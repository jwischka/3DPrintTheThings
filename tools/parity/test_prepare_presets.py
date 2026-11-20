import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from prepare_presets import SELECTED, prepare


class PreparePresetsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.vendor = self.root / "source/resources/profiles/Snapmaker"
        for kind, leaf in SELECTED.items():
            directory = self.vendor / kind
            directory.mkdir(parents=True)
            (directory / "base.json").write_text(
                json.dumps({"name": f"{kind} base", "type": kind,
                            "layer_height": "0.31"}) + "\n", encoding="utf-8")
            (directory / leaf).write_text(
                json.dumps({"name": f"selected {kind}", "type": kind,
                            "inherits": f"{kind} base", "layer_height": "0.12"}) + "\n",
                encoding="utf-8")
        # Unrelated legacy entries in a vendor folder must not break closure.
        (self.vendor / "machine/unrelated.json").write_text(
            '{"name":"legacy","type":"other"}', encoding="utf-8")

    def test_copies_exact_closure_and_records_diagnostic_changes(self):
        output = self.root / "bundle"
        summary = prepare(self.root / "source", output)
        actual = summary["variants"]["actual"]
        diagnostic = summary["variants"]["diagnostic"]
        self.assertEqual("orca-native-presets-v1", actual["presetManifestSchema"])
        self.assertEqual("none", actual["sanitization"]["mode"])
        self.assertEqual(6, len(actual["presetFilesSha256"]))
        self.assertEqual(9, len(diagnostic["presetFilesSha256"]))
        for relative, expected_hash in actual["presetFilesSha256"].items():
            original = self.vendor / relative
            copied = output / relative
            self.assertEqual(original.read_bytes(), copied.read_bytes())
            self.assertEqual(expected_hash, hashlib.sha256(copied.read_bytes()).hexdigest())
        for variant in (actual, diagnostic):
            self.assertEqual(variant["presetManifestSha256"],
                             hashlib.sha256((output / variant["manifest"]).read_bytes()).hexdigest())
            manifest = json.loads((output / variant["manifest"]).read_text())
            self.assertEqual(4, len(manifest["filaments"]))
            self.assertEqual(1, len(set(manifest["filaments"])))
        self.assertEqual("macro-free-diagnostic", diagnostic["sanitization"]["mode"])
        child = json.loads((output / "machine/diagnostic_machine.json").read_text())
        self.assertEqual("selected machine", child["inherits"])
        self.assertEqual("G28\nG92 E0", child["machine_start_gcode"])
        self.assertEqual(summary, json.loads((output / "summary.json").read_text()))

    def test_missing_parent_fails_without_publishing_output(self):
        leaf = self.vendor / "process" / SELECTED["process"]
        value = json.loads(leaf.read_text())
        value["inherits"] = "missing"
        leaf.write_text(json.dumps(value), encoding="utf-8")
        output = self.root / "bundle"
        with self.assertRaisesRegex(ValueError, "Missing or ambiguous parent"):
            prepare(self.root / "source", output)
        self.assertFalse(output.exists())

    def test_cycle_and_wrong_selected_type_fail(self):
        parent = self.vendor / "machine/base.json"
        value = json.loads(parent.read_text())
        value["inherits"] = "selected machine"
        parent.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "cycle"):
            prepare(self.root / "source", self.root / "cycle")
        leaf = self.vendor / "machine" / SELECTED["machine"]
        value = json.loads(leaf.read_text())
        value["type"] = "filament"
        leaf.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Wrong preset type"):
            prepare(self.root / "source", self.root / "wrong")

    def test_existing_output_is_not_overwritten(self):
        output = self.root / "bundle"
        output.mkdir()
        marker = output / "keep"
        marker.write_text("untouched", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Output must be new"):
            prepare(self.root / "source", output)
        self.assertEqual("untouched", marker.read_text())


if __name__ == "__main__":
    unittest.main()
