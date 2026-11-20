# The pinned upstream finder predates OpenEXR 3's extraction of Half into Imath.
# Map its old target name to the real system library, without changing source or
# substituting the implementation. Used only by the independent Linux build.
list(PREPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_LIST_DIR}/modules")
