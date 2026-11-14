#!/usr/bin/env python3
"""Use the shared bounded adb runner for one real native slicing smoke test."""
import importlib.util
import sys
from pathlib import Path

runner_path = Path(__file__).resolve().parents[2] / "tools" / "run-browser-instrumentation.py"
spec = importlib.util.spec_from_file_location("instrumentation_runner", runner_path)
if spec is None or spec.loader is None:
    raise RuntimeError("Cannot load instrumentation runner")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
runner.TEST_CLASS = "com.u1.slicer.slicing.SlicingIntegrationTest#tetrahedron_stl_slicesSuccessfully"
sys.exit(runner.main())
