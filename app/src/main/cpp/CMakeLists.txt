cmake_minimum_required(VERSION 3.22.1)

project("lumacore_native")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
    lumacore_native
    SHARED
    lumacore_native.cpp
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
