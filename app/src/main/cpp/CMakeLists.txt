cmake_minimum_required(VERSION 3.22.1)

project("lumacore_native")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Main JNI shared library for Android runtime
add_library(
    lumacore_native
    SHARED
    lumacore_native.cpp
    lumacore_homography.cpp
    lumacore_pyramid.cpp
    lumacore_merge.cpp
    lumacore_bilateral_slice.cpp
    lumacore_sharpness.cpp
    lumacore_super_res.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(glesv3-lib GLESv3)

target_link_libraries(
    lumacore_native
    ${log-lib}
    ${android-lib}
    ${glesv3-lib}
)

# Standalone native test executable for native testing & benchmarking
add_executable(
    lumacore_native_tests
    test_burst_align.cpp
    lumacore_homography.cpp
    lumacore_pyramid.cpp
    lumacore_merge.cpp
    lumacore_bilateral_slice.cpp
    lumacore_sharpness.cpp
    lumacore_super_res.cpp
)

target_link_libraries(
    lumacore_native_tests
    ${log-lib}
)
