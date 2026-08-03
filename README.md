# Konan Plugin

The **Konan Plugin** is a custom Gradle plugin that facilitates compiling C/C++ sources using the `run_konan` utility. It simplifies multi-platform native compilation for Kotlin Native projects, and can generate JNI bindings so the same C library is callable from JVM and Android.

> This plugin is based on awesome work by [aSemy](https://gist.github.com/aSemy) 🚀 

## Features
- Compiles C/C++ source files to `.o` object files.
- Supports linking object files into static libraries (`.a`).
- Works with all Kotlin Native targets, as `KonanTarget` enum constants.
- Generates runtime-free JNI bindings + a self-contained stub shared library (`jvmInterop`).
- Marshals `String`s and primitive arrays across the bridge, so a C API taking `const char*` or
  `float*` buffers is callable without an off-heap allocator on the Kotlin side.
- Auto-wires generated sources and per-ABI `jniLibs` into Android projects (AGP).
- Auto-detects the Kotlin/Native distribution, the JDK that supplies `jni.h`, and the compiler-rt
  builtins each target needs (NDK on Android, Xcode on macOS).
- Zero runtime dependencies (only requires Gradle API).
- Support for custom compiler arguments.

## Installation
1. Apply the plugin in your `build.gradle.kts`:
   ```kotlin
   plugins {
       id("io.github.lemcoder.konanplugin") version "1.2.0-alpha"
   }
   ```

2. Ensure a Kotlin/Native distribution is installed. It is found automatically via `$KONAN_HOME`, else
   the newest `~/.konan/kotlin-native-prebuilt-*`; override with `konanConfig.konanPath`.

## Configuration
The plugin provides one extension, `konanConfig`, with a nested `jvmInterop` block. Everything except
`targets` and `libName` has a convention, so most builds only declare those.

| Property                 | Type                | Default        | Description                                                        |
|--------------------------|---------------------|----------------|--------------------------------------------------------------------|
| `targets`                | `List<KonanTarget>` | —              | Targets to compile for. Use `targets(...)` or `androidTargets()`.   |
| `libName`                | `String`            | project name   | Static library base name (no `lib` prefix / `.a` suffix).           |
| `sourceDir`              | `String`            | `native`       | Directory containing C/C++ sources.                                |
| `headerDir`              | `String`            | `sourceDir`    | Include root passed as `-I`.                                       |
| `outputDir`              | `String`            | `build/native` | Root for the per-target `.a`s (`<outputDir>/<target>/lib<libName>.a`). |
| `konanPath`              | `String`            | auto-detected  | Root of the Kotlin/Native distribution.                            |
| `additionalCompilerArgs` | `List<String>`      | `[]`           | Appended to the default `-fno-sanitize=undefined`. Put `-std=…` here — C and C++ sources share one invocation. |

### `jvmInterop { }` (nested)
Generates JNI bridges from the headers and links a stub shared library per target. Every input is
derived from the enclosing block, so in practice only `packageName` is set — and on Android even that
defaults to the AGP `namespace`.

| Property                 | Type                | Default                         | Description                                          |
|--------------------------|---------------------|---------------------------------|------------------------------------------------------|
| `enabled`                | `Boolean`           | block declared, or Android target | Whether to generate JNI bindings.                  |
| `packageName`            | `String`            | Android `namespace`             | Kotlin package for the generated bindings.           |
| `headers`                | `List<String>`      | every `.h` under `headerDir`     | Header file names to bind.                          |
| `headerFilter`           | `String`            | the header names                | `.def` `headerFilter` glob.                          |
| `headerDir`              | `String`            | `konanConfig.headerDir`         | Include root holding the headers.                    |
| `targets`                | `List<KonanTarget>` | `konanConfig.targets`           | Targets to build the stub library for.               |
| `staticLibraryDir`       | `String`            | `konanConfig.outputDir`         | Root holding the per-target `.a`s to link.           |
| `staticLibraryName`      | `String`            | `konanConfig.libName`           | Static library base name to link.                    |
| `konanPath`              | `String`            | `konanConfig.konanPath`         | Root of the Kotlin/Native distribution.              |
| `jniHome`                | `String`            | auto-detected                   | JDK that ships `include/jni.h` (host targets only).  |
| `additionalCompilerArgs` | `List<String>`      | `[]`                            | Extra `-compiler-option` values for the generator.   |
| `additionalLinkerArgs`   | `List<String>`      | `[]`                            | Extra link arguments for every target.               |
| `targetLinkerArgs`       | `Map<KonanTarget, List<String>>` | `{}`               | Extra link arguments for one target; set via `linkerArgsFor(target, …)`. |

Platform-specific link arguments belong in `linkerArgsFor` — `-framework` reaches the Android linker
as an error if put in `additionalLinkerArgs`:

```kotlin
jvmInterop {
    linkerArgsFor(KonanTarget.MACOS_ARM64, "-lc++", "-framework", "Accelerate")
    linkerArgsFor(KonanTarget.ANDROID_ARM64, "$ndk/…/libc++_static.a")
}
```

A static library built with a current NDK needs *that* NDK's C++ runtime: konan bundles an old one,
and the plugin's auto-detected `-L` is appended last so an explicit path wins.

#### Generated bindings
One `external fun kniBridgeN(...)` per C function, in header order, each carrying its C-derived
signature as a doc comment. Parameters are marshalled by shape:

| C parameter                        | Kotlin parameter | Crosses as                                    |
|------------------------------------|------------------|------------------------------------------------|
| `const char*` input                | `String?`        | `GetStringUTFChars`                            |
| `char*` / `float*` / … buffer      | `ByteArray?` / `FloatArray?` / … | `Get<Type>ArrayElements` (writes copied back) |
| struct by value, struct return     | `ByteArray?`     | raw struct bytes                               |
| opaque handle, pointer to struct   | `Long`           | raw address                                    |

Functions returning `const char*` return the address; read it with the generated
`kniCString(ptr: Long): String?`. Struct arguments are raw bytes, so the caller writes the fields —
`ByteBuffer.order(ByteOrder.nativeOrder())` with the layout from the C header.

## Usage

### Static library only
```kotlin
import io.github.lemcoder.KonanTarget

konanConfig {
    targets(KonanTarget.LINUX_X64, KonanTarget.MINGW_X64, KonanTarget.MACOS_ARM64)
    libName.set("mylib")

    sourceDir.set("native/src")
    headerDir.set("native/include")
    outputDir.set("native/lib")

    additionalCompilerArgs.addAll("-O2", "-Wall")
}
```

Target names are also accepted as strings — `targets("linux_x64", "mingw_x64")` — and resolved to the
enum, so an unknown name fails at configuration time instead of during the clang invocation.

### Android, with JNI bindings
The JNI leg turns itself on for Android targets, and AGP wiring is automatic:

```kotlin
import io.github.lemcoder.KonanTarget

konanConfig {
    targets(KonanTarget.ANDROID_ARM64, KonanTarget.ANDROID_X64) // device + emulator
    libName.set("mymath")

    jvmInterop {
        packageName.set("example")   // omit to use the AGP namespace
    }
}
```

That is the whole configuration: headers are scanned from `sourceDir`, the `.a`s land in
`build/native/<target>/`, the stubs in `build/jvmInterop/jniLibs/<abi>/`, and the generated Kotlin
bridges become a source directory of every variant. See `examples/android`.

To opt out of the JNI leg while still targeting Android, set `jvmInterop { enabled.set(false) }`.

## Tasks
| Task                        | Description                                                             |
|-----------------------------|-------------------------------------------------------------------------|
| `runKonanClang`             | Compiles the sources to `.o` and links them into `lib<libName>.a` per target. |
| `generateJvmInterop`        | Generates the JNI `.c` stubs + runtime-free Kotlin bridges.              |
| `linkJvmInterop`            | Umbrella task: links the stub shared library for every target.           |
| `linkJvmInterop<Target>`    | Links the stub shared library for one target.                            |

On Android these are ordered ahead of `preBuild` and the Kotlin compile tasks automatically, so
`assembleDebug` alone runs the whole chain.

### Example
1. Place your C/C++ source files in `sourceDir` (default `native/`).
2. Place your `.h` headers in `headerDir` (defaults to `sourceDir`).
3. Run the build:
   ```bash
   ./gradlew runKonanClang
   ```

The compiled static libraries will be output to `outputDir`.

## Example Workflow
### Directory Structure
```plaintext
project-root/
├── build.gradle.kts
├── native/
│   ├── src/
│   │   ├── file1.c
│   ├── include/
│   │   ├── file1.h
│   └── lib/ (generated output)
└── settings.gradle.kts
```

### Output
After running the task:
```plaintext
native/lib/
├── linux_x64/
│   └── lib.a
├── mingw_x64/
│   └── lib.a
```

## Examples
- `examples/native` — Kotlin/Native consumer via standard `cinterop` (no JNI).
- `examples/jvm` — plain JVM app loading the stub from `java.library.path`.
- `examples/android` — Android app calling C through the generated bridges.
  `scripts/run-android.sh` publishes the plugin, builds, installs and launches it in one command.

## Migrating from 1.1.x
- `targets` takes `KonanTarget` constants instead of strings: `targets(KonanTarget.ANDROID_ARM64)`, or
  `targets("android_arm64")` to keep using names.
- The top-level `jvmInterop { }` block moved inside `konanConfig { }`.
- `jvmInterop`'s `targets`, `headers`, `headerDir`, `staticLibraryDir`, `staticLibraryName` and
  `konanPath` now default from `konanConfig` and can usually be deleted.
- `konanPath` is auto-detected; the manual `~/.konan` lookup in build scripts can go.
- The only default clang argument is `-fno-sanitize=undefined`. 1.1.x also passed `-std=c99`, which
  made any `.cpp` in the source dir a hard error; add it back via `additionalCompilerArgs` if your C
  sources rely on it. Earlier versions additionally passed `-DJPH_CROSS_PLATFORM_DETERMINISTIC
  -DJPH_ENABLE_ASSERTS`; those go through the same property.

## License
This project is licensed under the Apache 2.0 License. For more details, see the `LICENSE` file.

Happy coding! 😊
