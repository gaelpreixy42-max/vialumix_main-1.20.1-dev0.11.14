cmake_minimum_required(VERSION 3.24)
project(vialumix_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(JNI REQUIRED)
find_package(Vulkan REQUIRED)

add_library(vialumix SHARED src/vialumix_jni.cpp)
target_include_directories(vialumix PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(vialumix PRIVATE Vulkan::Vulkan)

if (WIN32)
    set_target_properties(vialumix PROPERTIES OUTPUT_NAME "vialumix")
elseif(UNIX)
    set_target_properties(vialumix PROPERTIES OUTPUT_NAME "vialumix")
endif()

# Optional bridge to the real MCVR source tree supplied with the project. This is deliberately
# opt-in because MCVR has its own third-party submodules and must be built with its matching
# generated JNI headers. Configure with -DVIALUMIX_MCVR_ROOT=<path> when building the native backend.
set(VIALUMIX_MCVR_ROOT "" CACHE PATH "Path to a checked-out MCVR source tree")
if (VIALUMIX_MCVR_ROOT AND EXISTS "${VIALUMIX_MCVR_ROOT}/CMakeLists.txt")
    message(STATUS "Vialumix: MCVR bridge source detected at ${VIALUMIX_MCVR_ROOT}")
    add_subdirectory("${VIALUMIX_MCVR_ROOT}" "${CMAKE_BINARY_DIR}/mcvr")
else()
    message(STATUS "Vialumix: standalone native capability probe; MCVR bridge not configured")
endif()
