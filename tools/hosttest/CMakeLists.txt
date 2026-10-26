# Host (x86_64 Linux) sanity-test harness for the JNI-free core. NOT part of the app build.
#   cmake -S tools/hosttest -B /tmp/vc-hosttest -DCMAKE_BUILD_TYPE=Release && cmake --build /tmp/vc-hosttest -j16
#   /tmp/vc-hosttest/hosttest whisper <model.bin> <16k-mono.wav>
#   /tmp/vc-hosttest/hosttest llama <model.gguf>
cmake_minimum_required(VERSION 3.22)
project(vc_hosttest LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CPP ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE ON CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
foreach(o LLAMA_BUILD_COMMON LLAMA_BUILD_TESTS LLAMA_BUILD_TOOLS LLAMA_BUILD_EXAMPLES LLAMA_BUILD_SERVER LLAMA_BUILD_APP LLAMA_BUILD_IS_DEV LLAMA_OPENSSL)
  set(${o} OFF CACHE BOOL "" FORCE)
endforeach()
add_subdirectory(${CPP}/third_party/llama.cpp llama_build)
set(W ${CPP}/third_party/whisper.cpp)
add_library(whisper STATIC ${W}/src/whisper.cpp ${W}/src/aneforge/whisper-aneforge.cpp)
target_include_directories(whisper PUBLIC ${W}/include PRIVATE ${W}/src)
target_link_libraries(whisper PUBLIC ggml)
target_compile_definitions(whisper PRIVATE WHISPER_VERSION="1.9.5")
add_executable(hosttest main.cpp ${CPP}/core/whisper_core.cpp ${CPP}/core/llama_core.cpp)
target_include_directories(hosttest PRIVATE ${CPP}/core)
target_link_libraries(hosttest whisper llama ggml)
