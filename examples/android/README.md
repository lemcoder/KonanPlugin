# Android example

Shows the plugin producing the Android JNI artifacts for a C library. This project deliberately has
**no Android Gradle Plugin** so it builds anywhere — it just generates the artifacts an Android module
consumes.

## Build

```bash
../../gradlew -p . assembleAndroidJni
```

Produces, per ABI:
- `build/jvmInterop/jniLibs/arm64-v8a/libexamplestubs.so`
- `build/jvmInterop/jniLibs/x86_64/libexamplestubs.so`

and the runtime-free Kotlin bridges in `build/generated/jvmInterop/kotlin/example/example.kt`:

```kotlin
@file:JvmName("example")
package example
private val nativeLibrary: Unit = System.loadLibrary("examplestubs")
/** C: my_add(a: Int, b: Int): Int */
external fun kniBridge0(p0: Int, p1: Int): Int
/** C: my_scale(x: Double): Double */
external fun kniBridge1(p0: Double): Double
```

The `.so` is a self-contained shared library — the JNI `examplestubs.c` compiled and statically linked
against the cross-compiled `libmymath.a`. Verify it:

```bash
file build/jvmInterop/jniLibs/arm64-v8a/libexamplestubs.so
# ELF 64-bit LSB shared object, ARM aarch64 ...
$KONAN_NDK/bin/aarch64-linux-android-nm -D build/jvmInterop/jniLibs/arm64-v8a/libexamplestubs.so | grep kniBridge
# T Java_example_example_kniBridge0  ...
```

## Wire it into a real Android module

In your `:app` (or library) `build.gradle.kts`:

```kotlin
android {
    sourceSets["main"].jniLibs.srcDir(
        rootProject.file("examples/android/build/jvmInterop/jniLibs")
    )
    sourceSets["main"].kotlin.srcDir(
        rootProject.file("examples/android/build/generated/jvmInterop/kotlin")
    )
}
```

AGP packages every `jniLibs/<abi>/*.so` into the APK and extracts the matching ABI at install time;
`System.loadLibrary("examplestubs")` then resolves it. No `java.library.path` needed on Android.

## The Kotlin you write (tier 2)

The generated bridges are the low-level tier. You write the idiomatic API — and in a multiplatform
project, the `expect`/`actual`:

```kotlin
// commonMain
expect fun add(a: Int, b: Int): Int

// androidMain  (over the JNI bridges)
actual fun add(a: Int, b: Int): Int = example.kniBridge0(a, b)

// nativeMain   (over the K/N cinterop bindings — see ../native)
actual fun add(a: Int, b: Int): Int = mymath.my_add(a, b)
```

For pointer/string parameters the bridge takes a raw address (`Long`); convert in your `actual`
(e.g. a direct `ByteBuffer` + `GetDirectBufferAddress`, or a small `malloc`/`free` helper).
