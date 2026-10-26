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
- [ ] M7 (future) QNN / OpenCL backends. Whisper-on-NPU research done: see docs/WHISPER_QNN.md (proposal D37: ggml-hexagon route first; not implemented)

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

## On-device report #1 (2026-10-26, user's Galaxy SM-S948B, SoC SM8850, Android 17/API 37, 11GB RAM)
Source: Diagnostics "Copy debug log" after Self-test. **Native engines work on real hardware** (no crash, CPU variant picked: NEON, FMA, FP16, MATMUL_INT8, SVE(16), DOTPROD, REPACK).
- LLM Qwen3-4B Q5_K_M: load 5.1s, first token 1.3s, ~12.3 tok/s, threads=5, ctx=4096 (KV f16 576MB, compute buf 326MB, REPACK 2.7GB).
- STT whisper small.en: load 0.4s (mmap, lazy), **transcribe 1s of silence = 7.4s (RTF 7.4)** -> far too slow for 1s-cadence sliding window. First call, cold; flash_attn=1 on CPU; no audio_ctx reduction. Output 'you' on silence (hallucination; HallucinationFilter should catch).
- Self-test LLM output shows raw '<think>\n\n</think>\n\nOK' (CleanupService strips it; self-test display doesn't).
- Native log ring buffer is flooded by ~100 identical 'CPU_Mapped model buffer size = 0.01 MiB' lines.
- NOT yet tested: real dictation, live streaming quality, cleanup quality/latency on a paragraph, UI look.
### Planned follow-ups (priority order)
1. STT speed: warm-up decode at load; self-test runs 2x (cold+warm) and tries flash_attn on/off; whisper `audio_ctx` shrink for short windows; two-tier streaming (fast partials w/ small model or reduced ctx, accurate final re-decode on utterance end); adapt cadence to measured decode time.
2. Diagnostics log: collapse repeated native lines, keep larger tail, strip <think> in self-test display, include per-decode timings.
3. LLM: benchmark screen (threads 4/5/6/8, prefill tok/s on ~200-token input), KV cache q8_0 or ctx 2048 for dictation, SM8850 Hexagon/Adreno-OpenCL llama.cpp backends (M7).
4. UI polish after user feedback.


## Home edit-in-place dictation (HOME-UI agent, 2026-10-26)
- New `ui/DictationInsert.kt` (pure insert/replace logic, tests in `ui/DictationInsertTest.kt`); `HomeViewModel` holds `box: TextFieldValue`; `HomeScreen` has a single BasicTextField (provisional text dimmed/italic via VisualTransformation, auto-scroll to caret while recording/rewriting). See D30-D32. Supersedes the old "live transcript vs result" split, `raw/result/committed/partial/starting` state, the clear-confirm dialog (now Undo snackbar) and the "always rewrite RAW" rule (D14).
- Behavior: mic start is immediate (audio buffered in a channel while STT loads; "Loading speech model..." shown); STT text is re-rendered whole each update; stop finalizes, saves a History row, optional auto-Clean of the inserted range only. LLM is warmed during dictation only when auto-cleanup is on. Copy copies selection or whole text.
- Note: `Settings.autoCleanup` still defaults to true (SettingsRepository not owned by this agent); flip the default if the user wants it off by default.
- NOT verified on device. Verified: unit tests + compileDebugKotlin (see session result).

## STT speed work (STT-SPEED agent, 2026-10-26) - reaction to on-device report #1
Changed (see D33-D35): `core/whisper_core.*` + JNI (`whisperLoad(path, flashAttn)`, `whisperTranscribe(..., audioCtx, partial, prompt)`, `whisperTimings`), `stt/WhisperEngine` (warm-up, audio_ctx scaling, flash benchmark, per-decode log line `decode partial|final window=..s audio_ctx=.. beam=.. ms=.. (encode=.. decode=..)`), `stt/StreamingTranscriber` (two-tier, gate, adaptive cadence, `modelLoading`, `timeToFirstTextMs` + log line `time-to-first-text`), `SttEngine` (`DecodeOpts`, `awaitReady`), `HallucinationFilter` (more phantoms, speech-ratio gate), `EngineProvider` (flash mode key, `reloadStt`, `warmUpLlm` skipped when auto-cleanup off), `VoiceCleanApp` (early STT load), Diagnostics self-test, DebugLog (2500 lines), native log ring.
Tunables: `WhisperEngine.minAudioCtx=500, ctxMarginFrames=50, finalFullContext=true, threads, beam`; `StreamingTranscriber.Config(decodeIntervalSec=1, cadenceGapFactor=0.5, finalBeam=3, fastRtf=0.5, finalTrailingSilenceSec=0.3, finalizeSilenceSec=0.8)`; setting `Settings.whisperFlashAttn` auto|on|off.
Verified: JVM tests (my 18 stt tests incl. two-tier replace, gating, adaptive cadence, beam-when-fast, buffering while loading, TTFT) green; assembleDebug green; JNI symbols present. NOT verified: any on-device speed (no device). Whether partial audio_ctx>=500 hurts accuracy, and which flash_attn mode wins, are unknown until a report.
Note: full test run currently fails only in the Home agent's `DictationInsertTest.replacementDoesNotRestoreSelectionButBlankRemoves` (not mine).
Look for in next report (Copy debug log): `STT: whisper ready ... warmup=..ms`, `STT: decode partial/final ... ms=` lines (target partial < ~1 s for base.en/small.en), `Stream: time-to-first-text`, self-test partial #1 vs #2 vs final RTF, flash_attn benchmark rows. If partials are still > cadence, lower `minAudioCtx`, use base.en, or fewer threads; if finals are slow, set `finalFullContext=false`.

## ExecuTorch + QNN backend (QNN agent, 2026-10-26)
- [x] `llm/ExecuTorchQnnEngine.kt` (LlmModule type 4, streaming Flow, cancel via stop(), `companion availability(context, modelPath?)`), `llm/QnnModelInfo.kt` (+ JVM tests), `llm/FallbackLlmEngine.kt` split out of the old stub file.
- [x] Deps in app/build.gradle.kts, manifest `uses-native-library libcdsprpc.so`, AppContainer passes `(appContext, httpClient)`, 3 catalog entries (Qwen3 1.7B QNN SM8650/8750/8850), ModelsTest accepts `.pte`. testDebugUnitTest + assembleDebug green; APK 134 MB (+68 MB).
- [ ] NOT run on any device. Open risks (details docs/QNN.md, D36): no .pte README (QNN SDK version unknown), runtime 2.39.3 vs ET 1.5.1 built on 2.37, JNI hardcodes `llama3` decoder for Qwen3, SM8750 and SM8850 .pte are identical files.
- Hookups still needed (files owned by others): Settings backend picker should call `ExecuTorchQnnEngine.availability(ctx)` to show/disable "executorch-qnn" and display the reason; Models screen should hide `.pte` entries whose SM tag != device SoC and the custom-model dialog is GGUF-only; selecting a `.pte` with backend llama.cpp (or `.gguf` with qnn) currently just falls back to the fake engine with lastError set; pick backend by file extension in `createLlmEngine` callers.

## Session: cleanup optional / device profile / QNN hookups
- autoCleanup default false; Home banner only requires STT; rewrite chips disabled without LLM (already gated by llmAvailable).
- diag/DeviceProfile (SoC tier -> recommended Whisper models) shown in Settings; added small/medium.en Q8_0 to catalog.
- LLM backend now chosen by file type via llm/RoutingLlmEngine (.pte -> QNN, else llama.cpp); backend chip removed from Settings; Models screen hides .pte for other SoCs. See docs/QNN.md. APK 134MB. Nothing run on device.
