# ExecuTorch + Qualcomm QNN backend (decision D36)

Status: implemented and compiled; **never run on a device** (no Snapdragon hardware here). Research date 2026-10-26.

## What ships in the APK
- `org.pytorch:executorch-android-qnn:1.5.1` (Maven Central, BSD): `LlmModule` + `libexecutorch.so` + `libqnn_executorch_backend.so`.
  It is a QNN-enabled build (JNI has `MODEL_TYPE_QNN_LLAMA = 4`, used by `ExecuTorchQnnEngine`).
- `com.qualcomm.qti:qnn-runtime:2.39.3` (default; override `-PqnnRuntimeVersion=`) (Maven Central, license "Qualcomm AI Hub Model License", LICENSE.pdf/NOTICE.txt inside the AAR;
  the AAR is published for redistribution/consumption by apps): `libQnnHtp.so`, `libQnnSystem.so`, `libQnnHtpPrepare.so` (69 MB),
  `libQnnHtpV{68,69,73,75,79,81}{Stub,Skel}.so`. Debug APK grew 65 MB -> 134 MB. ExecuTorch 1.5.1 declares 2.37.0 but 2.37.x/2.38 lack the V81 skel (SM8850); V81 first appears in 2.39.3, so that is the default. Compatibility of the 1.5.1 backend with 2.39.3 is untested; pin with `-PqnnRuntimeVersion=2.37.0` if SM8650/SM8750 misbehave.
- Manifest: `<uses-native-library libcdsprpc.so required=false>` (vendor FastRPC lib, needed on Android 12+).
- Engine sets `ADSP_LIBRARY_PATH`/`LD_LIBRARY_PATH` to the app's nativeLibraryDir before loading (libs are extracted, `useLegacyPackaging=true`).
- Check the Qualcomm license yourself before distributing a release APK beyond personal use.

## Known gaps
- The pre-exported `.pte` files below have no README: the QNN SDK version / HTP arch they were compiled with is unknown. Context
  binaries must match the runtime's QNN version. The SM8750 and SM8850 files on the Hub are byte-identical (same SHA256).
- The JNI hardcodes decoder `llama3` for the QNN runner, so Qwen3 EOS handling is unverified; the engine also cuts on `<|im_end|>` etc. itself.
- Only temperature is honoured (no top-k/top-p/penalty in the ExecuTorch runner). Context size / threads settings are ignored.
- The JNI wrapper appears not to apply chat templates; `QnnModelInfo.buildPrompt` renders Qwen/Llama3/Phi templates (the BOS is added by the runner).

## Models (verified on HF via tree API)
`l3utterfly/Qwen-3-1.7B-qnn-executorch` (apache-2.0, ungated): `Qwen-3-1.7B.SM8650.pte` (2,403,955,732 B), `.SM8750.pte` and `.SM8850.pte`
(2,403,185,684 B). Same author also: `Llama-3.2-3B-Instruct-qnn-executorch` (llama3.2 license, ~2.45 GB), `phi-4-mini-instruct-qnn-executorch` (MIT, 3.68 GB);
the engine already has templates/tokenizers for these but they are not in the catalog. Others seen: `sine-dogma/Qwen3.5-0.8B-QNN-SM8650` (raw QNN
context binaries, needs a custom runner, not usable here), PowerServe/* (PowerServe format, not ExecuTorch).
No `.pte` repo ships a tokenizer: the engine uses `<model>.tokenizer.json`/`tokenizer.json` next to the `.pte`, else downloads
(SHA256-checked) Qwen/Qwen3-1.7B, unsloth/Llama-3.2-3B-Instruct or microsoft/Phi-4-mini-instruct `tokenizer.json` into `models/llm/`.
Ungated `executorch-community/*` / `software-mansion/*` repos are XNNPACK/CPU `.pte`, not QNN.

## Making your own .pte (needs x86 Linux host, cannot be done on device)
1. Install ExecuTorch from source and the Qualcomm AI Engine Direct SDK (QAIRT; same version as the runtime, 2.37 for the bundled one; free download after Qualcomm login, EULA applies).
2. `python -m examples.qualcomm.oss_scripts.llama.llama --soc_model SM8750 --compile_only --decoder_model qwen3_1_7b ... ` (see
   `examples/qualcomm/oss_scripts/llama/README.md` in pytorch/executorch). One `.pte` per SoC (SM8650=V75, SM8750=V79, SM8850=V81);
   Hybrid mode may need ~100 GB RAM (`--model_mode kv` less). Name the file `<Model>.<SMxxxx>.pte`, put it with a `tokenizer.json`
   in `<externalFilesDir>/models/llm/` (or upload to HF and add a custom model once the custom-model UI accepts `.pte`).

## Availability check
`ExecuTorchQnnEngine.availability(context, modelPath?)` returns null if usable, else a reason: non-arm64, non-Qualcomm SoC, unsupported SoC,
missing native libs (names the exact files, incl. the HTP arch stub/skel), no `.pte`, wrong file type, or SoC tag mismatch.
