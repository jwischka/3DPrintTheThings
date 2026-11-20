import json
import hashlib
import tempfile
import unittest
from pathlib import Path

from parity import analyze, compare
from provenance import ProvenanceError, build_provenance, validate_pair


FIXTURES = Path(__file__).parent / "fixtures"
PIN = "a" * 40
class ProvenanceGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.bundle_root = self.root / "bundle"
        self.bundle_root.mkdir()
        self.files = {
            "machine/base.json": b'{"name":"base"}',
            "process/leaf.json": b'{"name":"process"}',
            "filament/leaf.json": b'{"name":"filament"}',
            "machine/parent.json": b'{"name":"parent"}',
        }
        for relative, data in self.files.items():
            target = self.bundle_root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        self.hashes = {name: hashlib.sha256(data).hexdigest() for name, data in self.files.items()}
        manifest = {
            "schema": "orca-native-presets-v1", "printer": "machine/base.json",
            "process": "process/leaf.json", "filaments": ["filament/leaf.json"] * 4,
            "catalog": ["machine/parent.json"],
        }
        manifest_bytes = json.dumps(manifest, sort_keys=True).encode()
        (self.bundle_root / "actual.json").write_bytes(manifest_bytes)
        self.bundle = {
            "manifest": "actual.json", "presetManifestSchema": "orca-native-presets-v1",
            "presetManifestSha256": hashlib.sha256(manifest_bytes).hexdigest(),
            "presetFilesSha256": self.hashes,
            "sanitization": {"mode": "none", "overrides": {}},
        }
        self.input_path = self.root / "model.stl"
        self.input_path.write_bytes(b"fixture model bytes")
        self.expected_gcode = self.root / "expected.gcode"
        self.actual_gcode = self.root / "actual.gcode"
        source = (FIXTURES / "absolute.gcode").read_bytes()
        self.expected_gcode.write_bytes(source)
        self.actual_gcode.write_bytes(source)
        self.expected_json = self.root / "expected.json"
        self.actual_json = self.root / "actual.json"
        self.expected_provenance = build_provenance(self.bundle, self.bundle_root, self.input_path, self.expected_gcode, PIN)
        self.actual_provenance = build_provenance(self.bundle, self.bundle_root, self.input_path, self.actual_gcode, PIN)
        self.write_reports()

    def tearDown(self):
        self.temp.cleanup()

    def report(self, provenance, gcode):
        report = analyze(gcode)
        report["parityProvenance"] = provenance
        return report

    def write_reports(self, left=None, right=None):
        left = left if left is not None else self.expected_provenance
        right = right if right is not None else self.actual_provenance
        self.expected_json.write_text(json.dumps(self.report(left, self.expected_gcode)), encoding="utf-8")
        self.actual_json.write_text(json.dumps(self.report(right, self.actual_gcode)), encoding="utf-8")

    def test_build_provenance_binds_real_input_gcode_and_parent_closure(self):
        result = build_provenance(self.bundle, self.bundle_root, self.input_path, self.expected_gcode, PIN)
        self.assertEqual(result["engineSourcePin"], PIN)
        self.assertEqual(result["inputSha256"], self.expected_provenance["inputSha256"])
        self.assertEqual(result["gcodeSha256"], self.expected_provenance["gcodeSha256"])
        self.assertEqual(result["presetFilesSha256"], dict(sorted(self.hashes.items())))

    def test_matching_provenance_passes_gate_but_is_not_parity(self):
        self.assertEqual(validate_pair(self.expected_json, self.expected_gcode,
                                       self.actual_json, self.actual_gcode), [])
        # The gate is deliberately not a replacement for the strict structural comparator.
        self.assertEqual(compare(analyze(self.expected_gcode), analyze(self.actual_gcode)), [])

    def test_rejects_absent_malformed_and_incomplete_provenance(self):
        with self.assertRaisesRegex(ProvenanceError, "presetFilesSha256"):
            build_provenance({**self.bundle, "presetFilesSha256": {"machine/base.json": "1" * 64}},
                             self.bundle_root, self.input_path, self.expected_gcode, PIN)
        with self.assertRaisesRegex(ProvenanceError, "full 40- or 64-character"):
            build_provenance(self.bundle, self.bundle_root, self.input_path, self.expected_gcode, "a" * 7)

        self.expected_json.write_text("{}", encoding="utf-8")
        with self.assertRaisesRegex(ProvenanceError, "missing parityProvenance"):
            validate_pair(self.expected_json, self.expected_gcode, self.actual_json, self.actual_gcode)

        report = self.report(self.expected_provenance, self.expected_gcode)
        del report["parityProvenance"]["engineSourcePin"]
        self.expected_json.write_text(json.dumps(report), encoding="utf-8")
        with self.assertRaisesRegex(ProvenanceError, "engineSourcePin"):
            validate_pair(self.expected_json, self.expected_gcode, self.actual_json, self.actual_gcode)

        report["parityProvenance"]["engineSourcePin"] = PIN
        report["parityProvenance"]["inputSha256"] = "not-a-hash"
        self.expected_json.write_text(json.dumps(report), encoding="utf-8")
        with self.assertRaisesRegex(ProvenanceError, "invalid inputSha256"):
            validate_pair(self.expected_json, self.expected_gcode, self.actual_json, self.actual_gcode)

    def test_rejects_stale_gcode_bytes_and_config_claims(self):
        self.actual_gcode.write_bytes(self.actual_gcode.read_bytes() + b"; changed\n")
        with self.assertRaisesRegex(ProvenanceError, "G-code bytes"):
            validate_pair(self.expected_json, self.expected_gcode, self.actual_json, self.actual_gcode)

        self.actual_gcode.write_bytes(self.expected_gcode.read_bytes())
        report = json.loads(self.actual_json.read_text(encoding="utf-8"))
        report["config"]["layer_height"] = "0.3"
        self.actual_json.write_text(json.dumps(report), encoding="utf-8")
        with self.assertRaisesRegex(ProvenanceError, "capture config"):
            validate_pair(self.expected_json, self.expected_gcode, self.actual_json, self.actual_gcode)

    def test_rejects_source_model_manifest_parent_and_sanitization_mismatch(self):
        for key, value in (
            ("engineSourcePin", "b" * 40),
            ("inputSha256", "6" * 64),
            ("presetManifestSha256", "7" * 64),
            ("presetFilesSha256", {**self.hashes, "machine/parent.json": "8" * 64}),
            ("sanitization", {"mode": "macro-free-diagnostic", "overrides": {
                "machine/diagnostic.json": {"machine_start_gcode": ""}}}),
        ):
            changed = dict(self.actual_provenance)
            changed[key] = value
            self.write_reports(right=changed)
            with self.subTest(key=key):
                self.assertTrue(validate_pair(self.expected_json, self.expected_gcode,
                                              self.actual_json, self.actual_gcode))

    def test_rejects_different_effective_config(self):
        changed_gcode = self.root / "different-config.gcode"
        changed_gcode.write_bytes(self.expected_gcode.read_bytes().replace(b"layer_height = 0.2", b"layer_height = 0.3"))
        changed = build_provenance(self.bundle, self.bundle_root, self.input_path, changed_gcode, PIN)
        self.actual_gcode.write_bytes(changed_gcode.read_bytes())
        self.write_reports(right=changed)
        errors = validate_pair(self.expected_json, self.expected_gcode, self.actual_json, self.actual_gcode)
        self.assertTrue(any("config mismatch" in error for error in errors))

    def test_opaque_macro_remains_rejected_by_strict_comparator(self):
        macro = FIXTURES / "unsupported_macro.gcode"
        macro_copy = self.root / "macro.gcode"
        macro_copy.write_bytes(macro.read_bytes())
        macro_provenance = build_provenance(self.bundle, self.bundle_root, self.input_path, macro_copy, PIN)
        macro_json = self.root / "macro.json"
        macro_json.write_text(json.dumps(self.report(macro_provenance, macro_copy)), encoding="utf-8")
        self.assertEqual(validate_pair(macro_json, macro_copy, macro_json, macro_copy), [])
        report = analyze(macro_copy)
        self.assertTrue(any("unsupported commands" in error for error in compare(report, report)))

    def test_builder_rejects_bad_path_or_diagnostic_without_explicit_overrides(self):
        with self.assertRaisesRegex(ProvenanceError, "invalid preset path"):
            build_provenance({**self.bundle, "presetFilesSha256": {**self.hashes, "../escape.json": "9" * 64}},
                             self.bundle_root, self.input_path, self.expected_gcode, PIN)
        diagnostic = {**self.bundle, "sanitization": {"mode": "macro-free-diagnostic", "overrides": {}}}
        with self.assertRaisesRegex(ProvenanceError, "must declare deterministic overrides"):
            build_provenance(diagnostic, self.bundle_root, self.input_path, self.expected_gcode, PIN)

        false_override = {**self.bundle, "sanitization": {
            "mode": "macro-free-diagnostic", "overrides": {"machine/base.json": {"start_gcode": ""}}}}
        with self.assertRaisesRegex(ProvenanceError, "do not contain declared overrides"):
            build_provenance(false_override, self.bundle_root, self.input_path, self.expected_gcode, PIN)

    def test_builder_rehashes_manifest_and_all_parent_files(self):
        parent = self.bundle_root / "machine/parent.json"
        parent.write_bytes(b'{"name":"tampered parent"}')
        with self.assertRaisesRegex(ProvenanceError, "machine/parent.json"):
            build_provenance(self.bundle, self.bundle_root, self.input_path, self.expected_gcode, PIN)
        parent.write_bytes(self.files["machine/parent.json"])

        manifest_path = self.bundle_root / "actual.json"
        manifest_path.write_bytes(manifest_path.read_bytes() + b" ")
        with self.assertRaisesRegex(ProvenanceError, "manifest bytes"):
            build_provenance(self.bundle, self.bundle_root, self.input_path, self.expected_gcode, PIN)

    def test_manifest_must_enumerate_exact_closure(self):
        incomplete = {**self.bundle, "presetFilesSha256": {
            key: value for key, value in self.hashes.items() if key != "machine/parent.json"}}
        with self.assertRaisesRegex(ProvenanceError, "exactly enumerate"):
            build_provenance(incomplete, self.bundle_root, self.input_path, self.expected_gcode, PIN)


if __name__ == "__main__":
    unittest.main()
