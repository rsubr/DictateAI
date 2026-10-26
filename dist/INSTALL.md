# VoiceClean (debug build) - install and first run

APK: `voiceclean-debug.apk` (about 65 MB, debug-signed, arm64-v8a, Android 12+).

## Install
**With adb (USB debugging on):**
```
adb install -r voiceclean-debug.apk
```
**Sideload:** copy the APK to the phone, open it in Files, and allow "Install unknown apps" for that file manager
(Settings > Apps > Special access > Install unknown apps). If Play Protect warns, choose "Install anyway".

## First run
1. Open VoiceClean, go to **Models**.
2. Download a speech model: **Whisper small.en** (488 MB). Good balance for live dictation.
3. Download a language model: **Qwen3 4B Q5_K_M** (2.9 GB). Wi-Fi recommended; downloads resume if interrupted.
   The first finished download of each kind is selected automatically.
4. Go to **Settings > Diagnostics** and tap **Self-test**. It loads both models, transcribes 1 s of silence and
   generates "Say OK", then shows timings and tokens/s. Do this first: it is the fastest way to see where something breaks.
5. Go to **Home**, tap the mic, grant microphone permission, speak, tap again. Cleaned text appears; use the chips
   (Formal, Casual, Shorter, Longer) and Copy.

Recording stops automatically if you leave the app (Android blocks background microphone use).

## Recommended models by device RAM
| RAM | Speech | Language model |
|-----|--------|----------------|
| 6 GB | base.en or small.en | Qwen3 1.7B Q8_0 (only one model is kept in memory) |
| 8 GB | small.en | Qwen3 4B Q5_K_M or Gemma 3 4B Q5_K_M |
| 12 GB+ | small.en, large-v3-turbo Q5_0 for accuracy | Qwen3 4B Q6_K |

Live dictation re-decodes audio every second, so big Whisper models update the live text less often (final text is unaffected).

## Reporting problems
1. **Settings > Diagnostics > Copy debug log**, then paste it into a message. It contains device/SoC/RAM, engine states,
   the app log, the last uncaught exception and the native ggml/llama/whisper log.
2. If the app **crashes instantly** (native crash), the Diagnostics log may be empty. Use logcat right after:
   ```
   adb logcat -d -s VoiceClean VoiceCleanJNI ggml AndroidRuntime DEBUG libc > logcat.txt
   ```
   Then reopen the app and copy the debug log too (the file log survives the crash and its last lines show what was loading).
3. Say what you did, what you expected, and which models were selected.
