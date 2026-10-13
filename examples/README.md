# Examples

One C library (`native/mymath.c`: `my_add`, `my_scale`) consumed from three platforms. Each declares it
the same way — `konan { val mymath = libraries.create("mymath"); bindings { create("mymath") { library(mymath) } } }` —
and gets the binding its Kotlin targets need:

| Example | Flow | What it proves | Run |
|---|---|---|---|
| [`jvm/`](jvm) | a library (.a) + a binding → JNI bindings and library | full JNI path, end to end | `../../gradlew -p jvm run` |
| [`android/`](android) | same, cross-compiled per ABI, real app | APK with `.so` (arm64-v8a + x86_64) + `MainActivity` | `cd android && ./gradlew installDebug` |
| [`android-ffm/`](android-ffm) | same library, FFM instead of JNI: jextract's bindings on PanamaPort, `libmymath.so` per ABI, no stub | FFM on Android, end to end on an emulator (a multiplatform library's Android target works the same, with `withJava()`) | `cd android-ffm && ./gradlew installDebug` |
| [`native/`](native) | a library (.a) + a binding → KGP's cinterop | the native path | `../../gradlew -p native runDebugExecutableMacosArm64` |

`jvm` and `native` build the plugin from source via `includeBuild`. `android` needs AGP 9 (Gradle 9.4,
its own wrapper), so it consumes the plugin from mavenLocal — publish it first:
`(cd ../.. && ./gradlew publishToMavenLocal)`.

Each example sets `konan { config { konanHome.set(kotlinNativeDistribution()) } }`: the Kotlin/Native
version the plugin needs, where the Kotlin plugin installs it. `android-ffm` also needs
`konan.config.jextract=/path/to/jextract`, e.g. in `~/.gradle/gradle.properties`.
No example needs a JDK's `jni.h`: the plugin ships one for desktop links, and Android's comes from the
NDK sysroot.

## The two flows

```
native/*.c ──(compileNativeLibraryMymath)──> libmymath.a   per target
                                                  │
   ┌──────────────────────────────────────────────┴───────────────────────────┐
   │ JVM / Android (generateJni, linkJni)          │ Kotlin/Native (cinterop)   │
   │                                               │                            │
   │ .def(headers) ─> JNI .c stub + Kotlin .kt     │ .def(headers) ─> K/N klib  │
   │ .c + libmymath.a ─link─> lib<pkg>stubs.so     │ embeds libmymath.a         │
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
