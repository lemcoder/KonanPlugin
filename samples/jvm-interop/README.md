# JVM interop sample

Demonstrates the `generateJvmInterop` task: generating **JNI bindings** for a C library from a
`.def` file using the JVM flavor of Kotlin/Native's `cinterop` stub generator, then calling the C
code from the JVM.

## What happens

1. **`generateJvmInterop`** (from this plugin) runs the internal generator
   `org.jetbrains.kotlin.native.interop.gen.jvm.MainKt -flavor jvm` (bundled in the Kotlin/Native
   `kotlin-native-compiler-embeddable.jar`). For [`mymath.def`](mymath.def) it produces:
   - `build/generated/jvmInterop/kotlin/sample/mymath/mymath.kt` — Kotlin wrappers + `external fun kniBridgeN(...)`
   - `build/generated/jvmInterop/c/samplemymathstubs.c` — `JNIEXPORT ... Java_sample_mymath_mymath_kniBridgeN(...)`
2. **`linkStubs`** compiles the generated `.c` together with [`src/main/c/mymath.c`](src/main/c/mymath.c)
   into `build/nativeLibs/libsamplemymathstubs.dylib`.
3. **`compileKotlin`** compiles the generated bindings plus [`Main.kt`](src/main/kotlin/Main.kt)
   against `kotlinx.cinterop` (from the embeddable jar).
4. **`run`** executes with `-Djava.library.path=build/nativeLibs`, so `loadKonanLibrary()` finds the
   stub library. The Kotlin `my_add` / `my_scale` calls go through JNI into the C implementation.

## Run

```bash
../../gradlew -p . run
```

Expected output:

```
my_add(2, 3)    = 5
my_scale(4.0)   = 40.0
```

## Requirements / knobs

- A Kotlin/Native distribution under `~/.konan/kotlin-native-prebuilt-*` (auto-detected), or set
  `KONAN_HOME`.
- A JDK that ships `include/jni.h` (the JVM running Gradle is often a JBR without it). The build
  scans installed JDKs; override with `-Pjni.home=/path/to/jdk` if detection fails.
- `clang` on `PATH` (used by `linkStubs`; for cross-compilation route this through `run_konan` instead).
- `hostTarget` in [`build.gradle.kts`](build.gradle.kts) is `macos_arm64` — change for other hosts.

## Caveat

The JVM/JNI flavor of `cinterop` is **internal, unsupported** tooling. The generated source shape and
the `kotlinx.cinterop` JVM runtime contract are not stable API and may change between Kotlin/Native
versions.
