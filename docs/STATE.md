# STATE (update every session)

**Last updated:** 2026-10-26 (M6 integration)

## Environment facts
- Working dir: /home/agent/tmp/zz-null (project root = this dir; not a git repo yet)
- Android SDK: ~/android-sdk (build-tools 34/35, platform android-35, cmdline-tools). **NDK + CMake NOT yet installed.**
- JDK 17 at /usr/bin/java. No system gradle (use wrapper). 96 cores, 16GB RAM, ~6GB free disk (tight!).
- Network OK (dl.google.com, huggingface.co, github.com reachable).
- Cannot test on a device here; user installs APK and reports errors.

## Task status
- [x] Plan, decisions, handoff docs written
- [x] M0 Toolchain (NDK 27.2.12479018, CMake 3.22.1, Gradle 8.10.2 wrapper, AGP 8.7.3, Kotlin 2.0.21)
- [x] M1 Scaffold (assembleDebug verified; see docs/BUILD.md)
- [x] M2 Native (llama.cpp + whisper.cpp JNI) — compiled/linked for arm64-v8a; NOT run on a device or host (see Known issues)
- [x] M3 Model download/delete manager (compileDebugKotlin + 9 JVM tests green; not run on a device)
- [x] M4 Audio + streaming STT (JVM-tested with fake engine; not tested on device)
- [x] M5 Cleanup + UI + history (compiles; not run on device)
- [x] M6 Integration, APK, report (APK: dist/voiceclean-debug.apk; nothing run on a device)
- [ ] M7 (future) QNN / OpenCL backends

## Models API (M3) - for other modules
- `container.modelRepository.activeSttPath(): Flow<String?>` and `activeLlmPath(): Flow<String?>`: absolute path of the selected model file, null if none selected or file missing. Emits on select/download/delete. First finished download of a kind is auto-selected.
- Files: `<getExternalFilesDir>/models/{stt,llm}/<file>` (+ `.part` while downloading). Catalog in `models/ModelCatalog.kt` (sizes + SHA256 verified via HF tree API on 2026-10-26; all curated repos are ungated: Qwen official, unsloth mirrors for Gemma 3 / Llama 3.2, ggerganov/whisper.cpp).
- Settings: `SettingsRepository.hfToken` / `setHfToken` (optional HF token, sent as Bearer; OkHttp drops it on cross-host redirect to the CDN).
- `DownloadService` (foreground, dataSync) shows progress notification; started from ModelsViewModel. Manifest has FOREGROUND_SERVICE(+_DATA_SYNC) and POST_NOTIFICATIONS (requested at first download on API 33+).
- Qwen3 note for M5: disable thinking in prompt. Gemma 3 / Qwen3 GGUFs are text-only (no mmproj needed).

## Known issues
- M2 code has never executed (no device, no host compiler). First on-device run may reveal runtime issues (backend variant loading, model load, chat template for a given model). Check logcat tags `ggml`, `VoiceCleanJNI`.
- Unsupported embedded chat templates silently fall back to ChatML.
- Not tested on a device/emulator; only `assembleDebug` verified.
- Home screen uses FakeSttEngine/FakeLlmEngine (swap in `di/AppContainer.kt`). Fake LLM just capitalizes/echoes text. Each style tap writes a History row.
- M3: resume/retry/checksum only unit-tested against MockWebServer, never against a multi-GB real download or on-device. Downloads do not auto-resume after process death (user taps Resume; .part is kept and shown as partial). Custom models have size-only verification (no SHA). Notification may be hidden if POST_NOTIFICATIONS is denied (service still runs).
- Gradle wrapper download can time out on first run; re-run.
- Debug APK is ~60MB (Compose + c++_shared); expected to grow with llama/whisper.
- Dynamic color theme requires minSdk 31 (ok).

