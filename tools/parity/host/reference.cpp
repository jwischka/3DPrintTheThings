#include "sapil_presets.h"
#include "libslic3r/Model.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/libslic3r.h"
#include <nlohmann/json.hpp>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>

int main(int argc, char** argv)
{
    if (argc != 5) {
        std::cerr << "Usage: preset-reference <manifest.json> <model.stl> <output-dir> <resources-dir>\n";
        return 2;
    }
    try {
        namespace fs = std::filesystem;
        const fs::path output = fs::absolute(argv[3]);
        fs::create_directories(output);
        Slic3r::set_resources_dir(argv[4]);
        auto loaded = sapil::load_full_presets(argv[1]);
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
        nlohmann::json effective = nlohmann::json::object();
        for (const auto& key : loaded.config.keys())
            effective[key] = loaded.config.option(key)->serialize();
        std::ofstream config_file(output / "effective-config.json");
        config_file << effective.dump(2) << '\n';
        if (!config_file) throw std::runtime_error("Cannot write effective configuration");
        std::ofstream inputs_file(output / "resolved-inputs.json");
        inputs_file << nlohmann::json(loaded.resolved_inputs).dump(2) << '\n';
        if (!inputs_file) throw std::runtime_error("Cannot write resolved inputs");
        print.process();
        const auto gcode = print.export_gcode((output / "cube.gcode").string(), nullptr);
        if (!fs::is_regular_file(gcode) || fs::file_size(gcode) == 0)
            throw std::runtime_error("No reference G-code produced");
        std::cout << "Linux reference slice complete\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "Reference slice failed: " << error.what() << '\n';
        return 1;
    }
}
