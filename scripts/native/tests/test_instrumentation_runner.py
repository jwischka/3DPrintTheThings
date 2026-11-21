import importlib.util
from pathlib import Path
import unittest


path = Path(__file__).resolve().parents[3] / "tools" / "run-browser-instrumentation.py"
spec = importlib.util.spec_from_file_location("instrumentation_runner", path)
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


def result(count=2):
    return ("INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\n" * count
            + f"OK ({count} tests)\nINSTRUMENTATION_CODE: -1\n")


class InstrumentationResultTest(unittest.TestCase):
    def test_complete_run(self):
        self.assertTrue(runner.passed(result(), 0, 2))
        self.assertTrue(runner.passed(result(1), 0))

    def test_missing_selected_test(self):
        self.assertFalse(runner.passed(result(1), 0, 2))

    def test_selected_class_must_complete(self):
        start, complete = result(1).split("INSTRUMENTATION_STATUS_CODE: 0", 1)
        first_class = "INSTRUMENTATION_STATUS: class=ExampleLayoutTest\n"
        other_class = "INSTRUMENTATION_STATUS: class=OtherTest\n"
        complete = "INSTRUMENTATION_STATUS_CODE: 0" + complete
        expected_classes = ("ExampleLayoutTest",)
        self.assertTrue(runner.passed(first_class + start + first_class + complete, 0,
                                      expected_classes=expected_classes))
        self.assertFalse(runner.passed(first_class + start + other_class + complete, 0,
                                       expected_classes=expected_classes))

    def test_summary_cannot_hide_missing_completion(self):
        self.assertFalse(runner.passed(result().replace(
            "INSTRUMENTATION_STATUS_CODE: 0\n", "", 1), 0))

    def test_duplicate_completion_is_rejected(self):
        self.assertFalse(runner.passed(result() + "INSTRUMENTATION_STATUS_CODE: 0\n", 0))

    def test_skip_failure_or_unknown_status_is_rejected(self):
        for status in (-1, -2, -3, -4, -999, 2):
            with self.subTest(status=status):
                self.assertFalse(runner.passed(result() + f"INSTRUMENTATION_STATUS_CODE: {status}\n", 0))

    def test_no_tests_and_process_failure_are_rejected(self):
        self.assertFalse(runner.passed(result(0), 0))
        self.assertFalse(runner.passed(result(), 1))
        self.assertFalse(runner.passed(result().replace("INSTRUMENTATION_CODE: -1", ""), 0))


if __name__ == "__main__":
    unittest.main()
