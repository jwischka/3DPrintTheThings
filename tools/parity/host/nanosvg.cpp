// Upstream supplies NanoSVG's implementation from GUI/BitmapCache.cpp. The
// headless reference needs the same bundled parser without that wxWidgets unit.
#define NANOSVG_IMPLEMENTATION
#include "nanosvg/nanosvg.h"
