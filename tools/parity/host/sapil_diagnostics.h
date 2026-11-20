#pragma once

#include <iostream>
#include <mutex>

// The pinned fork's TreeSupport3D.cpp calls this external diagnostic hook when
// reporting an out-of-range point. Keep its logging on the host without importing
// the Android JNI/logging headers. This hook never changes geometry or control flow.
namespace sapil {
inline void diagnostics_note_clipper_point(long long x, long long y, const char* source)
{
    static std::mutex mutex;
    std::lock_guard<std::mutex> lock(mutex);
    std::cerr << "Clipper point diagnostic: " << source << " (" << x << ", " << y << ")\n";
}
}
