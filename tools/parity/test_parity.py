import json
import tempfile
import unittest
from contextlib import redirect_stdout
from collections import Counter
from io import StringIO
from pathlib import Path

from parity import analyze, compare, config_diff, load_report, main


FIXTURES = Path(__file__).parent / "fixtures"


class GcodeParityTest(unittest.TestCase):
    def test_config_mismatch_reports_compact_changed_missing_and_important_settings(self):
        expected = {
            "gcode_flavor": "klipper", "layer_height": "0.2",
            "machine_start_gcode": "START\n" * 100,
            **{f"expected_{i}": str(i) for i in range(20)},
        }
        actual = {
            "gcode_flavor": "marlin", "layer_height": "0.2",
            **{f"actual_{i}": str(i) for i in range(20)},
        }

        diff = config_diff(expected, actual)
        self.assertEqual(diff["changed_count"], 1)
        self.assertEqual(diff["missing_in_actual_count"], 21)
        self.assertEqual(diff["missing_in_expected_count"], 20)
        self.assertEqual(diff["important_settings"]["gcode_flavor"],
                         {"expected": "klipper", "actual": "marlin"})
        self.assertEqual(diff["important_settings"]["machine_start_gcode"]["actual"], "<missing>")
        self.assertEqual(len(diff["missing_in_actual"]), 16)
        self.assertEqual(diff["missing_in_actual_omitted"], 5)

        # Comparison remains strict: a diagnostic summary never turns a mismatch into a pass.
        baseline = analyze(FIXTURES / "absolute.gcode")
        candidate = dict(baseline, config=actual)
        errors = compare(dict(baseline, config=expected), candidate)
        self.assertTrue(any(error.startswith("config mismatch: 1 changed") for error in errors))

    def test_json_compare_omits_full_config_maps(self):
        baseline = analyze(FIXTURES / "absolute.gcode")
        expected = dict(baseline, config={"gcode_flavor": "klipper", "large": "x" * 10000})
        actual = dict(baseline, config={"gcode_flavor": "klipper", "large": "y" * 10000})
        with tempfile.TemporaryDirectory() as directory:
            expected_path = Path(directory) / "expected.json"
            actual_path = Path(directory) / "actual.json"
            expected_path.write_text(json.dumps(expected), encoding="utf-8")
            actual_path.write_text(json.dumps(actual), encoding="utf-8")
            output = StringIO()
            with redirect_stdout(output):
                exit_code = main(["compare", str(expected_path), str(actual_path), "--json"])

        self.assertEqual(exit_code, 1)
        rendered = output.getvalue()
        self.assertIn('"config_diff"', rendered)
        self.assertNotIn('"config": {', rendered)
        self.assertLess(len(rendered), 5000)

    def test_cube_fixture_has_10mm_bounds_and_closed_triangles(self):
        vertices = []
        triangles = []
        current = []
        for line in (FIXTURES / "cube_10mm.stl").read_text(encoding="ascii").splitlines():
            parts = line.strip().split()
            if len(parts) == 4 and parts[0] == "vertex":
                vertex = tuple(float(value) for value in parts[1:])
                vertices.append(vertex)
                current.append(vertex)
                if len(current) == 3:
                    triangles.append(tuple(current))
                    current = []

        self.assertEqual(len(triangles), 12)
        self.assertEqual(tuple(min(v[i] for v in vertices) for i in range(3)), (0.0, 0.0, 0.0))
        self.assertEqual(tuple(max(v[i] for v in vertices) for i in range(3)), (10.0, 10.0, 10.0))
        self.assertEqual(current, [])

        edge_counts = Counter()
        for triangle in triangles:
            for left, right in ((0, 1), (1, 2), (2, 0)):
                edge_counts[tuple(sorted((triangle[left], triangle[right])))] += 1
        self.assertEqual(len(edge_counts), 18)
        self.assertTrue(all(count == 2 for count in edge_counts.values()))

    def test_absolute_coordinates_and_retraction_and_toolchange(self):
        result = analyze(FIXTURES / "absolute.gcode")
        self.assertEqual(result["layers"], 2)
        self.assertEqual(result["tool_sequence"], [0, 1, 1, 0])
        self.assertAlmostEqual(result["extrusion_mm_by_tool"]["0"], 3.0)
        self.assertAlmostEqual(result["extrusion_mm_by_tool"]["1"], 1.5)

    def test_relative_extrusion_and_g92(self):
        result = analyze(FIXTURES / "relative.gcode")
        self.assertEqual(result["tool_sequence"], [])
        self.assertAlmostEqual(result["extrusion_mm_by_tool"]["0"], 4.0)

    def test_config_and_duration_parsed(self):
        result = analyze(FIXTURES / "absolute.gcode")
        self.assertEqual(result["config"]["layer_height"], "0.2")
        self.assertEqual(result["duration_seconds"], 120)

    def test_compare_applies_explicit_extrusion_and_duration_tolerances(self):
        baseline = analyze(FIXTURES / "absolute.gcode")
        candidate = dict(baseline)
        candidate["extrusion_mm_by_tool"] = {"0": 3.01, "1": 1.5}
        candidate["duration_seconds"] = 121
        self.assertEqual(compare(baseline, candidate), [])
        candidate["duration_seconds"] = 130
        self.assertTrue(any("duration" in item for item in compare(baseline, candidate)))

    def test_absolute_epsilon_does_not_mask_tiny_print_difference(self):
        baseline = {"config": {}, "layers": 1, "tool_sequence": [0],
                    "extrusion_mm_by_tool": {"0": 2.0}, "duration_seconds": 30}
        candidate = dict(baseline)
        candidate["extrusion_mm_by_tool"] = {"0": 2.1}
        candidate["duration_seconds"] = 31
        self.assertTrue(compare(baseline, candidate))

    def test_captured_json_baseline_can_be_compared_to_gcode(self):
        baseline = analyze(FIXTURES / "absolute.gcode")
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "baseline.json"
            report.write_text(json.dumps(baseline), encoding="utf-8")
            self.assertEqual(compare(load_report(report), baseline), [])

    def test_retraction_recovery_is_not_counted_as_consumed_extrusion(self):
        header = (
            "; CONFIG_BLOCK_START\n; gcode_flavor = klipper\n; layer_height = 0.2\n; CONFIG_BLOCK_END\n"
            "; CHANGE_LAYER\n; estimated printing time (normal mode) = 1m\n"
            "G90\nM83\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            first = Path(directory) / "first.gcode"
            second = Path(directory) / "second.gcode"
            first.write_text(header + "G1 E5\nG1 E-1\nG1 E1\nG1 E4\n", encoding="utf-8")
            second.write_text(header + "G1 E5\nG1 E-2\nG1 E2\nG1 E4\n", encoding="utf-8")
            first_report, second_report = analyze(first), analyze(second)
            self.assertEqual(first_report["extrusion_mm_by_tool"], {"0": 9.0})
            self.assertEqual(second_report["extrusion_mm_by_tool"], {"0": 9.0})
            self.assertEqual(compare(first_report, second_report), [])

    def test_absolute_extrusion_position_is_shared_across_tools(self):
        gcode = (
            "; CONFIG_BLOCK_START\n; gcode_flavor = klipper\n; layer_height = 0.2\n; CONFIG_BLOCK_END\n"
            ";LAYER_CHANGE\n; estimated printing time (normal mode) = 1m\n"
            "G90\nM82\nT0\nG1 E10\nT1\nG1 E20\nT0\nG1 E25\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "shared-e.gcode"
            path.write_text(gcode, encoding="utf-8")
            report = analyze(path)
        self.assertEqual(report["extrusion_mm_by_tool"], {"0": 15.0, "1": 10.0})

    def test_layer_marker_preference_and_compact_motion_words(self):
        gcode = (
            "; CONFIG_BLOCK_START\n; gcode_flavor = klipper\n; layer_height = 0.2\n; CONFIG_BLOCK_END\n"
            "; estimated printing time (normal mode) = 1m\n"
            ";LAYER_CHANGE\n;Z: 0.2\n;Z: 0.4\n"
            "G90\nM82\nG92E0\nG1X10E5\nG92.1E0\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "compact.gcode"
            path.write_text(gcode, encoding="utf-8")
            report = analyze(path)
        self.assertEqual(report["layers"], 1)
        self.assertEqual(report["extrusion_mm_by_tool"], {"0": 5.0})
        self.assertEqual(report["unsupported_commands"], ["G92.1E0"])

    def test_unbalanced_config_block_fails_closed(self):
        truncated = (
            "; CONFIG_BLOCK_START\n; gcode_flavor = klipper\n; layer_height = 0.2\n"
            ";LAYER_CHANGE\n; estimated printing time (normal mode) = 1m\n"
            "M83\nG1 E5\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "truncated.gcode"
            path.write_text(truncated, encoding="utf-8")
            report = analyze(path)
        self.assertFalse(report["config_block_valid"])
        self.assertTrue(any("markers" in error for error in compare(report, report)))

    def test_signed_extrusion_and_ambiguous_exponent_fail_closed(self):
        signed = (
            "; CONFIG_BLOCK_START\n; gcode_flavor = klipper\n; layer_height = 0.2\n; CONFIG_BLOCK_END\n"
            ";LAYER_CHANGE\n; estimated printing time (normal mode) = 1m\n"
            "M83\nG1 E+1.5\nG1 E1e2\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "numbers.gcode"
            path.write_text(signed, encoding="utf-8")
            report = analyze(path)
        self.assertEqual(report["extrusion_mm_by_tool"], {"0": 1.5})
        self.assertIn("invalid or repeated E parameter", report["unsupported_commands"])

    def test_unknown_macro_and_missing_metadata_fail_closed(self):
        macro_report = analyze(FIXTURES / "unsupported_macro.gcode")
        self.assertEqual(macro_report["layers"], 1)
        self.assertEqual(macro_report["unsupported_commands"], ["PRINT_START"])
        self.assertTrue(any("unsupported commands" in e for e in compare(macro_report, macro_report)))

        incomplete = dict(macro_report, config={}, layers=0, duration_seconds=None,
                          extrusion_mm_by_tool={})
        errors = compare(incomplete, incomplete)
        self.assertTrue(any("CONFIG_BLOCK" in e for e in errors))
        self.assertTrue(any("layer markers" in e for e in errors))
        self.assertTrue(any("duration" in e for e in errors))
        self.assertTrue(any("extrusion" in e for e in errors))


if __name__ == "__main__":
    unittest.main()
