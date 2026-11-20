#ifndef SAPIL_INTERNAL_H
#define SAPIL_INTERNAL_H

#include "../include/sapil.h"
#include "libslic3r/PrintConfig.hpp"
#include <cstddef>
#include <mutex>
#include <string>

namespace sapil {

struct SlicerEngine::Impl {
    enum class PresetState { Legacy, Ready, Invalid };
    mutable std::mutex preset_mutex;
    PresetState preset_state = PresetState::Legacy;
    Slic3r::DynamicPrintConfig full_preset_config;
    size_t full_preset_filament_count = 0;
    std::string last_gcode_path;
    std::string last_gcode_content;
    Slic3r::DynamicPrintConfig print_config;
    Slic3r::DynamicPrintConfig printer_config;
    Slic3r::DynamicPrintConfig filament_config;
};

} // namespace sapil

#endif // SAPIL_INTERNAL_H
