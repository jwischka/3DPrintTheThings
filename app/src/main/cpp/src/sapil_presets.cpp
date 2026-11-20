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

namespace sapil {
namespace {
namespace fs = std::filesystem;
using Json = nlohmann::json;
struct Entry { std::string path, name, type, parent; Slic3r::DynamicPrintConfig own; };

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
        if (status != 0 || !reason.empty() || !substitutions.substitutions.empty() || !substitutions.unrecogized_keys.empty()) throw std::runtime_error("Invalid preset contents");
        entry.name = metadata["name"]; entry.type = metadata["type"]; entry.parent = metadata["inherits"];
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
        visiting.erase(name);
        result.resolved_inputs.push_back(found->second.path);
        return resolved.emplace(name, std::move(config)).first->second;
    };
    const auto& machine = resolve(by_path.at(printer), "machine");
    const auto& print = resolve(by_path.at(process), "process");
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
    bundle.printers.load_preset("", by_path.at(printer), machine, true);
    bundle.prints.load_preset("", by_path.at(process), print, true);
    bundle.filament_presets.clear();
    for (const auto& path : filaments) {
        const auto& name = by_path.at(path);
        const auto& filament = resolve(name, "filament");
        bundle.filaments.load_preset("", name, filament, bundle.filament_presets.empty());
        bundle.filament_presets.push_back(name);
    }
    result.config = bundle.full_config();
    return result;
}
}
