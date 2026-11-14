#!/usr/bin/env python3
"""Run real native slicing smoke and provenance capture through bounded adb."""
import importlib.util
import sys
from pathlib import Path

runner_path = Path(__file__).resolve().parents[2] / "tools" / "run-browser-instrumentation.py"
spec = importlib.util.spec_from_file_location("instrumentation_runner", runner_path)
if spec is None or spec.loader is None:
    raise RuntimeError("Cannot load instrumentation runner")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
runner.TEST_CLASS = (
    "com.u1.slicer.slicing.SlicingIntegrationTest#tetrahedron_stl_slicesSuccessfully,"
    "com.u1.slicer.slicing.SlicingIntegrationTest#tetrahedron_stl_slicesSuccessfully_withRotation,"
    "com.u1.slicer.slicing.BambuPipelineIntegrationTest#calibCube_dualColour_noBowdenUnloadSequence,"
    "com.u1.slicer.slicing.AndroidParityCaptureTest#captureCube10mmGcode,"
    "com.u1.slicer.slicing.NativeContractInstrumentedTest#temperatureCommandsReflectConfiguredValues,"
    "com.u1.slicer.slicing.NativeContractInstrumentedTest#singleToolRetractionAndBedBounds,"
    "com.u1.slicer.slicing.NativeContractInstrumentedTest#dualColorToolMappingAndRetraction,"
    "com.u1.slicer.slicing.NativeContractInstrumentedTest#cancellationDuringProcessingAllowsNextSlice,"
    "com.u1.slicer.slicing.NativeContractInstrumentedTest#cancellationAtExportTransitionAllowsNextSlice,"
    "com.u1.slicer.printer.DownloadSliceUploadInstrumentedTest#downloadedLibrarySelectionSlicesAndUploadsExactGcode,"
    "com.u1.slicer.printer.DownloadSliceUploadInstrumentedTest#failedFixtureDownloadLeavesNoEntryOrUpload"
)
runner.EXPECTED_TESTS = len(runner.TEST_CLASS.split(","))
sys.exit(runner.main())
