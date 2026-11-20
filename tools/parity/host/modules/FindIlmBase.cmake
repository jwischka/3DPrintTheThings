# OpenEXR 3 provides the former IlmBase Half implementation through Imath.
find_package(Imath CONFIG REQUIRED)
if(NOT TARGET IlmBase::Half)
    add_library(IlmBase::Half INTERFACE IMPORTED GLOBAL)
    set_target_properties(IlmBase::Half PROPERTIES INTERFACE_LINK_LIBRARIES Imath::Imath)
endif()
set(IlmBase_FOUND TRUE)
