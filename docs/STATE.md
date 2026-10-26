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
- [ ] M4 Audio + streaming STT
- [ ] M5 Cleanup + UI + history
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
