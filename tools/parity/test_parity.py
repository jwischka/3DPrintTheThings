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
    @staticmethod
    def velocity_gcode(commands):
        return (
            "; CONFIG_BLOCK_START\n; gcode_flavor = klipper\n; layer_height = 0.2\n; CONFIG_BLOCK_END\n"
            ";LAYER_CHANGE\n; estimated printing time (normal mode) = 1m\n"
            "M83\nG1 E5\n" + "\n".join(commands) + "\n"
        )

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

    def test_velocity_limit_validates_supported_numeric_parameters_and_canonicalizes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "velocity.gcode"
            path.write_text(self.velocity_gcode([
                "SET_VELOCITY_LIMIT ACCEL=1000.000 SQUARE_CORNER_VELOCITY=5",
                "SET_VELOCITY_LIMIT MINIMUM_CRUISE_RATIO=0.5 VELOCITY=200",
            ]), encoding="utf-8")
            report = analyze(path)
        self.assertEqual(report["velocity_limit_commands"], [
            {"ACCEL": "1000", "SQUARE_CORNER_VELOCITY": "5"},
            {"MINIMUM_CRUISE_RATIO": "0.5", "VELOCITY": "200"},
        ])
        self.assertEqual(report["unsupported_commands"], [])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "zero-bounds.gcode"
            path.write_text(self.velocity_gcode([
                "SET_VELOCITY_LIMIT MINIMUM_CRUISE_RATIO=0 SQUARE_CORNER_VELOCITY=0",
            ]), encoding="utf-8")
            self.assertEqual(analyze(path)["unsupported_commands"], [])

    def test_velocity_limit_rejects_invalid_bounds_duplicates_unknown_and_malformed(self):
        cases = [
            ("ACCEL=0", "must be greater than zero"),
            ("VELOCITY=-1", "must be greater than zero"),
            ("MINIMUM_CRUISE_RATIO=1", "must be in [0, 1)"),
            ("SQUARE_CORNER_VELOCITY=-0.1", "must be nonnegative"),
            ("ACCEL=1 ACCEL=2", "duplicate SET_VELOCITY_LIMIT parameter ACCEL"),
            ("ACCEL=1 BOGUS=2", "unknown SET_VELOCITY_LIMIT parameter BOGUS"),
            ("ACCEL=1e3", "invalid numeric SET_VELOCITY_LIMIT parameter ACCEL"),
            ("ACCEL =1", "malformed SET_VELOCITY_LIMIT parameter"),
        ]
        for parameters, expected_error in cases:
            with self.subTest(parameters=parameters), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "invalid-velocity.gcode"
                path.write_text(self.velocity_gcode([f"SET_VELOCITY_LIMIT {parameters}"]), encoding="utf-8")
                report = analyze(path)
                self.assertTrue(any(expected_error in error for error in report["unsupported_commands"]))
                self.assertEqual(report["velocity_limit_commands"], [])

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "empty-velocity.gcode"
            path.write_text(self.velocity_gcode(["SET_VELOCITY_LIMIT"]), encoding="utf-8")
            self.assertIn("requires at least one parameter", analyze(path)["unsupported_commands"][0])

    def test_velocity_limit_sequence_is_ordered_repeated_and_exact(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = [Path(directory) / f"{i}.gcode" for i in range(3)]
            paths[0].write_text(self.velocity_gcode([
                "SET_VELOCITY_LIMIT ACCEL=1000", "SET_VELOCITY_LIMIT ACCEL=2000",
            ]), encoding="utf-8")
            paths[1].write_text(self.velocity_gcode([
                "SET_VELOCITY_LIMIT ACCEL=1000", "SET_VELOCITY_LIMIT ACCEL=2001",
            ]), encoding="utf-8")
            paths[2].write_text(self.velocity_gcode([
                "SET_VELOCITY_LIMIT ACCEL=2000", "SET_VELOCITY_LIMIT ACCEL=1000",
            ]), encoding="utf-8")
            first, changed, reordered = map(analyze, paths)
        for candidate, index in ((changed, 1), (reordered, 0)):
            errors = compare(first, candidate)
            self.assertTrue(any("sequence differs (2 vs 2 commands)" in error for error in errors))
            self.assertTrue(any(f"first difference at index {index}" in error for error in errors))

    def test_legacy_accel_to_decel_is_opt_in_and_labeled_by_cli(self):
        with tempfile.TemporaryDirectory() as directory:
            left, right = Path(directory) / "left.gcode", Path(directory) / "right.gcode"
            command = ["SET_VELOCITY_LIMIT ACCEL=1000 ACCEL_TO_DECEL=500"]
            left.write_text(self.velocity_gcode(command), encoding="utf-8")
            right.write_text(self.velocity_gcode(command), encoding="utf-8")
            default_report = analyze(left)
            self.assertTrue(any("requires legacy opt-in" in error
                                for error in default_report["unsupported_commands"]))
            opted = analyze(left, legacy_klipper_accel_to_decel=True)
            self.assertEqual(opted["velocity_limit_commands"], [
                {"ACCEL": "1000", "ACCEL_TO_DECEL": "500"}])
            self.assertEqual(opted["unsupported_commands"], [])
            right.write_text(self.velocity_gcode(["SET_VELOCITY_LIMIT ACCEL=1000 ACCEL_TO_DECEL=0"]),
                             encoding="utf-8")
            invalid_legacy = analyze(right, legacy_klipper_accel_to_decel=True)
            self.assertTrue(any("ACCEL_TO_DECEL must be greater than zero" in error
                                for error in invalid_legacy["unsupported_commands"]))

            output = StringIO()
            right.write_text(self.velocity_gcode(command), encoding="utf-8")
            with redirect_stdout(output):
                code = main(["compare", str(left), str(right), "--legacy-klipper-accel-to-decel"])
            inspection = StringIO()
            with redirect_stdout(inspection):
                self.assertEqual(main(["inspect", str(left), "--legacy-klipper-accel-to-decel"]), 0)
            inspected = json.loads(inspection.getvalue())
            self.assertTrue(inspected["velocity_limit_legacy_mode"])
            self.assertIn("not a firmware compatibility claim", inspected["comparison_notice"])
        self.assertEqual(code, 0)
        self.assertIn("LEGACY KLIPPER PARAMETER MODE", output.getvalue())
        self.assertIn("not a firmware compatibility claim", output.getvalue())

    def test_old_json_missing_velocity_sequence_cannot_match_raw_command_file(self):
        with tempfile.TemporaryDirectory() as directory:
            old = analyze(FIXTURES / "absolute.gcode")
            old.pop("velocity_limit_commands")
            old.pop("velocity_limit_legacy_mode")
            json_path = Path(directory) / "old.json"
            raw_path = Path(directory) / "candidate.gcode"
            json_path.write_text(json.dumps(old), encoding="utf-8")
            raw_path.write_text(self.velocity_gcode(["SET_VELOCITY_LIMIT ACCEL=500"]), encoding="utf-8")
            errors = compare(load_report(json_path), analyze(raw_path))
        self.assertTrue(any("sequence differs (0 vs 1 commands)" in error for error in errors))

    def test_old_json_without_velocity_commands_can_match_raw_file_without_them(self):
        with tempfile.TemporaryDirectory() as directory:
            old = analyze(FIXTURES / "absolute.gcode")
            old.pop("velocity_limit_commands")
            old.pop("velocity_limit_legacy_mode")
            json_path = Path(directory) / "old.json"
            raw_path = Path(directory) / "candidate.gcode"
            json_path.write_text(json.dumps(old), encoding="utf-8")
            raw_path.write_bytes((FIXTURES / "absolute.gcode").read_bytes())
            errors = compare(load_report(json_path), analyze(raw_path))
        self.assertEqual(errors, [])

    def test_legacy_opt_in_does_not_allow_unknown_printer_macros(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "macro.gcode"
            path.write_text(self.velocity_gcode([
                "SET_VELOCITY_LIMIT ACCEL=1000 ACCEL_TO_DECEL=500", "PRINT_START",
            ]), encoding="utf-8")
            report = analyze(path, legacy_klipper_accel_to_decel=True)
        self.assertEqual(report["unsupported_commands"], ["PRINT_START"])
        self.assertTrue(compare(report, report))


if __name__ == "__main__":
    unittest.main()