## M2 Native notes (S3)
- Pinned: llama.cpp **b11899** (c6fb3b5), whisper.cpp **v1.9.5** (d1be6fd). Vendored (no .git, pruned) in `app/src/main/cpp/third_party/`. whisper.cpp's own ggml dir deleted; `whisper` target is compiled from `src/whisper.cpp` (+aneforge stub, env-gated) against llama.cpp's ggml (D11). Compiles and links cleanly against the newer ggml; all `ggml_*` undefined symbols in libvoiceclean_jni/libllama resolve to libggml/libggml-base.
- Build: shared libs, `GGML_BACKEND_DL=ON` + `GGML_CPU_ALL_VARIANTS=ON` (Android variants armv8.0_1, 8.2_1 dotprod, 8.2_2 +fp16, 8.6_1 +i8mm, 9.0_1, 9.2_1, 9.2_2) -> ggml picks the best CPU variant at runtime (no SIGILL on 7-series). `GGML_NATIVE=OFF`, OpenMP off, KleidiAI off, OpenCL/Vulkan off (future: Adreno OpenCL). Release (-O3) forced in CMake even for debug APK.
- Packaging: `jniLibs.useLegacyPackaging = true` (app/build.gradle.kts) because ggml dlopens `libggml-cpu-*.so` from `applicationInfo.nativeLibraryDir`. Kotlin engines call `NativeBridge.initBackends(nativeLibraryDir)` before loading models. APK now ~65MB.
- Layout: `cpp/core/` JNI-free (`whisper_core`, `llama_core`), `cpp/voiceclean_jni.cpp` JNI glue (real UTF-8<->UTF-16 conversion, no exceptions cross JNI, mutex per context, cancel flag). `NativeBridge` exposes raw externals + `TokenCallback`.
- Kotlin: `stt/WhisperEngine` (+`FallbackSttEngine`), `llm/LlamaCppEngine` (callbackFlow, cancel stops native), `llm/ExecuTorchQnnEngine` (throws UnsupportedOperationException) + `FallbackLlmEngine` (both in llm/ExecuTorchQnnEngine.kt).
- **Interface change:** `GenParams` gained `systemPrompt: String? = null, topP, topK, minP, repeatPenalty` (all defaulted; source compatible). `generate(prompt, params)`: prompt = user message, `systemPrompt` = system message, wrapped with the model's embedded chat template (falls back to ChatML if unsupported). For Qwen3 add `/no_think` in the system prompt (PromptBuilder).
- `AppContainer`: `sttEngine` = FallbackSttEngine(WhisperEngine, Fake); `createLlmEngine(backendId)` ("llama.cpp" | "executorch-qnn") wraps real engine in FallbackLlmEngine(…, Fake); `llmEngine` = createLlmEngine("llama.cpp"). **Callers must `load(path, cfg)` the active model** (until a load succeeds the Fake is used; `lastError` has the cause). WhisperEngine has mutable `threads`/`beam` (1=greedy, max 5).
- `tools/hosttest/` has a host test harness (CMake + main.cpp) but it is UNBUILT: this box has no host C/C++ compiler/libc headers or qemu, so no model inference was run anywhere.

## Next steps
Phase B (parallel, disjoint dirs):
- M2 Native: vendor llama.cpp+whisper.cpp under app/src/main/cpp/third_party, extend cpp/CMakeLists.txt (marked comment), JNI in cpp/ + Kotlin wrappers in stt/ and llm/ implementing SttEngine/LlmEngine; existing JNI stub class is `com.voiceclean.app.NativeBridge` (loads libvoiceclean_jni).
- M3 Models: models/ package, ModelsScreen (placeholder in ui/), settings keys activeSttModel/activeLlmModel already in SettingsRepository. OkHttp client in AppContainer.
- M4/M5: audio/ (empty), HomeScreen is a placeholder wired to fake engines; add PromptBuilder/CleanupService in llm/.
Then Phase C integration (M6): replace fakes in AppContainer.

## Session log
- S3 (Models agent): M3 done. models/{ModelCatalog,ResumableDownloader,DownloadManager,DownloadService,ModelRepository,CustomModelSpec}.kt, ui/ModelsScreen+ModelsViewModel, tests in app/src/test/.../models. Added mockwebserver testImplementation.
- S1: Wrote CLAUDE.md, docs/PLAN.md, DECISIONS.md, STATE.md.
- S2: Phase A. Installed NDK/CMake, created wrapper, scaffold (ui/nav/theme, Room, DataStore, AppContainer, engine interfaces + fakes, native stub). assembleDebug OK.

