# Kotlin Multiplatform: iOS, macOS, Linux and Android

One C library (`shared/native/mymath.c`) behind one common Kotlin API, on iOS, macOS, Linux and Android.

```
shared/                     Kotlin Multiplatform library: iosArm64, iosSimulatorArm64, macosArm64, linuxX64, android
  native/mymath.{c,h}       the C library
  src/nativeInterop/cinterop/mymath.def
  src/commonMain            expect fun add / scale
  src/nativeMain            actuals over KGP's cinterop binding, for every Kotlin/Native target;
                            main() for the macOS and Linux executables
  src/androidMain           actuals over the generated JNI binding
  src/commonTest            calls into C; runs on the iOS simulator, macOS and Linux
androidApp/                 Android app over :shared (AGP 9 keeps apps out of multiplatform modules)
```

`shared/build.gradle.kts` declares the library and its binding once:

```kotlin
konan {
    config { konanHome.set(kotlinNativeDistribution()) }
    val mymath = libraries.create("mymath") {
        targets(
            NativeTarget.IOS_ARM64, NativeTarget.IOS_SIMULATOR_ARM64,
            NativeTarget.MACOS_ARM64, NativeTarget.LINUX_X64,
            NativeTarget.ANDROID_ARM64, NativeTarget.ANDROID_X64,
        )
    }
    bindings {
        create("mymath") {
            includeDirs.from("native")
            library(mymath)
        }
    }
}
```

and each side gets its own binding, in the `.def`'s package `com.example.mymath`:

| Target | Binding | The C library |
|---|---|---|
| `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `linuxX64` | KGP's cinterop klib (`cinteropMymath<Target>`): public, pointers as `CPointer`, needs `@OptIn(ExperimentalForeignApi::class)` | `libmymath.a` embedded in the klib, so linked into `Shared.framework` and the executables |
| `android` | generated JNI bindings in `androidMain` (`generateJniMymath`): `internal`, arrays and buffers marshalled | `libcomexamplemymathstubs.so` per ABI (`linkJniMymath…`), in the AAR's `jniLibs` |

The Kotlin/Native `actual`s live once, in `nativeMain`. A source set shared by several targets sees the
binding only when KGP commonizes it, so `gradle.properties` sets
`kotlin.mpp.enableCInteropCommonization=true`; without it `compileNativeMainKotlinMetadata` (and the IDE)
fail with *"Unresolved reference 'mymath'"*, while each target's own compilation still works.

Common code cannot see either binding, so `commonMain` declares `expect fun add(a: Int, b: Int): Int` and
each platform's `actual` calls `com.example.mymath.my_add`. Functions on numbers read the same on both
sides; pointers do not — `memScoped`/`CPointer` on iOS, arrays and `ByteBuffer`s on Android.

## Run

Builds the plugin from source (`includeBuild("../..")`), with the repository's wrapper:

```bash
# iOS: the common tests, calling into C on a simulator
../../gradlew -p . :shared:iosSimulatorArm64Test

# iOS: the framework an Xcode project embeds
../../gradlew -p . :shared:linkDebugFrameworkIosSimulatorArm64

# macOS: the tests and the executable
../../gradlew -p . :shared:macosArm64Test :shared:runDebugExecutableMacosArm64

# Linux: cross-compiled and linked on macOS — Kotlin/Native brings the glibc sysroot; run the ELF on Linux
../../gradlew -p . :shared:linkDebugExecutableLinuxX64 :shared:linkDebugTestLinuxX64
docker run --rm --platform linux/amd64 -v "$PWD/shared/build/bin/linuxX64":/kn ubuntu:22.04 \
  sh -c '/kn/debugExecutable/shared.kexe && /kn/debugTest/test.kexe'

# Android: the app, on a device or emulator
../../gradlew -p . :androidApp:installDebug
```

Needs Xcode for the iOS and macOS targets, an Android SDK (`local.properties` or `ANDROID_HOME`), and the
NDK the Android Gradle plugin uses installed (SDK Manager → NDK (Side by side)); `ANDROID_NDK_HOME` or
`konan.config.androidNdk` choose another.

From Swift, the common API is in the framework:

```swift
import Shared

let sum = MyMath_iosKt.add(a: 2, b: 3)   // 5, computed in C
```
