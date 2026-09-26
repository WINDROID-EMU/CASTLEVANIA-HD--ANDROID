cmake_minimum_required(VERSION 3.22.1)

project("castlevania_mesen" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

get_filename_component(ROOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(THIRD_PARTY_DIR "${ROOT_DIR}/third_party")

set(RCHEEVOS_DIR "${THIRD_PARTY_DIR}/rcheevos")

# Includes
include_directories(
    ${THIRD_PARTY_DIR}
    ${THIRD_PARTY_DIR}/Core
    ${THIRD_PARTY_DIR}/NES
    ${THIRD_PARTY_DIR}/Utilities
    ${THIRD_PARTY_DIR}/SevenZip
    ${RCHEEVOS_DIR}/include
)

add_definitions(-DMESEN_NES_ONLY)
add_definitions(-D__ANDROID__)
add_definitions(-DRC_HASH_NO_DISC)
add_definitions(-DRC_HASH_NO_ENCRYPTED)
add_compile_options(-Wno-undefined-inline -Wno-switch -Wno-unused-variable -O3 -fPIC)

# Find all source files for Mesen NES + Utilities
file(GLOB_RECURSE CORE_SHARED_SRC
    "${THIRD_PARTY_DIR}/Core/Shared/*.cpp"
)

file(GLOB_RECURSE CORE_SNES_INPUT_SRC
    "${THIRD_PARTY_DIR}/Core/SNES/Input/*.cpp"
)

file(GLOB_RECURSE CORE_NETPLAY_SRC
    "${THIRD_PARTY_DIR}/Core/Netplay/*.cpp"
)

file(GLOB_RECURSE NES_SRC
    "${THIRD_PARTY_DIR}/NES/*.cpp"
)

file(GLOB_RECURSE UTILITIES_SRC
    "${THIRD_PARTY_DIR}/Utilities/*.cpp"
    "${THIRD_PARTY_DIR}/Utilities/*.c"
)

file(GLOB_RECURSE SEVENZIP_SRC
    "${THIRD_PARTY_DIR}/SevenZip/*.c"
)

file(GLOB_RECURSE RCHEEVOS_SRC
    "${RCHEEVOS_DIR}/src/*.c"
)
list(FILTER RCHEEVOS_SRC EXCLUDE REGEX "rc_libretro\\.c$")
list(FILTER RCHEEVOS_SRC EXCLUDE REGEX "hash_encrypted\\.c$")
list(FILTER RCHEEVOS_SRC EXCLUDE REGEX "hash_disc\\.c$")
list(FILTER RCHEEVOS_SRC EXCLUDE REGEX "cdreader\\.c$")

add_library(castlevania SHARED
    jni_bridge.cpp
    "${THIRD_PARTY_DIR}/Core/pch.cpp"
    "${THIRD_PARTY_DIR}/Core/Debugger/DebuggerStub.cpp"
    ${CORE_SHARED_SRC}
    ${CORE_SNES_INPUT_SRC}
    ${CORE_NETPLAY_SRC}
    ${NES_SRC}
    ${UTILITIES_SRC}
    ${SEVENZIP_SRC}
    ${RCHEEVOS_SRC}
)

find_library(LOG_LIB log)
find_library(GLES2_LIB GLESv2)
find_library(EGL_LIB EGL)
find_library(ANDROID_LIB android)

target_link_libraries(castlevania
    ${LOG_LIB}
    ${GLES2_LIB}
    ${EGL_LIB}
    ${ANDROID_LIB}
)
