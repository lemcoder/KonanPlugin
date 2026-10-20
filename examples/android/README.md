# Android: three libraries, JNI and FFM in one app

One Android app over three native libraries, each built its own way and bound its own way:

| Library | Language | Built by | Bound with | Loaded as |
|---|---|---|---|---|
| `cmath` | C | Kotlin/Native's clang | JNI | `libcmathstubs.so` (stub + archive) |
| `rustmath` | Rust | `cargo { }`, a `staticlib` | JNI | `librustmathstubs.so` (stub + archive) |
| `cppmath` | C++ | Kotlin/Native's clang | FFM, jextract on PanamaPort | `libcppmath.so` (the library itself) |

```
native/c/       cmath.c, cmath.h
native/rust/    Cargo.toml, src/lib.rs, rustmath.h (the C header the bindings are generated from)
native/cpp/     cppmath.cpp, cppmath.h (an extern "C" API over C++, which jextract reads)
src/nativeInterop/cinterop/{cmath,rustmath,cppmath}.def
src/main/kotlin/com/example/nativeapp/MainActivity.kt
```

`build.gradle.kts` declares three `libraries` and three `bindings`; `ffm()` on `cppmath` replaces JNI for
that binding only. The activity calls all three:

```kotlin
cmath.c_add(2, 3)                         // C, JNI
rustmath.rust_count_vowels("Kotlin Native") // Rust, JNI
cpp.cpp_median(arena.allocateFrom(JAVA_INT, 5, 1, 4, 2), 4)  // C++, FFM
```

The Rust and C++ libraries need runtimes Android does not give apps — Rust's std its unwinder, C++ libc++
and the unwinder. The plugin's Android links carry them from Kotlin/Native's NDK, and fail the build if
anything is left undefined.

FFM sets what the whole app needs: `compileSdk` 37 and `minSdk` 26 (PanamaPort), Java 21 sources
(jextract), and 64-bit ABIs only — here `arm64-v8a`, phones and Apple-silicon emulators.

## Run

```bash
rustup target add aarch64-linux-android
../../gradlew -p . installDebug -Pkonan.config.cargo=$HOME/.cargo/bin/cargo -Pkonan.config.jextract=$(which jextract)
# or, from the repository root, build, install and launch:
KONAN_CARGO=$HOME/.cargo/bin/cargo KONAN_JEXTRACT=$(which jextract) scripts/run-android.sh
```

The plugin is built from this checkout (`includeBuild("../..")`). Java 21 comes from
`kotlin { jvmToolchain(21) }`, whatever JDK runs Gradle. Needs an Android SDK (`local.properties` or
`ANDROID_HOME`).