## M4/M5 (Audio/Cleanup/UI agent) - behaviors and tunables
- `audio/AudioRecorder` (AudioSource): VOICE_RECOGNITION, 16k mono PCM16 -> FloatArray 100ms chunks; cold Flow, releases on cancel; throws MicPermissionException. `audio/Vad.kt`: `Vad` interface + `EnergyVad` (adaptive floor, min thr 0.012, ratio 3x, hangover 300ms).
- `stt/StreamingTranscriber` (Config): decodeIntervalSec=1, maxWindowSec=25, finalizeSilenceSec=0.8, autoStopSilenceSec=8, minSpeechSec=0.3, prerollSec=0.5, promptChars=224. Time is measured in audio samples. One decode at a time; partials are skipped while busy, finals are queued. stop() flushes a final decode. `HallucinationFilter` drops [BLANK_AUDIO]/(music) tags, phantom phrases ("Thank you.") only when VAD speech in window < 1.6s, and collapses 3+ repeated n-grams.
- `llm/PromptBuilder`: Style {Original, Clean, Formal, Casual, Shorter, Longer}; ModelFamily {PLAIN, CHATML, QWEN3, GEMMA, LLAMA3} detected from model file name; `request()` returns (user message, GenParams with systemPrompt); the NATIVE engine applies the GGUF chat template once (D21; supersedes D13). Qwen3 gets `/no_think` appended to the user message.
- `llm/CleanupService`: always rewrites the RAW text; strips think blocks/special tokens/fences/"Here is...:" lines/wrapping quotes; falls back to raw on empty or absurd length ratio (bounds per style).
- (done in M6, see below) `di/EngineProvider`: Native/Models agents should implement it (availability from active models, lazy `ensureSttLoaded/ensureLlmLoaded`, `llmModelName()` for template family) and assign it in AppContainer. Home shows "Download a model" banner when `sttAvailable/llmAvailable` is false.
- Home (`ui/HomeScreen`, `HomeViewModel`): mic with pulsing level ring, live transcript (committed normal, partial dimmed), editable result, chips, Copy+snackbar, New/Clear, auto-cleanup (Clean) after stop if setting on. History row is created on stop and updated on style change/edit (debounced 800ms).
- History: Room v2 (`durationMs`, MIGRATION_1_2), search, expand, copy, delete, delete-all (confirm); tap card reopens in Home via `openRequest` flow in Nav.
- Tests: `app/src/test` (PromptBuilder, CleanupService, HallucinationFilter, EnergyVad, StreamingTranscriber).

## M4/M5 known issues / next steps
- Untested on device: mic capture, VAD thresholds (tune `EnergyVad.minThreshold` if too sensitive/insensitive), transcribe cadence with the real (slow) Whisper models (partials are skipped while busy, so large models just update less often).
- transcribe() is blocking and runs on Dispatchers.Default; cancelling a running decode is not possible until stop returns.
- Integration (M6): implement EngineProvider; verify native llm engine accepts pre-templated prompts; History navigation reopen uses a shared flow (not a nav arg).


## M6 Integration (this session)
### Changes
- Chat template applied once, natively (D21). Verified in vendored llama-chat.cpp: legacy templates (chatml/gemma/llama3) emit no BOS; `llama_tokenize(add_special=true, parse_special=true)` adds BOS only when the vocab says so (Llama3/Gemma yes, Qwen no) => no duplicate BOS. Gemma template folds the system message into the first user turn. Qwen3: `/no_think` in user message (skipped for "Instruct-2507"), `<think>` stripped by CleanupService regardless.
- `RealEngineProvider` (di/EngineProvider.kt, D22): lazy load on `activeSttPath/activeLlmPath`, single load mutex, IO dispatcher, stale model unloaded on selection change/delete, LLM reloaded on threads/ctx change, load failure => `EngineStatus.Error` + exception with message (no silent fake), `warmUpStt()` at Home init, `warmUpLlm()` after STT is ready when dictation starts, STT released after 5 min idle, <7.5GB RAM devices hold one model at a time. Home shows load progress/error line (`engineMsg`) and "Download a model" only when no active model file.
- Engines: ReentrantReadWriteLock so `unload()` cannot free native contexts under a running decode/generate; stale-cancel race in LlamaCppEngine.generate fixed.
- Default threads = performance cores (`diag/CpuInfo`, clamp 2..5); Settings threads/ctx feed WhisperEngine.threads and LlmConfig.
- Lifecycle: recording stops on ON_STOP (`HomeViewModel.onAppBackgrounded`); no foreground mic service (D23). ViewModel survives rotation; AudioRecord released in flow `finally`; mic denial handled (permission launcher + MicPermissionException).
- Manifest: largeHeap removed; merged manifest has `extractNativeLibs="true"` (from useLegacyPackaging); all 7 ggml CPU variants + libllama/libggml/libvoiceclean_jni in APK (whisper is linked into libvoiceclean_jni).
- Diagnostics (D24): `diag/DebugLog` (ring + `filesDir/debug.log`, uncaught exceptions), native ggml/llama/whisper log ring buffer (`NativeBridge.nativeLogText()`), `NativeBridge.systemInfo()` (ggml CPU features of the chosen variant), `diag/Diagnostics` (device/SoC/ABI/RAM, engine states, full report, self-test), Settings > Diagnostics UI (Copy debug log, Self-test).
- DECISIONS renumbered: D12-D15 (UI/audio), D16-D20 (Native/Models), D21-D25 new. D13 marked superseded.
- Build: `flock /tmp/voiceclean-gradle.lock ./gradlew testDebugUnitTest assembleDebug` green (all JVM tests pass). APK copied to dist/voiceclean-debug.apk; dist/INSTALL.md written.

