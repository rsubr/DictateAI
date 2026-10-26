# STATE (update every session)

**Last updated:** 2026-10-26 (session 2, Phase A: M0+M1)

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
- [ ] M2 Native (llama.cpp + whisper.cpp JNI)
- [ ] M3 Model download/delete manager
- [x] M4 Audio + streaming STT (JVM-tested with fake engine; not tested on device)
- [x] M5 Cleanup + UI + history (compiles; not run on device)
- [ ] M6 Integration, APK, report
- [ ] M7 (future) QNN / OpenCL backends

## Known issues
- Not tested on a device/emulator; only `assembleDebug` verified.
- Home screen uses FakeSttEngine/FakeLlmEngine (swap in `di/AppContainer.kt`). Fake LLM just capitalizes/echoes text. Each style tap writes a History row.
- Gradle wrapper download can time out on first run; re-run.
- Debug APK is ~60MB (Compose + c++_shared); expected to grow with llama/whisper.
- Dynamic color theme requires minSdk 31 (ok).

## Next steps
Phase B (parallel, disjoint dirs):
- M2 Native: vendor llama.cpp+whisper.cpp under app/src/main/cpp/third_party, extend cpp/CMakeLists.txt (marked comment), JNI in cpp/ + Kotlin wrappers in stt/ and llm/ implementing SttEngine/LlmEngine; existing JNI stub class is `com.voiceclean.app.NativeBridge` (loads libvoiceclean_jni).
- M3 Models: models/ package, ModelsScreen (placeholder in ui/), settings keys activeSttModel/activeLlmModel already in SettingsRepository. OkHttp client in AppContainer.
- M4/M5: audio/ (empty), HomeScreen is a placeholder wired to fake engines; add PromptBuilder/CleanupService in llm/.
Then Phase C integration (M6): replace fakes in AppContainer.

## Session log
- S1: Wrote CLAUDE.md, docs/PLAN.md, DECISIONS.md, STATE.md.
- S2: Phase A. Installed NDK/CMake, created wrapper, scaffold (ui/nav/theme, Room, DataStore, AppContainer, engine interfaces + fakes, native stub). assembleDebug OK.

## M4/M5 (Audio/Cleanup/UI agent) - behaviors and tunables
- `audio/AudioRecorder` (AudioSource): VOICE_RECOGNITION, 16k mono PCM16 -> FloatArray 100ms chunks; cold Flow, releases on cancel; throws MicPermissionException. `audio/Vad.kt`: `Vad` interface + `EnergyVad` (adaptive floor, min thr 0.012, ratio 3x, hangover 300ms).
- `stt/StreamingTranscriber` (Config): decodeIntervalSec=1, maxWindowSec=25, finalizeSilenceSec=0.8, autoStopSilenceSec=8, minSpeechSec=0.3, prerollSec=0.5, promptChars=224. Time is measured in audio samples. One decode at a time; partials are skipped while busy, finals are queued. stop() flushes a final decode. `HallucinationFilter` drops [BLANK_AUDIO]/(music) tags, phantom phrases ("Thank you.") only when VAD speech in window < 1.6s, and collapses 3+ repeated n-grams.
- `llm/PromptBuilder`: Style {Original, Clean, Formal, Casual, Shorter, Longer}; ModelFamily {PLAIN, CHATML, QWEN3, GEMMA, LLAMA3} detected from model file name; `render()` returns a FULLY TEMPLATED prompt string (Qwen3 gets `/no_think` + empty think block). Engines must NOT re-apply a chat template (see D13).
- `llm/CleanupService`: always rewrites the RAW text; strips think blocks/special tokens/fences/"Here is...:" lines/wrapping quotes; falls back to raw on empty or absurd length ratio (bounds per style).
- `di/EngineProvider` (seam, `AppContainer.engineProvider`, default Noop => fakes work): Native/Models agents should implement it (availability from active models, lazy `ensureSttLoaded/ensureLlmLoaded`, `llmModelName()` for template family) and assign it in AppContainer. Home shows "Download a model" banner when `sttAvailable/llmAvailable` is false.
- Home (`ui/HomeScreen`, `HomeViewModel`): mic with pulsing level ring, live transcript (committed normal, partial dimmed), editable result, chips, Copy+snackbar, New/Clear, auto-cleanup (Clean) after stop if setting on. History row is created on stop and updated on style change/edit (debounced 800ms).
- History: Room v2 (`durationMs`, MIGRATION_1_2), search, expand, copy, delete, delete-all (confirm); tap card reopens in Home via `openRequest` flow in Nav.
- Tests: `app/src/test` (PromptBuilder, CleanupService, HallucinationFilter, EnergyVad, StreamingTranscriber).

## M4/M5 known issues / next steps
- Untested on device: mic capture, VAD thresholds (tune `EnergyVad.minThreshold` if too sensitive/insensitive), transcribe cadence with the real (slow) Whisper models (partials are skipped while busy, so large models just update less often).
- transcribe() is blocking and runs on Dispatchers.Default; cancelling a running decode is not possible until stop returns.
- Integration (M6): implement EngineProvider; verify native llm engine accepts pre-templated prompts; History navigation reopen uses a shared flow (not a nav arg).
