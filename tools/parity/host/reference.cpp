#include "sapil_presets.h"
#include "libslic3r/Model.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/GCode/GCodeProcessor.hpp"
#include "libslic3r/libslic3r.h"
#include <nlohmann/json.hpp>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <utility>

// For unchanged vendor presets, independently compose the reference through
// upstream's vendor importer. This prevents a defect shared by the manifest
// loader on both platforms from being mistaken for configuration parity.
Slic3r::DynamicPrintConfig vendor_reference(const std::filesystem::path& manifest,
                                          const std::filesystem::path& resources)
{
    std::ifstream input(manifest);
    const auto spec = nlohmann::json::parse(input);
    auto preset_name = [&](const std::string& path) {
        std::ifstream source(manifest.parent_path() / path);
        return nlohmann::json::parse(source).at("name").get<std::string>();
    };
    Slic3r::PresetBundle bundle;
    bundle.load_vendor_configs_from_json((resources / "profiles").string(), "Snapmaker",
        Slic3r::PresetBundle::LoadSystem, Slic3r::ForwardCompatibilitySubstitutionRule::Disable);
    auto select = [](Slic3r::PresetCollection& collection, const std::string& name) {
        if (!collection.find_preset(name, false))
            throw std::runtime_error("Selected preset missing from upstream vendor bundle: " + name);
        collection.select_preset_by_name(name, true);
        if (collection.get_selected_preset_name() != name)
            throw std::runtime_error("Upstream preset selection fell back");
    };
    select(bundle.printers, preset_name(spec.at("printer").get<std::string>()));
    select(bundle.prints, preset_name(spec.at("process").get<std::string>()));
    bundle.filament_presets.clear();
    for (const auto& path : spec.at("filaments")) {
        const auto name = preset_name(path.get<std::string>());
        if (!bundle.filaments.find_preset(name, false))
            throw std::runtime_error("Selected filament missing from upstream vendor bundle");
        if (bundle.filament_presets.empty()) select(bundle.filaments, name);
        bundle.filament_presets.push_back(name);
    }
    return bundle.full_config();
}

nlohmann::json serialize_config(const Slic3r::DynamicPrintConfig& config)
{
    nlohmann::json result = nlohmann::json::object();
    for (const auto& key : config.keys()) result[key] = config.option(key)->serialize();
    return result;
}

int main(int argc, char** argv)
{
    if (argc != 6 || (std::string(argv[5]) != "actual" && std::string(argv[5]) != "diagnostic")) {
        std::cerr << "Usage: preset-reference <manifest.json> <model.stl> <output-dir> <resources-dir> <actual|diagnostic>\n";
        return 2;
    }
    try {
        namespace fs = std::filesystem;
        const fs::path output = fs::absolute(argv[3]);
        fs::create_directories(output);
        Slic3r::set_resources_dir(argv[4]);
        auto loaded = sapil::load_full_presets(argv[1]);
        if (std::string(argv[5]) == "actual") {
            auto reference = vendor_reference(argv[1], argv[4]);
            const auto expected = serialize_config(reference);
            const auto actual = serialize_config(loaded.config);
            if (expected != actual) {
                std::ofstream(output / "vendor-config.json") << expected.dump(2) << '\n';
                std::ofstream(output / "manifest-config.json") << actual.dump(2) << '\n';
                throw std::runtime_error("Manifest composition differs from independent upstream vendor loading");
            }
            std::cerr << "Vendor and manifest configurations match (" << expected.size() << " keys)\n";
            loaded.config = std::move(reference);
        }
        auto model = Slic3r::Model::read_from_file(argv[2]);
        model.add_default_instances();
        // The fixed U1 comparison fixture uses the same centered placement on
        // both platforms. Placement is explicit, not a CLI auto-arrange result.
        model.center_instances_around_point(Slic3r::Vec2d(135.0, 135.0));
        for (auto* object : model.objects) object->ensure_on_bed();
        Slic3r::Print print;
        print.apply(model, loaded.config);
        const auto validation = print.validate();
        if (!validation.string.empty()) throw std::runtime_error(validation.string);
        const auto effective = serialize_config(loaded.config);
        std::ofstream config_file(output / "effective-config.json");
        config_file << effective.dump(2) << '\n';
        config_file.close();
        if (!config_file) throw std::runtime_error("Cannot write effective configuration");
        std::ofstream inputs_file(output / "resolved-inputs.json");
        inputs_file << nlohmann::json(loaded.resolved_inputs).dump(2) << '\n';
        inputs_file.close();
        if (!inputs_file) throw std::runtime_error("Cannot write resolved inputs");
        print.process();
        Slic3r::GCodeProcessorResult gcode_result;
        const auto gcode = print.export_gcode((output / "cube.gcode").string(), &gcode_result);
        if (!fs::is_regular_file(gcode) || fs::file_size(gcode) == 0)
            throw std::runtime_error("No reference G-code produced");
        std::cout << "Linux reference slice complete\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "Reference slice failed: " << error.what() << '\n';
        return 1;
    }
}