### Verified (by build/tests/code reading only)
- Compiles, links, JVM unit tests pass; JNI symbols present; APK contents/manifest as above.
- End-to-end call chain reviewed: mic (AudioRecord on IO) -> chunks -> EnergyVad -> StreamingTranscriber (Default dispatcher, one decode at a time) -> FallbackSttEngine/WhisperEngine -> JNI whisperTranscribe -> partial/committed text -> stop() flush -> history row -> auto Clean -> CleanupService -> LlamaCppEngine (callbackFlow, native on Default, cancel on collector cancel) -> streamed sanitized text -> persist -> Copy. No JNI call on the main thread (loads on IO, decode/generate on Default).

### NOT verified (never run on a device)
- Any actual model load/inference, backend variant selection, whisper flash_attn on CPU, real tokens/s, memory headroom, chat-template detection of the real GGUFs (falls back to ChatML silently if unrecognised), VAD thresholds on a real mic, 8s auto-stop, 5 min download resume against HF, notification permission flow, rotation/backgrounding behaviour.

### Known risks, ranked
1. Native runtime failure on first load (ggml backend dlopen from nativeLibraryDir, SIGSEGV/SIGILL in a variant, whisper flash_attn). Mitigation: Self-test + debug.log breadcrumbs + logcat command in dist/INSTALL.md. If whisper crashes try `cp.flash_attn=false` in core/whisper_core.cpp.
2. Performance: whisper medium/large F16 with 1 s re-decode cadence is too slow on CPU (partials just update rarely; final decode on stop can take several seconds). small.en / large-v3-turbo-q5_0 expected usable.
3. Memory: 4B Q6 (4.1GB) + medium STT on 8GB phones; provider only evicts automatically below 7.5GB RAM. LMK may kill the app in the background.
4. LLM output quality/format: small models may add chatter; CleanupService guards fall back to raw text (user sees a notice).
5. Chat template legacy detection: newer GGUF templates unknown to llama_chat_apply_template fall back to ChatML (wrong for non-ChatML models; curated models are fine).
6. EnergyVad false triggers/misses in noise; Whisper hallucinations on silence (filtered heuristically).
7. Backgrounding mid-LLM generation: no foreground service; process may be frozen/killed.

### First on-device test checklist
1. Install, open, no crash. Settings > Diagnostics shows SoC/RAM/ABI; note "ggml CPU features" is empty until a model loads.
2. Models: download small.en + Qwen3 4B Q5_K_M (check progress, kill/reopen to resume, SHA verification passes).
3. Diagnostics > Self-test: STT load/transcribe times, LLM load time, first-token latency and tok/s (expect ~5-12 tok/s for 4B Q5 on SD 8 Gen 2/3). Paste report if anything fails.
4. Diagnostics shows CPU features with DOTPROD/MATMUL_INT8 (i8mm) on 8-series; if only base NEON, the variant selection is wrong.
5. Home: mic permission prompt, speak 10 s, live text appears about every 1-2 s, stop, cleaned text streams in, History row exists, Copy works.
6. Chips Formal/Casual/Shorter/Longer; Cancel during rewrite; switch active LLM in Models while idle then dictate (reload).
7. Rotate during recording, press Home during recording (should stop and keep text), deny mic permission, delete the active model (Home shows Download banner).
8. Try Gemma 3 4B to check chat template handling (no system role) and Llama 3.2.

### Next steps (M7 ideas)
- Adreno OpenCL backend for llama.cpp (GGML_OPENCL, Q4_0/Q8_0 kernels) and optional Hexagon/QNN via ExecuTorch (D3).
- Silero VAD (replace EnergyVad), true streaming decode with state reuse, whisper `no_context` tuning.
- Benchmark screen (per-model tok/s, RTF) built on Diagnostics self-test; KV/prompt cache of the system prompt; speculative decoding.
- Foreground microphone service if background dictation is wanted (needs FOREGROUND_SERVICE_MICROPHONE).
- Release signing, R8, instrumented tests; RealEngineProvider unit tests with a fake ModelRepository.
