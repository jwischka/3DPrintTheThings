#!/usr/bin/env python3
"""Run real native slicing smoke and provenance capture through bounded adb."""
import argparse
import importlib.util
import subprocess
import sys
from pathlib import Path

runner_path = Path(__file__).resolve().parents[2] / "tools" / "run-browser-instrumentation.py"
spec = importlib.util.spec_from_file_location("instrumentation_runner", runner_path)
if spec is None or spec.loader is None:
    raise RuntimeError("Cannot load instrumentation runner")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
CORE_TESTS = (
    "com.u1.slicer.slicing.SlicingIntegrationTest#tetrahedron_stl_slicesSuccessfully,"
    "com.u1.slicer.slicing.SlicingIntegrationTest#tetrahedron_stl_slicesSuccessfully_withRotation,"
    "com.u1.slicer.slicing.BambuPipelineIntegrationTest#calibCube_dualColour_noBowdenUnloadSequence,"
    "com.u1.slicer.slicing.AndroidParityCaptureTest#captureCube10mmGcode,"
    "com.u1.slicer.slicing.NativeContractInstrumentedTest#temperatureCommandsReflectConfiguredValues,"
    "com.u1.slicer.slicing.NativeContractInstrumentedTest#singleToolRetractionAndBedBounds,"
    "com.u1.slicer.slicing.NativeContractInstrumentedTest#dualColorToolMappingAndRetraction,"
    "com.u1.slicer.slicing.NativeContractInstrumentedTest#cancellationDuringProcessingAllowsNextSlice,"
    "com.u1.slicer.slicing.NativeContractInstrumentedTest#cancellationAtExportTransitionAllowsNextSlice,"
    "com.u1.slicer.slicing.NativeContractInstrumentedTest#cancellationAtResultReadingAllowsNextSlice,"
    "com.u1.slicer.slicing.NativeContractInstrumentedTest#crossThreadCancellationDuringProcessingAllowsNextSlice,"
    "com.u1.slicer.printer.DownloadSliceUploadInstrumentedTest#downloadedLibrarySelectionSlicesAndUploadsExactGcode,"
    "com.u1.slicer.printer.DownloadSliceUploadInstrumentedTest#failedFixtureDownloadLeavesNoEntryOrUpload,"
    "com.u1.slicer.printer.DownloadSliceUploadInstrumentedTest#truncatedFixtureCleansPartialAndNormalRetryPersists"
)
UI_TEST = "com.u1.slicer.MainActivityWorkflowInstrumentedTest#libraryOpenPrepareSlicePreviewAndUploadOnly"
PRESET_UI_TEST = (
    "com.u1.slicer.FullPresetWorkflowInstrumentedTest#importSelectSliceAndExport,"
    "com.u1.slicer.FullPresetPreviewInstrumentedTest#rendersImportedStlAfterEglContextRecreation,"
    "com.u1.slicer.FullPresetPreviewInstrumentedTest#rendersModelWithProjectColorsAndKeepsItOnRejectedImport,"
    "com.u1.slicer.FullPresetPrinterInstrumentedTest#showsPrinterProgressCameraAndConnectionFailures,"
    "com.u1.slicer.FullPresetLayoutInstrumentedTest#landscapeKeepsPreviewAndActionsReachableAtLargeText"
)
PRESET_TESTS = ",".join(
    "com.u1.slicer.slicing.NativePresetInstrumentedTest#" + method
    for method in (
        "captureActualPinnedPreset", "captureDiagnosticPinnedPreset",
        "invalidManifestBlocksSliceUntilReset", "childOverridesParentAndSliceConfigCannotOverrideIt",
        "distinctFilamentChildrenPreserveSlotOrder", "invalidValuesAndTopologyFailClosed",
        "fullPresetRejectsProjectInput", "wrongTypeTraversalAndCycleFailClosed",
        "geometryOnlyProjectUsesPresetsAndPreservesSource",
        "geometryOnlyRejectsPaintAndModifierVolumes",
        "geometryOnlyCentersStlOnSelectedBedWithoutMovingSource",
    )
)
GEOMETRY_TESTS = (
    "com.u1.slicer.slicing.SlicingIntegrationTest#setModelRotation_invalidatesPreviewMesh_soSubsequentFetchReturnsRotatedGeometry,"
    "com.u1.slicer.slicing.SlicingIntegrationTest#setModelRotation_sameValueSkips_cacheNotInvalidated,"
    "com.u1.slicer.slicing.SlicingIntegrationTest#f77_twoStls_placeAndSlice_gcodeWithinBedBounds,"
    "com.u1.slicer.slicing.SlicingIntegrationTest#f83_mmScalePath_producesExpectedSlicedHeight,"
    "com.u1.slicer.viewer.NativePreparePreviewTest#getPreparePreviewMesh_reportsBatchTriangleCountsThatSumToThePreviewMesh,"
    "com.u1.slicer.viewer.NativePreparePreviewTest#preparePreviewScene_duplicateReleaseIsRejected"
)


