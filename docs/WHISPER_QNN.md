# Whisper on the Hexagon NPU (research, 2026-10-26)

Scope: can Whisper STT run on the Qualcomm Hexagon NPU (HTP) inside this app? No app code was changed. Nothing here was run on a device
(none available); every performance number is from a third party and is labelled with its device. "UNKNOWN" = could not be verified.

Context: ExecuTorch 1.5.1 QNN AAR + `com.qualcomm.qti:qnn-runtime:2.39.3` already bundled (docs/QNN.md, D36); targets SM8650 (HTP v75),
SM8750 (v79), SM8850 (v81); English only; accuracy over speed (D7); models downloaded in-app (D6); whisper.cpp v1.9.5 compiled against llama.cpp
b11899's ggml (D11/D17), CPU backends loaded at runtime (`GGML_BACKEND_DL`).

## TL;DR
- Three real routes exist. They differ a lot in cost and in how much accuracy we keep.
  1. **ggml-hexagon via our existing whisper.cpp** (cheapest, keeps GGUF/ggml whisper models and the whole decode/prompt/beam stack). Experimental, flash-attn only, one open SM8850 correctness bug. Probably 1-2 weeks incl. build tooling.
  2. **Qualcomm AI Hub prebuilt Whisper (QNN context binary / precompiled ONNX)**: fastest and best-tested on Qualcomm silicon (large-v3-turbo encoder ~120-300 ms for a 30 s window on 8 Elite/Gen5), downloadable with no account, but needs a custom runtime and decode loop, is multilingual-only (no `.en`), and its QAIRT 2.50 binaries do not match our bundled 2.39.3 runtime. 3-5 weeks.
  3. **ExecuTorch QNN Whisper**: not viable now (export script is whisper-tiny only; Android `AsrModule` needs different method names; open JNI bug). Do not pursue.
- **Recommendation: GO on route 1 as an opt-in experimental setting (staged, behind a feature flag, CPU stays default). Defer route 2 until route 1 is measured on a real device. NO-GO on route 3.** Details and ordered plan at the end.

## 1. Qualcomm AI Hub Whisper models

Source: HF org `qualcomm` (API listing) and model cards, e.g. https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo (version v0.65.0, QAIRT 2.50).

| Repo | Status | Notes |
|---|---|---|
| `Whisper-Tiny`, `-Base`, `-Small`, `-Medium`, `-Large-V3-Turbo` | live, float | multilingual checkpoints (openai/whisper-*), apache-2.0 tag |
| `Whisper-Small-Quantized`, `-Medium-Quantized`, `-Large-V3-Turbo-Quantized` | live, **w8a16** | weights int8, activations int16; KV caches uint8 |
| `Whisper-Tiny-En`, `-Base-En`, `-Small-En`, `-Medium-En`, `Small-V2` | **deprecated**, README is a stub, no files | so **no English-only variants** are available. `Distil-Whisper` (distil-small.en) exists but only as ONNX/QNN_DLC/TFLITE float, not a prebuilt NPU binary |

- **Formats** (per model, per chipset): `QNN_CONTEXT_BINARY` (encoder.bin + decoder.bin + metadata.json), `PRECOMPILED_QNN_ONNX` (same .bin wrapped by tiny encoder.onnx/decoder.onnx for ONNX Runtime QNN EP, needs ORT 1.30.0), `VOICE_AI` (for Qualcomm Voice AI SDK, from the Qualcomm Package Manager = needs login). TFLite/ONNX/DLC appear only for Distil-Whisper. No `.pte`.
- **Per-SoC compile: yes.** Binaries are per chipset: "Snapdragon 8 Elite Gen 5 For Galaxy" (= SM8850, v81), "8 Elite For Galaxy" (= SM8750, v79), "8 Gen 3" (= SM8650, v75), 8 Gen 1, 7 Gen 4 (quantized only), plus automotive/IoT/X Elite. Exactly our three targets are covered. No generic binary.
- **Download without account/API key: yes.** Public S3, HTTP 200 verified with `curl -I`, e.g.
  `https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/whisper_large_v3_turbo/releases/v0.65.0/whisper_large_v3_turbo-qnn_context_binary-float-qualcomm_snapdragon_8_elite_for_galaxy.zip`
  (links are in each card and in `release_assets.json`). The URL embeds the release version, so pinned links can rot when Qualcomm re-releases (UNKNOWN retention policy). Only the Voice AI SDK path needs a Qualcomm login. Compiling your own variant needs an AI Hub Workbench account (hosted compile) or local QAIRT (login).
