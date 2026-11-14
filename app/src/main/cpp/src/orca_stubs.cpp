// Stub implementations for OrcaSlicer features excluded from Android build
// These satisfy references from libslic3r while disabling the affected features.

// Use our Android STEP.hpp stub (provides Step class without OCCT dependency).
// Include path has extern/libslic3r_android first, so "Format/STEP.hpp" picks
// up our stub before the real OCCT-dependent header.
#include "Format/STEP.hpp"
#include "libslic3r/Feature/FuzzySkin/FuzzySkin.hpp"
#include "libslic3r/Polygon.hpp"
#include <cstddef>
#include <string>
#include <vector>
#include <functional>

namespace Slic3r {
// STEP — excluded (needs OCCT). Implement Step class methods declared in stub header.
Step::Step(std::string, ImportStepProgressFn, StepIsUtf8Fn) {}
bool Step::load() { return false; }

bool load_step(const char*, Model*, bool&,
               double, double, bool,
               ImportStepProgressFn, StepIsUtf8Fn,
               long&) {
    return false;
}

#ifndef SLIC3R_NATIVE_REAL_FUZZY_SKIN
// The fallback preserves the input contour when the feature is unavailable.
namespace Feature { namespace FuzzySkin {
void apply_fuzzy_skin(Arachne::ExtrusionLine*, const PerimeterGenerator&, bool) {}
Polygon apply_fuzzy_skin(const Polygon& polygon, const PerimeterGenerator&, size_t, bool) { return polygon; }
void group_region_by_fuzzify(PerimeterGenerator&) {}
} } // namespace Feature::FuzzySkin
#endif

// SVG loading — excluded (needs OCCT 7.6)
bool load_svg(const char*, Model*, std::string&) {
    return false;
}

#ifndef SLIC3R_NATIVE_REAL_PNG
namespace png {
// PNG writing — excluded (needs real libpng)
bool write_rgb_to_file_scaled(const std::string&, size_t, size_t,
                               const std::vector<unsigned char>&, size_t) {
    return false;
}
} // namespace png
#endif
} // namespace Slic3r
