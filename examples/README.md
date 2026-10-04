# Examples

One C library (`native/mymath.c`: `my_add`, `my_scale`) consumed from three platforms, showing the
two flows the plugin provides:

| Example | Flow | What it proves | Run |
|---|---|---|---|
| [`jvm/`](jvm) | `konanConfig` (.a) + `jvmInterop` (JNI .dylib + bridges) | full JNI path, end to end | `../../gradlew -p jvm run` |
| [`android/`](android) | same, cross-compiled per ABI, real app | APK with `.so` (arm64-v8a + x86_64) + `MainActivity` | `cd android && ./gradlew installDebug` |
| [`native/`](native) | `konanConfig` (.a) + K/N `cinterops` | the existing native path | `../../gradlew -p native runDebugExecutableMacosArm64` |

`jvm` and `native` build the plugin from source via `includeBuild`. `android` needs AGP 9 (Gradle 9.4,
its own wrapper), so it consumes the plugin from mavenLocal — publish it first:
`(cd ../.. && ./gradlew publishToMavenLocal)`.

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
on top:

```kotlin
// commonMain
expect fun add(a: Int, b: Int): Int

// nativeMain  — over the cinterop binding
actual fun add(a: Int, b: Int): Int = mymath.my_add(a, b)

// jvmMain / androidMain  — over the generated JNI binding
actual fun add(a: Int, b: Int): Int = example.my_add(a, b)
```

Strings and primitive buffers are marshalled by the binding; opaque pointers arrive as an address
(`Long`). See the README's "Generated bindings".

## What's verified here vs. needs a device

- `jvm` and `native` run to completion; `jvm` also round-trips strings and primitive buffers.
- `android` produces and verifies the `.so` (ELF + exported `Java_example_example_my_1add`-style symbols). Running it
  on Android needs an emulator/device and an AGP module — see [`android/README.md`](android/README.md).
