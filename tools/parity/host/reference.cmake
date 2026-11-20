# Included at the end of the disposable, pinned upstream CMakeLists.txt.
# Build the upstream Linux core, without the Android port's source exclusions,
# compatibility headers, serial TBB shim or JNI/SAPIL slicing pipeline.
if(NOT TARGET opencv_world)
    add_library(opencv_world INTERFACE)
    target_link_libraries(opencv_world INTERFACE opencv_core)
endif()
add_executable(preset-reference
    "${PARITY_REPO}/tools/parity/host/reference.cpp"
    "${PARITY_REPO}/app/src/main/cpp/src/sapil_presets.cpp")
target_compile_features(preset-reference PRIVATE cxx_std_17)
target_include_directories(preset-reference PRIVATE
    "${PARITY_REPO}/app/src/main/cpp/src" "${CMAKE_SOURCE_DIR}/src"
    "${CMAKE_SOURCE_DIR}/deps_src" "${CMAKE_BINARY_DIR}/src")
target_link_libraries(preset-reference PRIVATE libslic3r boost_libs cereal::cereal libcurl)
