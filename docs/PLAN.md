# VoiceClean — Plan

## Product
- Big mic button bottom-center. Tap = start continuous live transcription; tap again (or auto-stop on long silence) = stop.
- On stop: LLM cleanup pass (spelling, punctuation, grammar, formal style).
- Style chips next to mic: Formal, Casual, Shorter, Longer (+ Clean/Original). Tapping re-runs the LLM on the current text (always from the cleaned/original source, not stacked drift — keep raw text as the base).
- Copy button (clipboard). Share optional.
- History screen: all transcriptions (raw + cleaned + style), search, delete, copy.
- Models screen: curated list of Whisper + LLM models; download from HuggingFace w/ progress/pause/resume; select active; **delete**; show size/RAM hint. Custom HF GGUF URL field.
- Settings: LLM backend (llama.cpp [default]; ExecuTorch-QNN [disabled/"coming soon" stub]), threads, context size, auto-cleanup on/off.

## Architecture (single Gradle module `app`, packages)
```
com.voiceclean.app
  ui/        Compose screens: HomeScreen, HistoryScreen, ModelsScreen, SettingsScreen, theme, nav
  audio/     AudioRecorder (AudioRecord 16kHz mono PCM16 -> float), VAD
  stt/       SttEngine interface, WhisperEngine (JNI), StreamingTranscriber (sliding window)
  llm/       LlmEngine interface, LlamaCppEngine (JNI), ExecuTorchQnnEngine (stub), PromptBuilder, CleanupService
  models/    ModelCatalog (curated list), ModelRepository, DownloadManager (OkHttp, resume), storage/delete
  data/      Room: TranscriptionEntity, Dao, HistoryRepository; DataStore settings
  di/        simple manual DI (AppContainer) — no Hilt needed
app/src/main/cpp/   CMakeLists.txt, jni glue, third_party/llama.cpp, third_party/whisper.cpp (git submodules or vendored tarballs)
```
Engine interfaces:
```kotlin
interface LlmEngine { suspend fun load(path:String, cfg:LlmConfig); fun generate(prompt:String, params:GenParams): Flow<String>; fun unload(); val id:String }
interface SttEngine { suspend fun load(path:String); fun transcribe(pcm:FloatArray, prompt:String?): String; fun unload() }
```
Native: one CMake project builds ggml once, links `libllama` + `libwhisper` into `libvoiceclean_jni.so` (D11). Flags: `-march=armv8.2-a+dotprod+i8mm+fp16` (runtime-safe variant: build with GGML_CPU_ARM_ARCH / or multiple CPU variants via GGML_CPU_ALL_VARIANTS + dynamic loading if feasible; at minimum armv8.2-a+dotprod+fp16 for S-series and 7-series; check i8mm at runtime if possible).

## Curated models (verify HF repo/file names exist before hardcoding — use HF API)
- STT (ggerganov/whisper.cpp): ggml-small.en.bin, ggml-medium.en.bin, ggml-large-v3-turbo(-q5_0).bin, ggml-base.en.bin (fast option)
- LLM GGUF (bartowski / unsloth / official): Qwen3-4B (Q5_K_M/Q6_K), Gemma-3-4B-it (Q5/Q6), Llama-3.2-3B-Instruct (Q6_K), Qwen3-1.7B (Q8_0, "faster"), Phi-4-mini (optional)
- Qwen3: disable thinking (`/no_think` or chat template flag) for cleanup.

## Prompting
System prompt per style; strict "output only the rewritten text, no commentary". Temperature low (0.1–0.3). Use model's chat template via llama_chat_apply_template. Guard: if output length ratio is absurd, fall back to raw text.

## Milestones / tasks (check off in STATE.md)
- M0 Toolchain: install NDK + CMake in ~/android-sdk, Gradle wrapper, verify empty Compose app builds to APK. (~6GB free disk — watch it; clean caches)
- M1 Scaffold: project skeleton, packages, nav, theme, Room, DataStore, AppContainer, interfaces + fake engines so UI runs without native libs.
- M2 Native: vendor llama.cpp + whisper.cpp (shared ggml), CMake, JNI for both, Kotlin wrappers implementing interfaces. Must compile for arm64-v8a.
- M3 Models: catalog, DownloadManager (resume, progress, notification-free foreground-in-app), storage, delete, select active, custom URL.
- M4 Audio+STT streaming: AudioRecorder, VAD, StreamingTranscriber, permission flow, live partial text on Home.
- M5 Cleanup+UI: PromptBuilder, CleanupService, style chips, copy, history persistence + History screen, polish.
- M6 Integration & release: end-to-end wiring, debug APK, install instructions, error report; lint/unit tests where cheap.
- M7 (future) ExecuTorch-QNN backend, Adreno OpenCL/Hexagon for llama.cpp, benchmark screen.

## Parallelization (sub-agents)
Phase A (serial): M0+M1 (one agent). Phase B (parallel, disjoint dirs): Native(M2) | Models(M3) | Audio+UI pieces(M4/M5 UI). Phase C: integration (M6).
