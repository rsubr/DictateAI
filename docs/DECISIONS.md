# Decisions (settled by user unless marked otherwise)

| # | Decision | Why |
|---|----------|-----|
| D1 | Android app, Kotlin + Jetpack Compose, C++ via NDK/JNI, arm64-v8a only | Galaxy S / Snapdragon 8 & 7 |
| D2 | LLM engine: **llama.cpp** (GGUF), CPU (i8mm/dotprod) first; optional Adreno OpenCL later | Max model choice from HuggingFace, accuracy via Q5/Q6/Q8 |
| D3 | LLM engine must sit behind an `LlmEngine` Kotlin interface so **ExecuTorch + Qualcomm QNN** can be added later as a user-selectable backend (settings option) | User request |
| D4 | STT: **whisper.cpp**, user picks model (small.en, medium.en, large-v3-turbo, ...) | Best English accuracy; shares ggml with llama.cpp |
| D5 | Streaming STT = sliding-window re-decode of recent audio (~1-2s cadence) with VAD; partial text shown live, finalized on silence/stop | Whisper isn't natively streaming |
| D6 | Weights NOT bundled. Models downloaded from HuggingFace in-app (resume, progress, SHA/size check). User can **delete** any downloaded model | User request |
| D7 | Accuracy over speed: default to larger quants (Q5_K_M/Q6_K) and larger models | User request |
| D8 | Build locally here with Android SDK at ~/android-sdk; deliver a debug-signed installable APK; report errors | User request |
| D9 | History of all transcriptions persisted (Room/SQLite): raw text, cleaned text, style used, timestamp | User request |
| D10 | Package: `com.voiceclean.app` (changeable) | assumption |
| D11 | Both ggml libs (llama + whisper) must share ONE ggml build to avoid symbol clashes | technical |
| D12 | Streaming STT time base is audio samples (not wall clock); partial decodes are dropped while the engine is busy, finals are queued | determinism, testability |
| D13 | PromptBuilder renders the full chat-templated prompt (per model family, detected from file name); LlmEngine.generate receives it verbatim, so native engines must not apply the template again | LlmEngine takes a single String; keeps per-family quirks (Qwen3 /no_think) in Kotlin |
| D14 | Styles always rewrite the RAW dictation; history row stores raw + current result + style + durationMs (Room v2) | avoid stacked drift |
| D15 | `EngineProvider` seam in di/ for engine loading and model availability; default Noop keeps fakes working | decouple parallel agents |

| D12 | CPU backend built as runtime-dispatched variants (GGML_BACKEND_DL + GGML_CPU_ALL_VARIANTS), libs extracted (useLegacyPackaging) | Avoid SIGILL on non-i8mm SoCs while using dotprod/i8mm where present |
| D13 | whisper.cpp compiled directly against llama.cpp's ggml (whisper's ggml copy removed) | Implements D11; verified symbol resolution |
| D14 | GenParams extended with systemPrompt + sampler params; chat template applied natively | Needed for system+user messages |
