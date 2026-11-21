#pragma once

#include "libslic3r/PrintConfig.hpp"
#include <cstddef>
#include <string>
#include <vector>

namespace sapil {
struct FullPresetResult {
    Slic3r::DynamicPrintConfig config;
    size_t filament_count = 0;
    std::vector<std::string> resolved_inputs;
    std::vector<int> model_filament_mapping;
    bool geometry_only = false;
};
// Throws on invalid or unsupported input; no caller state is modified.
FullPresetResult load_full_presets(const std::string& manifest_path);
}
