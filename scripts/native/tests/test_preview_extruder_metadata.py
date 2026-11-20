"""Exercise the production metadata parser with the host C++ standard library."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class PreviewExtruderMetadataTests(unittest.TestCase):
    def test_declared_tools_survive_metadata_attribute_boundaries(self):
        compiler = shutil.which("c++") or shutil.which("g++")
        if compiler is None:
            self.skipTest("Host C++ compiler unavailable; exercised in native Linux CI")
        root = Path(__file__).resolve().parents[3]
        source = (root / "app/src/main/cpp/src/sapil_model.cpp").read_text(encoding="utf-8")
        start = source.index("static std::vector<std::vector<int>> parsePreviewExtrudersFromModelConfig(")
        end = source.index("bool SlicerEngine::loadModel(", start)
        parser = source[start:end]
        harness = r'''
#include <algorithm>
#include <iostream>
#include <regex>
#include <string>
#include <utility>
#include <vector>
''' + parser + r'''
int main() {
    const auto tools = parsePreviewExtrudersFromModelConfig(R"xml(
      <config><object id="1">
        <part id="1"><metadata key="extruder" value="1"/></part>
        <part id="2"><metadata key="extruder" value="2"/></part>
      </object><object id="2"><metadata key="extruder" value="3"/></object>
      </config>)xml");
    if (tools != std::vector<std::vector<int>>{{1, 2}, {3}}) {
        std::cerr << "Lost declared part/object tools at XML attribute boundaries\n";
        return 1;
    }
    const auto unrelated = parsePreviewExtrudersFromModelConfig(R"xml(
      <object id="1"><part id="1">
        <metadata wrong_key="extruder" value="2"/>
        <metadata key="extruder" wrong_value="3"/>
      </part></object>)xml");
    if (unrelated != std::vector<std::vector<int>>{{}}) {
        std::cerr << "Accepted an unrelated metadata attribute\n";
        return 1;
    }
}
'''
        with tempfile.TemporaryDirectory(prefix="preview-metadata-") as directory:
            directory = Path(directory)
            cpp = directory / "metadata.cpp"
            binary = directory / "metadata-test"
            cpp.write_text(harness, encoding="utf-8")
            built = subprocess.run([compiler, "-std=c++17", str(cpp), "-o", str(binary)],
                                   capture_output=True, text=True, timeout=60)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            checked = subprocess.run([str(binary)], capture_output=True, text=True, timeout=10)
            self.assertEqual(checked.returncode, 0, checked.stdout + checked.stderr)


if __name__ == "__main__":
    unittest.main()