- **Sizes** (zip, verified via Content-Length / zip directory): tiny float 106 MB; small w8a16 285 MB; large-v3-turbo float 2.02 GB; **large-v3-turbo w8a16 824 MB** (encoder 686 MB, decoder 296 MB). medium sizes not fetched.
- **Interface** (from `metadata.json` of turbo w8a16): encoder input `input_features` [1,128,3000] uint16 (so the 128-bin log-mel must be computed on host and quantized), outputs 4 layers of cross-attn K/V caches (uint8, [20,1,64,1500] and [20,1,1500,64]); decoder is a single-token step with static self-KV [.,.,199] + `position_ids`, max 200 tokens. The 30 s window is fixed (no variable audio context), no timestamps.
- **License**: HF metadata `apache-2.0` for the Whisper cards; `LICENSE` file just points to the HF transformers Apache license. The binaries embed QAIRT-compiled graphs; the QNN runtime libs we already ship are under the Qualcomm AI Hub Model License (docs/QNN.md). Redistribution/hosting terms for the binaries themselves: UNKNOWN (we would link to Qualcomm's S3, not rehost).
- **Latency claims (Qualcomm, on-device, AI Hub profiling)**, turbo float, QNN context binary: encoder 283 ms (8 Elite Gen5), 305 ms (8 Elite), 401 ms (8 Gen 3); decoder 6.5 / 6.7 / 7.8 ms per token. Turbo w8a16: encoder 121 / 151 / 187 ms, decoder 3.3 / 3.5 / 4.4 ms. Small w8a16 (Gen5): encoder ~27 ms; medium w8a16 ~70 ms; tiny ~12 ms; base float 21 ms; small float 51 ms; medium float 135 ms.
- **Accuracy / WER: no WER is published on the cards** ("accuracy: use `qai-hub-models numerics`", repo has `evaluate.py`). w8a16 vs float quality loss for turbo: UNKNOWN. Given D7 (accuracy first) we would have to measure it ourselves (LibriSpeech/own dictation set) before offering w8a16; float is 2.4x bigger.
- Decode-side caveat: decoder is a fixed 200-token KV graph; prompt (initial_prompt) tokens would consume decoder steps; beam search/temperature fallback/timestamps must be implemented host-side.
- Related research: NPUsper (https://arxiv.org/abs/2607.01108, code https://github.com/npusper/NPUsper) reports up to 4.84x lower per-word latency and 33x lower time-to-first-token for streaming Whisper on mobile NPUs; SoCs and WER not stated in the abstract. Interesting for streaming later, not a dependency.
- Community conversions (not Qualcomm): `FluidInference/whisper-large-v3-turbo-qnn` (apache-2.0, ORT-QNN 1.22, **X Elite only**), `qualcomm-ai-hub-community/whisper-*-qcs6490-*`, `mobbins/*x-elite*`. None is a Galaxy phone target; ignore.

## 2. ExecuTorch route

- Generic Whisper runner exists: `examples/models/whisper` (https://github.com/pytorch/executorch/tree/main/examples/models/whisper), C++ `AsrRunner`; documented backends are CUDA, Metal, CPU/XNNPACK (via Optimum-ExecuTorch). It expects methods named `encoder` and `text_decoder`.
- Qualcomm-specific: `examples/qualcomm/oss_scripts/whisper/` (`whisper.py`, `whisper_model.py`, C++ `qnn_whisper_runner`). **It hardcodes `openai/whisper-tiny`** (AutoModel/tokenizer/processor all `whisper-tiny`), 16a8w PT2E quantization, static-KV decoder, methods named `encoder`/`decoder`, WER eval on a 1-clip librispeech dummy. Larger models would need export work (cross-attn cache shapes, memory, calibration); no evidence anyone shipped one.
- Pre-exported `.pte` on HF: only XNNPACK/CPU (`software-mansion/react-native-executorch-whisper-*`, `larryliu0820/whisper-*-ExecuTorch-XNNPACK`, `Arm/whisper-*-int8-xnnpack-executorch`), Metal, WebGPU. **No QNN Whisper `.pte` found** (HF search "whisper executorch" / "whisper qnn").
- **Android API in our AAR (verified by unzipping `executorch-android-qnn-1.5.1.aar`, 3.6 MB; libexecutorch.so 8.8 MB):** it contains `org.pytorch.executorch.extension.asr.AsrModule` (+`AsrCallback`, `AsrTranscribeConfig`), `@Experimental`, `AsrModule(modelPath, tokenizerPath, dataPath, preprocessorPath)`, `transcribe(wavPath, ...)` (takes a **WAV file path**, no PCM-in-memory API), and the .so has the `text_decoder` symbol. So a Java ASR runner exists, but (a) requires the method names `encoder`/`text_decoder`, which the Qualcomm export does not use (`encoder`/`decoder`), (b) mel preprocessing needs a separate `preprocessor.pte`, (c) we have no evidence the generic runner copes with the QNN static-KV decoder contract (qnn_whisper_runner is a separate binary, not in the AAR).
- Open bug: pytorch/executorch#22061 "QNN-delegated static-cache decoder produces correct output via native C++ Module, but degenerate/repeating output via Android JNI Module" (whisper tiny, SM8750, QAIRT 2.37, open). Evidence it works at all in C++: tiny, 11 s clip: load 0.44 s, encode 32 ms, decode 159 ms.
- Verdict: tiny-only upstream support, open Android bug, would need a custom JNI over `Module`. Worse than route 2 (Qualcomm already ships big models compiled) and route 1. **No-go.**

## 3. Other options

### 3a. ggml-hexagon (llama.cpp ggml) used by whisper.cpp - the cheap route
- **Our pinned llama.cpp b11899 (published 2026-10-26, i.e. newest) already vendors `ggml/src/ggml-hexagon/` (host backend + `htp/` DSP kernels).** It is currently **off**: `GGML_HEXAGON` default OFF, not set in `app/src/main/cpp/CMakeLists.txt`. The backend registers as device type `GGML_BACKEND_DEVICE_TYPE_GPU`, as a DL backend (`ggml_add_backend_library`), so our existing `ggml_backend_load_all_from_path(nativeLibraryDir)` in `core/whisper_core.cpp` would pick up `libggml-hexagon.so` if present. whisper.cpp v1.9.5's `whisper_backend_init_gpu` takes the first GPU/IGPU device, so `use_gpu=true` (we set false today) is all that selects it. Same shared ggml, no symbol clash (D11).
- **What it needs to build:** Hexagon SDK (6.x) + hexagon toolchain; upstream supplies a public Docker image `ghcr.io/snapdragon-toolchain/arm64-android:v0.8` (amd64; contains NDK r28b, OpenCL SDK, Hexagon SDK 6.7.0.0; docs https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/snapdragon/README.md). The CMake builds the DSP skels via ExternalProject: `libggml-htp-v73/v75/v79/v81` (+v85 per the vendored file/commit "enable hexagon v85"). Mapping: SM8650 -> v75, SM8750 -> v79, SM8850 -> v81. This box has docker, but only ~3.4 GB free disk; the image size is UNKNOWN (likely several GB) -> needs disk or building elsewhere. The host-side `libggml-hexagon.so` is cross-compiled with the NDK (we have r27.2; upstream uses r28b - UNKNOWN whether r27 is enough).
- **Runtime requirements:** dlopens vendor `libcdsprpc.so` (our manifest already has `uses-native-library libcdsprpc.so required=false`); skel `.so` found via `ADSP_LIBRARY_PATH` (we already set it for QNN). The backend requests an **unsigned PD** (`DSPRPC_CONTROL_UNSIGNED_MODULE`, ggml-hexagon.cpp line ~2097), i.e. no per-device signing; this works on retail Samsung/Qualcomm devices for QNN and is expected to for ggml-hexagon but some retail firmware refuses (reported in a Termux build README, https://github.com/Sebby37/llamacpp-hexagon-termux). UNKNOWN for Galaxy S One UI until tried. Issue https://github.com/ggml-org/llama.cpp/issues/27677 reports session failure if `/vendor/lib64` is on `LD_LIBRARY_PATH` (SM8850, Termux) - relevant because we set `LD_LIBRARY_PATH` for QNN.
- **Two QNN stacks vs ggml-hexagon:** independent (ggml-hexagon talks FastRPC to its own skel, not QNN), so no version clash with qnn-runtime 2.39.3. Both can coexist; NPU contention if STT and the ExecuTorch LLM run at once is UNKNOWN.
- **Op coverage for Whisper (checked in vendored `htp-kparams.cpp`):** MUL_MAT (F32/F16/BF16 and Q4_0, Q4_1, Q8_0, IQ4_NL, MXFP4, Q2-Q6_K, Q1_0, Q2_0; `Q5_0` is NOT supported and silently falls back to CPU), FLASH_ATTN_EXT (K/V must be F16 or Q8_0, mask F16), IM2COL (conv1d front end), NORM (LayerNorm), GELU/GELU_ERF/GELU_QUICK, ADD/MUL/SCALE, SOFT_MAX, CPY/CONT, GET_ROWS. Unsupported ops are scheduled to CPU by `ggml_backend_sched`.
- **Whisper-specific facts from third parties:**
  - whisper.node PR #15 (https://github.com/mybigday/whisper.node/pull/15): "ggml-hexagon can only run whisper's flash-attention graph; the non-FA graph uses an f32->f16 `ggml_cpy` that HTP cannot execute and asserts at `whisper_init_state`" -> forced `flash_attn=true` when offloading (our D35 flash_attn setting must be forced on for the NPU path).
  - Benchmarks there are on an **IQ-9075 board (HTP v73, not a phone)**, ms, HTP vs CPU: tiny.en encode 95 vs 361; base 143 vs 787; small 360 vs 2824; medium 812 vs 8799; large-v3-turbo-q8_0 **2479 vs 9400**; end-to-end transcribe (the JFK clip): small 915 vs 2789, medium 1915 vs 8006, turbo-q8_0 2838 vs 7616. Decoder is **not faster** on HTP (turbo 9.8 vs 8.2 ms/token; medium 35 vs 38 ms). So expect encoder 3.6-10.8x, whole utterance 1.5-4.2x. Snapdragon 8 Elite-class (v79/v81 with HMX) numbers: UNKNOWN, should be better than v73 but this is not measured.
  - whisper.rn (React Native) enabled it for Android as experimental (PR #314 -> #337, https://github.com/mybigday/whisper.rn/pull/314; requires the Hexagon SDK to build; "better than CPU" on an 8 Elite device, no numbers). This is the closest prior art to our integration.
  - **Open correctness bug on our flagship target:** https://github.com/ggml-org/llama.cpp/issues/25876 (SM8850 / v81, whisper 1.9.1 F16/Q4_0/Q8_0/Q5_0): garbled/repeated output with HMX; author traced it to GELU outputs not visible to later DSP ops (workaround `GGML_HEXAGON_OPFILTER='.GELU.'`, or `ADD` on CPU), closed as "not planned" by the stale bot on 2026-09-13 with no maintainer reply. Upstream has since merged "hexagon: improved GELU accuracy (#30104, 2026-10-07)" and many fixes up to 2026-10-26 that are in b11899, so it may be fixed - UNKNOWN until tested on SM8850. Env var `GGML_HEXAGON_OPFILTER` can be set with `setenv` before backend init as a fallback.
  - Accuracy: the ggml kernels are ggml's own (F16/Q8_0 math), so the models are the same GGUF/ggml whisper models we use today; WER should match CPU except for numerical differences or bugs like the above. Needs a CPU-vs-NPU transcript diff on a test set (we already have the Self-test harness, D24/D35).
- Other forks (not recommended): zhouwg/ggml-hexagon, haozixu/llama.cpp-npu, Fectxd/htp-v68-builder (WoS) - superseded by upstream.

### 3b. ggml OpenCL (Adreno GPU)
- `ggml/src/ggml-opencl` is vendored, off in our CMake (`GGML_OPENCL OFF`). It is tuned for llama (Q4_0/Q8_0) on Adreno; for Whisper the evidence is poor: whisper.cpp issue #3708 (Adreno 830: `GGML_ASSERT(M % 4 == 0)`), #3935 (Adreno: flash-attn gives garbage, ~10x slower), no published Whisper-on-Adreno speedups found. Treat as unproven; not worth chasing before Hexagon. Vulkan also off and unproven.

### 3c. ONNX Runtime QNN EP
- Maven Central `com.microsoft.onnxruntime:onnxruntime-android-qnn`, latest **1.29.0** (2026-08-12; versions 1.20-1.29 present). AAR is only 9.4 MB (`libonnxruntime.so` 24 MB + JNI); it depends on `com.qualcomm.qti:qnn-runtime:2.42.0` (POM; 1.22.0 -> 2.33.0, 1.23.2 -> 2.37.1 per search). `SessionOptions.addQnn(...)` exists in the Java API (verified in classes.jar).
- Qualcomm's `PRECOMPILED_QNN_ONNX` Whisper assets say "QAIRT 2.50, ONNX Runtime 1.30.0"; Maven only has ORT-QNN up to 1.29.0 (-> QNN 2.42). Whether 1.29 + a 2.50 runtime (Maven has 2.50.0 and 2.51.0) loads the 2.50 context binaries: UNKNOWN. Context binaries generally need a runtime at least as new as the compiler.
- **Single-QNN-runtime constraint:** the process can only usefully load one set of `libQnnHtp*.so`. The ExecuTorch 1.5.1 backend expects 2.37 (we run 2.39.3, untested), ORT 1.29 expects 2.42, Whisper binaries are 2.50. Aligning all three on one version (e.g. 2.50/2.51, which also has V81 skel) is an unverified compat gamble for the ExecuTorch LLM backend that has itself never been run on a device. This is the main hidden cost of route 2.
- Cost on APK: +~34 MB (ORT) and a 2.50 qnn-runtime instead of 2.39.3 (size ~ like today's 69 MB prepare lib; per AAR 134 MB debug APK already).

### 3d. sherpa-onnx
- README lists Qualcomm NPU (QNN) support (docs https://k2-fsa.github.io/sherpa/onnx/qnn/index.html) but the documented models are SM8850 Zipformer CTC (zh), Paraformer and SenseVoice. **Whisper is not listed**, and builds are from source with the QNN SDK. Not a route for Whisper.

## 4. What an implementation needs in our app

Existing seams: `stt/SttEngine.kt` is `load(path)`, `transcribe(pcm: FloatArray, prompt: String?, opts: DecodeOpts(partial, beam))`, `awaitReady()`, `unload()`; `WhisperEngine` + `FallbackSttEngine` implement it over JNI -> `core/whisper_core.cpp`; `StreamingTranscriber` runs two-tier decoding (D33): partial = reduced `audio_ctx`, greedy; final = full context, optional beam.

### Route 1 (ggml-hexagon): changes
1. CMake: `GGML_HEXAGON=ON` for arm64 Android with `HEXAGON_SDK_ROOT`; build skels (v75/v79/v81), package `libggml-hexagon.so` + `libggml-htp-v*.so` into jniLibs (extracted, already `useLegacyPackaging`). Decide build tooling (docker image or SDK install), gradle property to turn it on so default builds stay unchanged.
2. `whisper_core.cpp`: new `use_gpu` argument (maybe `device` = "cpu"/"npu"), force `flash_attn=true` for NPU, set `GGML_HEXAGON_*` env (`OPFILTER` fallback, `POWER_MODE=balanced` for battery; default aliases to `burst`) before `ggml_backend_load_all_from_path`. Don't set `LD_LIBRARY_PATH` to include `/vendor/lib64` (issue 27677). Log `ggml_backend_dev_*` list to the native log ring to see whether HTP0 registered.
3. Kotlin: setting "STT accelerator: CPU / Hexagon NPU (experimental)", `WhisperEngine.load` passes it; extend `availability` with SoC/HTP-version check (reuse the QNN SoC detection in ExecuTorchQnnEngine); automatic fall back to CPU + visible error if session creation fails (D22 pattern). Benchmark-on-load (like D35) keeping the faster of CPU/NPU per model file, and a CPU-vs-NPU transcript diff in Self-test.
4. Streaming: decoder runs on CPU anyway (HTP decoder is not faster), encoder is the bottleneck, so the existing D33 scheme works unchanged; partials use reduced `audio_ctx` - **must be re-validated** on HTP (dynamic shapes in FA/IM2COL; unknown). Expect final re-decode of large-v3-turbo-q8_0 to drop from ~7.6 s to ~2.8 s (IQ-9075 evidence) and probably more on 8 Elite; that would also make beam 3 affordable.
5. Models: unchanged (same GGML files from ggerganov/whisper.cpp already in the catalog). Do NOT use Q5_0 for NPU (falls back); prefer Q8_0 / F16 / Q4_0 (Q8_0 is also the accuracy-friendly choice).
- Effort: ~3 days to get a device-testable build (CMake + packaging + flag), ~3-5 days of device debugging and tuning, plus tooling/disk. Risks: SM8850 v81 correctness (issue 25876), unsigned-PD on One UI, experimental backend churn (daily commits), Hexagon SDK/docker disk size, APK +skels (few MB, UNKNOWN exact), FastRPC session killed on screen lock/background (seen in the same issue: `dspqueue_read failed 0x2e`) -> need session recreate-on-failure.

### Route 2 (Qualcomm AI Hub binaries)
1. Runtime choice: (a) ORT-QNN 1.29 with `PRECOMPILED_QNN_ONNX` (needs 2.5x runtime; compat UNKNOWN) or (b) direct QNN C API (`QnnContext_createFromBinary` on `encoder.bin`/`decoder.bin`) from new JNI C++ - needs QNN headers (from the QAIRT SDK; login-gated download, or vendored from ExecuTorch's `backends/qualcomm` includes - UNKNOWN licence implications). Either way a new native/JNI layer, ~2-3 weeks.
2. Host code in our JNI: 128-bin (turbo) / 80-bin log-mel for fixed 30 s padded input (whisper.cpp's mel code may be reusable; quantize to uint16 with metadata scales), BPE tokenizer (from tokenizer.json), the forced prefix `<|startoftranscript|><|en|><|transcribe|><|notimestamps|>`, logit suppression, greedy/beam, temperature fallback, no-speech detection, hallucination filters; initial_prompt costs decoder steps; dequantized uint8 KV handling. All whisper.cpp quality heuristics we rely on would be re-implemented.
3. Model manager: per-SoC URL selection (v75/v79/v81 -> "8 Gen 3"/"8 Elite For Galaxy"/"8 Elite Gen 5 For Galaxy"), unzip (zip of 0.8-2 GB; D20 downloader expects a single file + SHA - need zip handling and SHA from `release_assets.json`/S3 ETag), tiny/base/small/medium/turbo choices. Catalog entries for the same sizes already exist as ggml, so UX needs a clear "NPU model" kind.
4. Streaming: encoder cost is constant per window (~120-300 ms turbo, any audio length) so partials at 1 s cadence on turbo become feasible, ~0.5 s/decode: this is the best UX of all routes. But no reduced-audio-ctx trick, and every partial pays the full encoder.
5. Accuracy: measure float vs w8a16 vs ggml Q8_0 ourselves; multilingual-only checkpoints (turbo is multilingual anyway; others have no `.en` quality bump) and english forced.
- Effort 3-5 weeks. Risks: QAIRT 2.50 vs bundled 2.39.3/2.42 mismatch (affects the ExecuTorch LLM too), unreplaceable fixed shapes, URL stability, big downloads (turbo float 2 GB per SoC), license of redistribution of binaries.

### Expected speedup (evidence summary)
| Route | Evidence | Expected on our targets |
|---|---|---|
| ggml-hexagon | IQ-9075 (v73): encoder 3.6-10.8x, whole-utterance 1.5-4.2x; turbo-q8_0 7.6 s -> 2.8 s | likely >= that on v79/v81 (UNKNOWN), decoder stays CPU |
| AI Hub turbo float/w8a16 | Qualcomm profiling: encoder 121-305 ms (8 Elite/Gen5), decoder 3-7 ms/token | whole utterance well under 1 s, vs ~7.6 s for turbo-q8_0 on a v73 board CPU (phone CPU number UNKNOWN) |
| ExecuTorch tiny | C++ runner tiny: enc 32 ms, dec 159 ms (SM8750) | not applicable (tiny only) |

## Recommendation
**GO, staged. Route 1 first (ggml-hexagon through the existing whisper.cpp), as an opt-in experimental accelerator with CPU as default and automatic fallback; Route 2 only if Route 1 measurements on a real 8 Elite/Gen5 are disappointing or buggy and a >= turbo-quality sub-second final is wanted; Route 3 NO-GO.**
Why: it reuses all current model downloads, decoding quality features, streaming logic and the one-ggml design, is mostly build/packaging work, and has public prior art (whisper.rn, whisper.node) with 1.5-4x reported end-to-end gains. The accuracy-over-speed rule (D7) is respected because speed headroom lets us use turbo/medium with beam instead of small greedy. Main blockers to resolve early: (1) SM8850 garbled-output bug, (2) unsigned PD acceptance on Galaxy firmware, (3) Hexagon SDK/docker disk.

### Ordered work plan
1. Free disk (>= 10-15 GB) or build elsewhere; pull `ghcr.io/snapdragon-toolchain/arm64-android:v0.8`; build `libggml-hexagon.so` + skels for the vendored b11899 (gate behind `-PhexagonSdk`/CMake option; default builds unchanged). Check NDK r27 vs r28b. (0.5-1 d)
2. Package libs, add `useNpu` + forced flash-attn + env setup to `whisper_core.cpp`/JNI, expose a setting and logging of devices (`HTP0` present?). Fallback to CPU on any failure. (1-2 d)
3. Have the user run on a Galaxy S with each of: large-v3-turbo Q8_0, medium.en Q8_0, small.en; collect native log, Self-test CPU-vs-NPU transcripts + timings (encode, decode, RTF), check for garbling (SM8850) and try `GGML_HEXAGON_OPFILTER='.GELU.'`. (user-in-the-loop, 1-3 d)
4. If correct: add benchmark-on-load (extend D35 `stt_bench`) to pick CPU vs NPU per model, re-tune `StreamingTranscriber` cadence/beam from measured RTF, validate reduced-`audio_ctx` partials on HTP, handle session loss (recreate on failure, screen lock/background). (2-3 d)
5. Decision gate: if NPU is wrong/slow/unsigned-blocked on target devices, stop; evaluate Route 2 with a 1-week spike (turbo w8a16 on one SM8750 phone through ORT-QNN 1.29 + qnn-runtime 2.50/2.51 in a throwaway module) before committing; at the same time check whether the ExecuTorch LLM backend survives the runtime bump.
6. Document in STATE/BUILD.md, add an `availability()`-style explainer string in Settings, then (optional) add Route 2.

## Sources
- https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo (+ `-Quantized`, `Whisper-Small`, etc. cards; `release_assets.json`); S3 `qaihub-public-assets` zips (metadata.json inspected via HTTP range reads)
- https://github.com/qualcomm/ai-hub-models/tree/main/src/qai_hub_models/models/whisper_large_v3_turbo
- https://github.com/pytorch/executorch (examples/models/whisper, examples/qualcomm/oss_scripts/whisper, extension/android .../asr/AsrModule.kt, issue 22061)
- https://repo1.maven.org/maven2/org/pytorch/executorch-android-qnn/1.5.1/ (AAR inspected), .../com/microsoft/onnxruntime/onnxruntime-android-qnn/ (1.29.0 AAR + POM), .../com/qualcomm/qti/qnn-runtime/maven-metadata.xml (2.26.0-2.51.0)
- https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/snapdragon/README.md, issues 25876 and 27677, release b11899, commits to `ggml/src/ggml-hexagon`
- https://github.com/mybigday/whisper.node/pull/15, https://github.com/mybigday/whisper.rn/pull/314
- https://github.com/ggml-org/whisper.cpp/issues/3708, /3935 (OpenCL Adreno)
- https://k2-fsa.github.io/sherpa/onnx/qnn/index.html; https://arxiv.org/abs/2607.01108
