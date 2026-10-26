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
| D13 | ~~SUPERSEDED by D21~~ PromptBuilder renders the full chat-templated prompt (per model family, detected from file name); LlmEngine.generate receives it verbatim, so native engines must not apply the template again | LlmEngine takes a single String; keeps per-family quirks (Qwen3 /no_think) in Kotlin |
| D14 | Styles always rewrite the RAW dictation; history row stores raw + current result + style + durationMs (Room v2) | avoid stacked drift |
| D15 | `EngineProvider` seam in di/ for engine loading and model availability; default Noop keeps fakes working | decouple parallel agents |
| D16 | CPU backend built as runtime-dispatched variants (GGML_BACKEND_DL + GGML_CPU_ALL_VARIANTS), libs extracted (useLegacyPackaging) | Avoid SIGILL on non-i8mm SoCs while using dotprod/i8mm where present |
| D17 | whisper.cpp compiled directly against llama.cpp's ggml (whisper's ggml copy removed) | Implements D11; verified symbol resolution |
| D18 | GenParams extended with systemPrompt + sampler params; chat template applied natively | Needed for system+user messages |
| D19 | Curated models use ungated mirrors (Qwen official, unsloth for Gemma 3/Llama 3.2) with hardcoded size + SHA256 (from HF LFS oid); optional user HF token in settings for gated/custom repos | Avoid login friction; integrity check |
| D20 | Downloads: app-scoped DownloadManager (OkHttp, Range resume to .part, atomic rename) + foreground dataSync service for notification/process keep-alive; models stored in app external files dir (fallback internal) | Multi-GB files must survive screen changes/backgrounding |
| D21 | Chat template is applied exactly once, natively: PromptBuilder returns (user message, GenParams.systemPrompt); llama_core applies the GGUF's embedded template (ChatML fallback; Gemma folds system into the user turn). Legacy llama_chat_apply_template emits no BOS; llama_tokenize(add_special=true) adds it only if the vocab wants it, so no duplicate BOS. Only Qwen3 quirk in Kotlin: `/no_think` appended to the user message; CleanupService strips any `<think>` blocks anyway | Correct per model family, no double templating (supersedes D13) |
| D22 | `RealEngineProvider` owns engine lifecycle: lazy load on active model paths, one load mutex on Dispatchers.IO, stale model unloaded on selection change/delete, LLM reloaded when threads/ctx change, failed native load = visible error (never silently the fake). < 7.5 GB RAM devices keep only one model resident; STT released after 5 min idle | OOM safety, no main-thread loads |
| D23 | Recording stops on ON_STOP (no foreground mic service): Android 14+ blocks background mic anyway | Simplicity, privacy |
| D24 | Diagnostics: in-app debug log (ring + filesDir/debug.log, uncaught exceptions), native ggml log ring buffer exposed to Kotlin, Settings > Diagnostics with "Copy debug log" and Self-test | Cannot test on device here; user reports back |
| D25 | Default thread count = performance cores (cores >= 70% of max freq, clamped 2..5) | Little cores slow ggml |
