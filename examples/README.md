# Examples

One C library (`native/mymath.c`: `my_add`, `my_scale`) consumed from three platforms, showing the
two flows the plugin provides:

| Example | Flow | What it proves | Run |
|---|---|---|---|
| [`jvm/`](jvm) | `konanConfig` (.a) + `jvmInterop` (JNI .dylib + bridges) | full JNI path, end to end | `../../gradlew -p jvm run` |
| [`android/`](android) | same, cross-compiled per ABI | `.so` for `arm64-v8a` + `x86_64` | `../../gradlew -p android assembleAndroidJni` |
| [`native/`](native) | `konanConfig` (.a) + K/N `cinterops` | the existing native path | `../../gradlew -p native runDebugExecutableMacosArm64` |

Each example auto-detects the newest `~/.konan/kotlin-native-prebuilt-*` (override with `KONAN_HOME`).
The `jvm` example also needs a JDK with `include/jni.h` (auto-scanned; the Android leg gets `jni.h`
from the NDK sysroot, so no JDK needed there).

## The two flows

```
native/*.c ──(konanConfig: runKonanClang)──> libmymath.a   per target
                                                  │
   ┌──────────────────────────────────────────────┴───────────────────────────┐
   │ JVM / Android leg (jvmInterop)                │ Native leg (cinterops)     │
   │                                               │                            │
   │ .def(headers) ─> JNI .c stubs + bridges .kt   │ .def(headers) ─> K/N klib  │
   │ .c + libmymath.a ─link─> libfoostubs.so/.dylib│ links libmymath.a          │
   └───────────────────────────────────────────────────────────────────────────┘
```

## The expect/actual split (you write tier 2)

The plugin generates the **low-level** tier per platform. You write the idiomatic `expect`/`actual`
on top — because how strings/pointers are marshalled is your call:

```kotlin
// commonMain
expect fun add(a: Int, b: Int): Int

// nativeMain  — over the cinterop binding
actual fun add(a: Int, b: Int): Int = mymath.my_add(a, b)

// jvmMain / androidMain  — over the generated JNI bridge
actual fun add(a: Int, b: Int): Int = example.kniBridge0(a, b)
```

Pointer/string params arrive at the JNI bridge as a raw address (`Long`); convert in your `actual`.

## What's verified here vs. needs a device

- `jvm` and `native` run to completion and print `5` / `40.0`.
- `android` produces and verifies the `.so` (ELF + exported `Java_..._kniBridgeN` symbols). Running it
  on Android needs an emulator/device and an AGP module — see [`android/README.md`](android/README.md).
