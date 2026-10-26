cmake_minimum_required(VERSION 3.22.1)
project(voiceclean_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------------------
# One shared ggml (decision D11): llama.cpp's ggml is built once; whisper.cpp
# (its own ggml copy deleted) is compiled against it. CPU backend is built as
# runtime-dispatched variants (GGML_CPU_ALL_VARIANTS + GGML_BACKEND_DL): the
# best libggml-cpu-android_armv*.so for the device's CPU features is picked at
# runtime, so no SIGILL on cores lacking i8mm/fp16/etc.
# ---------------------------------------------------------------------------
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)   # future: Adreno OpenCL
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

add_subdirectory(third_party/llama.cpp llama_build)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/whisper.cpp)
add_library(whisper STATIC ${WHISPER_DIR}/src/whisper.cpp ${WHISPER_DIR}/src/aneforge/whisper-aneforge.cpp)
target_include_directories(whisper PUBLIC ${WHISPER_DIR}/include PRIVATE ${WHISPER_DIR}/src)
target_link_libraries(whisper PUBLIC ggml)
target_compile_definitions(whisper PRIVATE WHISPER_VERSION="1.9.5")
target_compile_options(whisper PRIVATE -O3 -w)

add_library(voiceclean_core STATIC core/whisper_core.cpp core/llama_core.cpp)
target_include_directories(voiceclean_core PUBLIC core)
target_link_libraries(voiceclean_core PUBLIC whisper llama ggml)
target_compile_options(voiceclean_core PRIVATE -O3)

add_library(voiceclean_jni SHARED voiceclean_jni.cpp)
find_library(log-lib log)
target_link_libraries(voiceclean_jni voiceclean_core ${log-lib})
