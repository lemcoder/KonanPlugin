# Examples

One C library (`native/mymath.c`: `my_add`, `my_scale`) consumed from each platform the plugin binds, and
an Android app over three. Each declares a library and a binding the same way —
`konan { val mymath = libraries.create("mymath"); bindings { create("mymath") { library(mymath) } } }` —
and gets the binding its Kotlin targets need:

| Example | Flow | What it proves | Run |
|---|---|---|---|
| [`jvm/`](jvm) | a library (.a) + a binding → JNI bindings and library | full JNI path, end to end | `../../gradlew -p jvm run` |
| [`android/`](android) | three libraries in one app, each built and bound its own way: C (clang) and Rust (cargo) over JNI, C++ (clang) over FFM on PanamaPort | JNI and FFM side by side in one APK, a Rust staticlib and C++'s runtime linked for Android, on an emulator | `../../gradlew -p android installDebug` |
| [`native/`](native) | a library (.a) + a binding → KGP's cinterop | the native path | `../../gradlew -p native runDebugExecutableMacosArm64` |
| [`kmp/`](kmp) | one binding for a multiplatform library: cinterop on iOS, macOS and Linux, JNI on Android, `expect`/`actual` over both | tests on an iOS simulator, macOS and Linux, and an Android app, calling the same C through one common API | `../../gradlew -p kmp :shared:allTests :androidApp:installDebug` |

Every example builds the plugin from source via `includeBuild`, with the repository's wrapper.

Each example sets `konan { config { konanHome.set(kotlinNativeDistribution()) } }`: the Kotlin/Native
version the plugin needs, where the Kotlin plugin installs it. `android` also needs
`konan.config.cargo` and `konan.config.jextract`, e.g. in `~/.gradle/gradle.properties`. `android` and
`kmp` build for Android, which needs an Android NDK: `ANDROID_NDK_HOME`, or `konan.config.androidNdk`.
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
- `kmp` passes its common tests on an iOS simulator and macOS, and — cross-linked on macOS — in an x86-64
  Ubuntu container; links `Shared.framework` for devices with `my_add` in it; and its app shows C's results
  on an Android emulator.
- `android` runs on an arm64 emulator: C and Rust through JNI, C++ through FFM, in one app — see
  [`android/README.md`](android/README.md).
