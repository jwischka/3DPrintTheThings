#include "sapil_presets.h"
#include "libslic3r/PresetBundle.hpp"
#include <nlohmann/json.hpp>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <functional>
#include <map>
#include <set>
#include <stdexcept>
#include <utility>

namespace sapil {
namespace {
namespace fs = std::filesystem;
using Json = nlohmann::json;
struct Entry { std::string path, name, type, parent, filament_id, setting_id, origin, instantiation; Slic3r::DynamicPrintConfig own; };

// These pinned vendor fields are deliberately discarded by upstream legacy
// conversion or belong to vendor presentation metadata rather than print config.
// Do not silently accept any other unknown key.
bool ignored_vendor_key(const std::string& type, const std::string& key) {
    if (type == "filament") return key == "filament_load_time" || key == "filament_unload_time";
    if (type == "machine") return key == "silent_mode" || key == "bed_model" || key == "bed_texture";
    if (type == "process") return key == "adaptive_layer_height" || key == "overhang_totally_speed" ||
        key == "smooth_coefficient" || key == "internal_bridge_support_thickness";
    return false;
}

fs::path confined_file(const fs::path& root, const std::string& relative) {
    fs::path rel(relative);
    if (rel.empty() || rel.is_absolute() || rel.has_root_name() || relative.find(':') != std::string::npos || relative.find('\\') != std::string::npos)
        throw std::runtime_error("Invalid preset path");
    for (const auto& part : rel) if (part == ".." || part == ".") throw std::runtime_error("Invalid preset path");
    auto path = fs::canonical(root / rel);
    auto r = root.begin(), p = path.begin();
    for (; r != root.end(); ++r, ++p) if (p == path.end() || *p != *r) throw std::runtime_error("Preset path escapes root");
    if (!fs::is_regular_file(path) || fs::file_size(path) > 1024 * 1024) throw std::runtime_error("Invalid preset file");
    return path;
}
}

FullPresetResult load_full_presets(const std::string& manifest_path) {
    const fs::path manifest = fs::canonical(manifest_path);
    if (!fs::is_regular_file(manifest) || fs::file_size(manifest) > 65536) throw std::runtime_error("Invalid preset manifest");
    const fs::path root = manifest.parent_path();
    std::ifstream stream(manifest);
    Json spec = Json::parse(stream);
    if (!spec.is_object() || spec.size() != 5 || spec.at("schema") != "orca-native-presets-v1") throw std::runtime_error("Unsupported preset manifest");
    const auto printer = spec.at("printer").get<std::string>();
    const auto process = spec.at("process").get<std::string>();
    const auto filaments = spec.at("filaments").get<std::vector<std::string>>();
    const auto catalog = spec.at("catalog").get<std::vector<std::string>>();
    if (filaments.empty() || filaments.size() > 16 || catalog.size() > 128) throw std::runtime_error("Invalid preset count");
    std::set<std::string> paths(catalog.begin(), catalog.end());
    paths.insert(printer); paths.insert(process); paths.insert(filaments.begin(), filaments.end());
    if (paths.size() > 146) throw std::runtime_error("Invalid preset count");
    std::map<std::string, Entry> entries;
    std::map<std::string, std::string> by_path;
    for (const auto& path : paths) {
        Entry entry;
        entry.path = path;
        std::map<std::string, std::string> metadata;
        std::string reason;
        Slic3r::ConfigSubstitutionContext substitutions(Slic3r::ForwardCompatibilitySubstitutionRule::Disable);
        const auto full = confined_file(root, path);
        const int status = entry.own.load_from_json(full.string(), substitutions, false, metadata, reason);
        if (status != 0 || !reason.empty()) throw std::runtime_error("Preset JSON parse failed");
        if (!substitutions.substitutions.empty()) throw std::runtime_error("Preset substitutions rejected");
        for (const auto& key : substitutions.unrecogized_keys)
            if (!ignored_vendor_key(metadata["type"], key)) throw std::runtime_error("Unknown preset keys");
        entry.name = metadata["name"]; entry.type = metadata["type"]; entry.parent = metadata["inherits"];
        entry.filament_id = metadata["filament_id"];
        entry.setting_id = metadata["setting_id"];
        entry.origin = metadata["from"];
        entry.instantiation = metadata["instantiation"];
        if (!entry.instantiation.empty() && entry.instantiation != "true" && entry.instantiation != "false")
            throw std::runtime_error("Invalid preset instantiation");
        if (entry.filament_id.empty() && entry.type == "filament" && entry.origin == "system") entry.filament_id = entry.setting_id;
        if (entry.name.empty() || (entry.type != "machine" && entry.type != "process" && entry.type != "filament")) throw std::runtime_error("Invalid preset identity");
        by_path.emplace(path, entry.name);
        if (!entries.emplace(entry.name, std::move(entry)).second) throw std::runtime_error("Duplicate preset name");
    }
    Slic3r::PresetBundle bundle;
    std::map<std::string, Slic3r::DynamicPrintConfig> resolved;
    std::set<std::string> visiting;
    FullPresetResult result;
    result.filament_count = filaments.size();
    std::function<const Slic3r::DynamicPrintConfig&(const std::string&, const std::string&)> resolve;
    resolve = [&](const std::string& name, const std::string& type) -> const Slic3r::DynamicPrintConfig& {
        auto found = entries.find(name);
        if (found == entries.end() || found->second.type != type) throw std::runtime_error("Missing or wrong-category preset parent");
        auto done = resolved.find(name);
        if (done != resolved.end()) return done->second;
        if (!visiting.insert(name).second) throw std::runtime_error("Preset inheritance cycle");
        Slic3r::DynamicPrintConfig config;
        if (!found->second.parent.empty()) config = resolve(found->second.parent, type);
        else if (type == "machine") config = bundle.printers.default_preset_for(found->second.own).config;
        else if (type == "process") config = bundle.prints.default_preset().config;
        else config = bundle.filaments.default_preset().config;
        config.apply(found->second.own);
        if (found->second.origin != "system") Slic3r::Preset::inherits(config) = found->second.parent;
        if (found->second.instantiation != "false") {
            Slic3r::Preset::normalize(config);
            const auto& defaults = type == "machine" ? bundle.printers.default_preset_for(found->second.own).config :
                type == "process" ? bundle.prints.default_preset().config : bundle.filaments.default_preset().config;
            if (!Slic3r::Preset::remove_invalid_keys(config, defaults).empty())
                throw std::runtime_error("Preset contains keys from another category");
        }
        visiting.erase(name);
        result.resolved_inputs.push_back(found->second.path);
        return resolved.emplace(name, std::move(config)).first->second;
    };
    const auto& machine = resolve(by_path.at(printer), "machine");
    const auto& print = resolve(by_path.at(process), "process");
    if (entries.at(by_path.at(printer)).instantiation == "false" || entries.at(by_path.at(process)).instantiation == "false")
        throw std::runtime_error("Selected preset is not instantiable");
    for (const auto& path : filaments) {
        const auto& name = by_path.at(path);
        if (entries.at(name).instantiation == "false") throw std::runtime_error("Selected preset is not instantiable");
        resolve(name, "filament");
    }
    const auto* nozzles = machine.option<Slic3r::ConfigOptionFloats>("nozzle_diameter");
    const auto* semm = machine.option<Slic3r::ConfigOptionBool>("single_extruder_multi_material");
    const auto* technology = machine.option<Slic3r::ConfigOptionEnumGeneric>("printer_technology");
    if (!technology || technology->value != Slic3r::ptFFF)
        throw std::runtime_error("Only FFF printer presets are supported");
    if (!nozzles || nozzles->values.empty() || nozzles->values.size() > 16 ||
        (filaments.size() > nozzles->values.size() &&
         !(nozzles->values.size() == 1 && semm && semm->value)))
        throw std::runtime_error("Unsupported preset extruder topology");
    for (double diameter : nozzles->values)
        if (!std::isfinite(diameter) || diameter <= 0) throw std::runtime_error("Invalid nozzle diameter");
    for (const char* key : {"support_filament", "support_interface_filament", "wipe_tower_filament",
                            "wall_filament", "sparse_infill_filament", "solid_infill_filament"}) {
        const auto* index = print.option<Slic3r::ConfigOptionInt>(key);
        if (index && (index->value < 0 || static_cast<size_t>(index->value) > filaments.size()))
            throw std::runtime_error("Preset references an unavailable filament");
    }
    for (const auto& item : resolved) {
        const auto& entry = entries.at(item.first);
        if (entry.instantiation == "false") continue;
        Slic3r::PresetCollection* collection = nullptr;
        if (entry.type == "machine") collection = &bundle.printers;
        else if (entry.type == "process") collection = &bundle.prints;
        else collection = &bundle.filaments;
        auto& preset = collection->load_preset("", entry.name, item.second, false);
        preset.is_system = entry.origin == "system";
        preset.setting_id = entry.setting_id;
        const Entry* ancestor = &entry;
        while (ancestor->filament_id.empty() && !ancestor->parent.empty()) ancestor = &entries.at(ancestor->parent);
        preset.filament_id = ancestor->filament_id;
        if (entry.type == "filament" && entry.origin == "system" && preset.filament_id.empty())
            throw std::runtime_error("System filament has no filament identity");
    }
    bundle.printers.select_preset_by_name(by_path.at(printer), true);
    bundle.prints.select_preset_by_name(by_path.at(process), true);
    bundle.filament_presets.clear();
    for (const auto& path : filaments) {
        const auto& name = by_path.at(path);
        if (bundle.filament_presets.empty()) bundle.filaments.select_preset_by_name(name, true);
        bundle.filament_presets.push_back(name);
    }
    result.config = bundle.full_config();
    if (!result.config.validate(false).empty())
        throw std::runtime_error("Invalid full preset configuration");
    return result;
}
}
