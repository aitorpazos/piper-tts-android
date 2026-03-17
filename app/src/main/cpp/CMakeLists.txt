cmake_minimum_required(VERSION 3.18)
project(espeak-jni C CXX)

# ── espeak-ng configuration ─────────────────────────────────────────────
# Build espeak-ng as a static library, then wrap it in our JNI shared lib.
# Disable features we don't need on Android.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(USE_ASYNC OFF CACHE BOOL "" FORCE)
set(USE_MBROLA OFF CACHE BOOL "" FORCE)
set(USE_LIBPCAUDIO OFF CACHE BOOL "" FORCE)
set(USE_LIBSONIC OFF CACHE BOOL "" FORCE)
set(USE_KLATT ON CACHE BOOL "" FORCE)
set(USE_SPEECHPLAYER ON CACHE BOOL "" FORCE)
set(COMPILE_INTONATIONS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(ESPEAK_COMPAT OFF CACHE BOOL "" FORCE)

# Add the espeak-ng submodule (only builds the library, not the CLI)
add_subdirectory(espeak-ng/src/ucd-tools)
add_subdirectory(espeak-ng/src/speechPlayer)

# We need espeak-include target before libespeak-ng
add_library(espeak-include INTERFACE)
target_include_directories(espeak-include INTERFACE
    espeak-ng/src/include
    espeak-ng/src/include/compat
)
target_link_libraries(espeak-include INTERFACE ucd)

add_subdirectory(espeak-ng/src/libespeak-ng)

# ── JNI bridge ──────────────────────────────────────────────────────────
add_library(espeak_jni SHARED espeak_jni.cpp)

target_include_directories(espeak_jni PRIVATE
    espeak-ng/src/include
    espeak-ng/src/include/compat
)

target_link_libraries(espeak_jni PRIVATE
    espeak-ng
    log
)
