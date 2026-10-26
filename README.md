# DictateAI

Fully on-device dictation for Android: live speech-to-text with [whisper.cpp](https://github.com/ggml-org/whisper.cpp),
optional text cleanup / rewriting (formal, casual, shorter, longer...) with [llama.cpp](https://github.com/ggml-org/llama.cpp)
or ExecuTorch + Qualcomm QNN. English only. Nothing leaves the phone except model downloads from Hugging Face.

Target: Samsung Galaxy S / Snapdragon 8 and 7 series (arm64-v8a, Android 12+, API 31+).

## Install (prebuilt APK)
1. Copy the APK (`dist/voiceclean-debug.apk` after a local build; APKs are not committed to git) to the phone (or `adb install -r dist/voiceclean-debug.apk`).
2. Allow "install unknown apps" for your file manager/browser when prompted.
3. Open DictateAI, grant the microphone permission.
4. Settings shows the speech models recommended for your phone; download one (or use the Models tab).
5. Optional: download a cleanup model in Models to enable the rewrite buttons.

The APK is debug-signed (not for the Play Store). Models are stored in the app's external files dir and can be deleted in the Models tab.

## Build from source
Requirements: Linux/macOS, JDK 17, Android SDK with platform 35, build-tools 35.0.0, NDK 27.2.12479018, CMake 3.22.1, ~10 GB free disk, internet (Gradle and dependencies).

```bash
export ANDROID_HOME=~/android-sdk
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-35" "build-tools;35.0.0" "ndk;27.2.12479018" "cmake;3.22.1"
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew testDebugUnitTest   # JVM unit tests
./gradlew assembleDebug       # first build compiles ggml/llama/whisper for 7 CPU variants (~3 min)
```
Output: `app/build/outputs/apk/debug/app-debug.apk` (~130 MB). If the first run times out downloading Gradle, run it again.
Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

llama.cpp (b11899) and whisper.cpp (v1.9.5) are vendored under `app/src/main/cpp/third_party`.

## Docs
`CLAUDE.md` (entry point for AI agents), `docs/PLAN.md`, `docs/STATE.md`, `docs/DECISIONS.md`, `docs/BUILD.md`, `docs/QNN.md`, `docs/WHISPER_QNN.md`, `dist/INSTALL.md`.

Note: the Kotlin package and Gradle module are still named `com.voiceclean.app` internally; the app's display name is DictateAI.