def capture_device_diagnostics(adb: str, directory: Path, stage: str, failed: bool = False) -> None:
    commands = [
        ["shell", "cat", "/proc/meminfo"],
        ["shell", "dumpsys", "meminfo"],
    ]
    if failed:
        package = runner.RUNNER.split("/", 1)[0].removesuffix(".test")
        commands.extend([
            ["shell", "dumpsys", "activity", "exit-info", package],
            ["shell", "dmesg"],
            ["logcat", "-b", "all", "-d", "-t", "4000", "-v", "threadtime"],
        ])
    try:
        directory.mkdir(parents=True, exist_ok=True)
        with (directory / f"device-diagnostics-{stage}.log").open("wb") as log:
            for command in commands:
                log.write(("$ " + " ".join([adb, *command]) + "\n").encode("utf-8"))
                try:
                    result = subprocess.run([adb, *command], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                            timeout=15)
                    log.write(result.stdout[-2 * 1024 * 1024:])
                    log.write(f"\nexit={result.returncode}\n\n".encode("utf-8"))
                except subprocess.TimeoutExpired as exc:
                    log.write((exc.stdout or b"")[-2 * 1024 * 1024:])
                    log.write(b"\nCommand timed out\n\n")
                except OSError as exc:
                    log.write(f"Unable to run command: {exc}\n\n".encode("utf-8", "replace"))
    except OSError as exc:
        print(f"Unable to write device diagnostics: {exc}", file=sys.stderr)


selection = argparse.ArgumentParser(add_help=False)
selection.add_argument("--suite", choices=("core", "geometry", "ui", "presets", "preset-ui", "all"), default="all")
selection.add_argument("--adb", default="adb")
selection.add_argument("--device-diagnostics", type=Path)
options, remaining = selection.parse_known_args()
runner.TEST_CLASS = {"core": CORE_TESTS, "geometry": GEOMETRY_TESTS, "ui": UI_TEST,
                     "presets": PRESET_TESTS, "preset-ui": PRESET_UI_TEST,
                     "all": CORE_TESTS + "," + GEOMETRY_TESTS + "," + UI_TEST + "," + PRESET_TESTS}[options.suite]
sys.argv = [sys.argv[0], "--adb", options.adb, *remaining]
if options.suite == "preset-ui":
    runner.RUNNER = "io.github.jwischka.threedprintthethings.presets.test/androidx.test.runner.AndroidJUnitRunner"
runner.EXPECTED_TESTS = len(runner.TEST_CLASS.split(","))
if options.device_diagnostics is None:
    sys.exit(runner.main())

capture_device_diagnostics(options.adb, options.device_diagnostics, "before")
result = 1
try:
    result = runner.main()
finally:
    capture_device_diagnostics(options.adb, options.device_diagnostics, "after", failed=result != 0)
sys.exit(result)
