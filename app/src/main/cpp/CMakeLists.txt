cmake_minimum_required(VERSION 3.22.1)
project(voiceclean_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# PHASE B (M2) TODO: vendor llama.cpp and whisper.cpp here.
#   - Put sources in app/src/main/cpp/third_party/llama.cpp and
#     app/src/main/cpp/third_party/whisper.cpp (submodules or tarballs).
#   - Build ggml ONCE (decision D11) and link both libllama and libwhisper
#     against that single ggml, e.g. add_subdirectory(third_party/llama.cpp)
#     and point whisper at the same ggml target (WHISPER_USE_SYSTEM_GGML or
#     a shared ggml dir) so symbols do not clash.
#   - CPU flags: -march=armv8.2-a+dotprod+fp16 (+i8mm if checked at runtime).
#   - Then add target_link_libraries(voiceclean_jni llama whisper ...) below.
# ---------------------------------------------------------------------------

add_library(voiceclean_jni SHARED voiceclean_jni.cpp)

find_library(log-lib log)
target_link_libraries(voiceclean_jni ${log-lib})
