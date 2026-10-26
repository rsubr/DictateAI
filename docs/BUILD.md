# BUILD (verified 2026-10-26)

## Environment
```
export ANDROID_HOME=~/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64   # any JDK 17 (system /usr/bin/java works)
```
`local.properties` (gitignored) contains `sdk.dir=/home/agent/android-sdk`; recreate if missing.

## Versions
- Gradle 8.10.2 (wrapper), AGP 8.7.3, Kotlin 2.0.21 (compose compiler plugin), KSP 2.0.21-1.0.28
- compileSdk/targetSdk 35, minSdk 31, abi arm64-v8a only
- NDK 27.2.12479018 (r27c), CMake 3.22.1, build-tools 35.0.0
- Compose BOM 2024.12.01, Room 2.6.1, DataStore 1.1.1, Navigation 2.8.5, OkHttp 4.12.0, coroutines 1.9.0
- Versions are in `gradle/libs.versions.toml`.

## SDK packages (already installed)
```
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "ndk;27.2.12479018" "cmake;3.22.1"
```

## Build
```
cd /home/agent/tmp/zz-null
./gradlew assembleDebug        # ~2.5 min cold, includes native CMake build
```
APK: `app/build/outputs/apk/debug/app-debug.apk` (~60MB, debug-signed with default debug keystore, installable via `adb install -r`).

Notes:
- First wrapper run may time out downloading Gradle (10s connect timeout); just re-run.
- Native code: `app/src/main/cpp/CMakeLists.txt` (currently a stub `libvoiceclean_jni.so`; STL is `c++_shared`).
- Room schemas export to `app/schemas/`.
- Disk is tight (~4GB free after first build; ~/.gradle is ~1.5GB). Clean with `./gradlew clean` or `rm -rf app/.cxx app/build` if needed.

## Native (M2)
- Always build with `flock /tmp/voiceclean-gradle.lock ./gradlew assembleDebug` when other agents are active. Cold native build ~2.5 min (7 CPU variants of ggml + llama + whisper); .cxx lives in `app/.cxx` (`rm -rf app/.cxx` to reclaim disk).
- Third party pinned in `app/src/main/cpp/third_party` (llama.cpp b11899, whisper.cpp v1.9.5; vendored, pruned). To upgrade: replace dirs, re-delete whisper's `ggml/`, keep the `src/aneforge` file in the CMake.
- Native libs in APK: libvoiceclean_jni, libllama, libggml, libggml-base, libggml-cpu-android_armv*.so, libc++_shared.
