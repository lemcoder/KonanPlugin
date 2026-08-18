# Konan Plugin

The **Konan Plugin** is a custom Gradle plugin that facilitates compiling C/C++ sources using the `run_konan` utility. It simplifies multi-platform native compilation for Kotlin Native projects, and can generate JNI bindings so the same C library is callable from JVM and Android.

> This plugin is based on awesome work by [aSemy](https://gist.github.com/aSemy) 🚀 

## Features
- Compiles C/C++ source files to `.o` object files.
- Supports linking object files into static libraries (`.a`).
- Works with all Kotlin Native targets, using Kotlin/Native's own `KonanTarget` — pass
  `kotlinNativeTarget.konanTarget` straight in, and new targets arrive with the Kotlin version.
- Generates runtime-free JNI bindings + a self-contained stub shared library (`jvmInterops`).
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
`konanConfig` compiles, and nothing else — the Kotlin/Native side of the plugin. Everything except
`targets` and `libName` has a convention, so most builds only declare those. JNI bindings are a
separate declaration, `jvmInterops`, described below.

| Property                 | Type                | Default        | Description                                                        |
|--------------------------|---------------------|----------------|--------------------------------------------------------------------|
| `targets`                | `List<KonanTarget>` | —              | Targets to compile for. Use `targets(...)` or `androidTargets()`.   |
| `libName`                | `String`            | project name   | Static library base name (no `lib` prefix / `.a` suffix).           |
| `sourceDir`              | `String`            | `native`       | Directory containing C/C++ sources.                                |
| `headerDir`              | `String`            | `sourceDir`    | Include root passed as `-I`.                                       |
| `outputDir`              | `String`            | `build/native` | Root for the per-target `.a`s (`<outputDir>/<target>/lib<libName>.a`). |
| `konanPath`              | `String`            | auto-detected  | Root of the Kotlin/Native distribution.                            |
| `additionalCompilerArgs` | `List<String>`      | `[]`           | Appended to the default `-fno-sanitize=undefined`. Put `-std=…` here — C and C++ sources share one invocation. |

## `jvmInterops { }` — JNI bindings

Declared like a cinterop, on the compilation it belongs to, and driven by the same `.def` the native
targets bind:

```kotlin
import io.github.lemcoder.interop.jvmInterops

kotlin {
    jvm()

    jvm().compilations["main"].jvmInterops {
        create("mymath") {
            // defFile defaults to src/nativeInterop/cinterop/mymath.def
            includeDirs.from(file("native"))
        }
    }
}
```

`KotlinCompilation` is not `ExtensionAware`, so this is an extension function rather than a block the
Kotlin plugin owns — the `import` is the only visible difference. A module with no Kotlin compilation
(an AGP application or library) declares interops on the project instead, and the generated bindings
and per-ABI `jniLibs` are wired into the variants:

```kotlin
jvmInterops {
    create("mymath") {
        defFile(project.file("src/main/nativeInterop/mymath.def"))
        targets.set(listOf(KonanTarget.ANDROID_ARM64, KonanTarget.ANDROID_X64))
    }
}
```

| Property                 | Type                | Default            | Description                                          |
|--------------------------|---------------------|--------------------|------------------------------------------------------|
| `defFile`                | `File`              | `src/nativeInterop/cinterop/<name>.def` | The cinterop `.def` to bind — cinterop's own default location, so one file serves both legs. |
| `packageName`            | `String`            | the def's `package`| Kotlin package for the generated bindings.           |
| `visibility`             | `BindingVisibility` | `INTERNAL`         | `PUBLIC` when the idiomatic wrapper lives in another module. |
| `targets`                | `List<KonanTarget>` | the build host     | Targets to link the JNI library for.                 |
| `library`                | `File`              | see below          | Archive to link, overriding the def.                 |
| `includeDirs`            | `FileCollection`    | the def's directory| Extra include roots.                                 |
| `jniHome`                | `String`            | auto-detected      | A JDK shipping `include/jni.h`.                      |
| `additionalCompilerArgs` | `List<String>`      | `[]`               | Extra `-compiler-option` values for the generator.   |
| `additionalLinkerArgs`   | `List<String>`      | `[]`               | Extra link arguments for every target.               |
| `targetLinkerArgs`       | `Map<…>`            | `{}`               | Per-target link arguments; set via `linkerArgsFor(target, …)`. |

### How far it goes depends on the library

Same question as cinterop's `staticLibraries`, one step further along: cinterop embeds the archive
into the klib, and the JVM leg has to *link* a shared library, because a JVM can only `dlopen`.

- **Something to link** — `library(...)`, the def's `staticLibraries`, or whatever `konanConfig`
  compiled for that target — and you get `lib<package>stubs.{so,dylib,dll}` per target.
- **Nothing to link** and it stops after the Kotlin bindings and the `.c` stub, for another build
  system to compile. This is the mode for a native library owned by CMake, Bazel or a vendor: linking
  an archive built by a foreign toolchain with konan's linker is what produces mismatched C++
  runtimes and missing compiler-rt.

Preferring the `konanConfig` output over a def entry keeps the def portable — naming the archive in
it would hardcode a target directory.

### Letting another build link it — `externalNativeBuild`

When the native library belongs to a CMake project, the plugin can drive that build instead of
linking anything itself, the way AGP's `externalNativeBuild` hands a module's native code to CMake:

```kotlin
create("koinference") {
    packageName.set("com.example.native")

    externalNativeBuild {
        cmake {
            path.set(file("native/CMakeLists.txt"))
            preset.set("macosArm64")          // or buildDirectory, for preset-less projects
            targets.add("koinference-jni")
            arguments.add("-DMY_OWN_SWITCH=ON")
        }
    }
}
```

No link task is registered; `cmakeConfigure<Name>` and `cmakeBuild<Name>` take its place, ordered
after generation, and `linkJvmInterop<Name>` depends on the build so "make the JNI library exist"
means the same thing either way.

Android needs a library per ABI rather than one for the host, so declare them:

```kotlin
externalNativeBuild {
    cmake {
        path.set(file("native/CMakeLists.txt"))
        targets.add("koinference-jni")

        abi("arm64-v8a") { preset.set("androidNativeArm64") }
        abi("x86_64") { preset.set("androidNativeX64") }
    }
}
```

Each ABI gets its own configure/build pair, the libraries land in `jniLibs/<abi>/`, and the plugin
wires that directory into the Android variants so they are packaged into the AAR. The Android
toolchain comes from the NDK the plugin finds — `ANDROID_NDK_HOME`, then `ndk.dir` or `sdk.dir` in
`local.properties`, then `ANDROID_HOME` — unless the build passes `-DCMAKE_TOOLCHAIN_FILE` itself.
`platform.set(26)` inside an `abi` block chooses the minimum API; it is `android-21` otherwise. The plugin sets
`CMAKE_LIBRARY_OUTPUT_DIRECTORY`, so the CMakeLists needs no knowledge of the layout.

The plugin supplies what only it knows, as cache entries your `CMakeLists.txt` reads:

| variable | meaning |
|---|---|
| `KONAN_JNI_STUB_DIR` | directory holding the generated `.c` stub |
| `KONAN_JNI_LIB_NAME` | the name the generated bindings will `System.loadLibrary` |
| `KONAN_JNI_INCLUDE_DIRS` | include roots for `jni.h` and its platform header, `;`-separated |

It also points `JAVA_HOME` at a JDK that ships `include/jni.h` — the one running Gradle often does
not, since IDE-bundled JBRs strip the headers — and locates `cmake` itself, because the Gradle daemon
does not inherit a login shell's PATH.

```cmake
set(KONAN_JNI_STUB_DIR "" CACHE PATH "")
set(KONAN_JNI_LIB_NAME "" CACHE STRING "")
set(KONAN_JNI_INCLUDE_DIRS "" CACHE STRING "")

file(GLOB JNI_SOURCES "${KONAN_JNI_STUB_DIR}/*.c")
add_library(mylib-jni SHARED ${JNI_SOURCES})
set_target_properties(mylib-jni PROPERTIES OUTPUT_NAME "${KONAN_JNI_LIB_NAME}")
target_include_directories(mylib-jni PRIVATE ${KONAN_JNI_INCLUDE_DIRS})
target_link_libraries(mylib-jni PRIVATE mylib)   # frameworks, libc++ etc. arrive transitively
```

`find_package(JNI)` would work too, but it is free to pick a different JDK from the one the plugin
chose for the bindings; the cache variable is the JDK that generated them.

### Generated bindings
One `external fun kniBridgeN(...)` per C function, in header order, each carrying its C-derived
signature as a doc comment. Parameters are marshalled by shape:

| C parameter                        | Kotlin parameter | Crosses as                                    |
|------------------------------------|------------------|------------------------------------------------|
| `const char*` input                | `String?`        | `GetStringUTFChars`                            |
| `char*` / `float*` / … buffer      | `ByteArray?` / `FloatArray?` / … | `Get<Type>ArrayElements` (writes copied back) |
| struct by value, struct return     | `ByteArray?`     | raw struct bytes                               |
| opaque handle, pointer to struct   | `Long`           | raw address                                    |

The bridges are `internal` by default — they are an implementation detail of the module that writes
the idiomatic API over them, and this keeps `kniBridge0…N` out of its published surface. Each carries
`@JvmName`, because Kotlin mangles internal functions on the JVM and JNI resolves the symbol from the
unmangled method name. Set `visibility.set(BindingVisibility.PUBLIC)` if the wrapper is in a
different Gradle module.

Functions returning `const char*` return the address; read it with the generated
`kniCString(ptr: Long): String?`. Struct arguments are raw bytes, so the caller writes the fields —
`ByteBuffer.order(ByteOrder.nativeOrder())` with the layout from the C header.

Platform-specific link arguments go in `linkerArgsFor`; `-framework` in `additionalLinkerArgs` would
reach the Android linker, which rejects it.

## Usage

### Static library only
```kotlin
import org.jetbrains.kotlin.konan.target.KonanTarget

konanConfig {
    targets(KonanTarget.LINUX_X64, KonanTarget.MINGW_X64, KonanTarget.MACOS_ARM64)
    libName.set("mylib")

    sourceDir.set("native/src")
    headerDir.set("native/include")
    outputDir.set("native/lib")

    additionalCompilerArgs.addAll("-O2", "-Wall")
}
```

Target names are also accepted as strings — `targets("linux_x64", "mingw_x64")` — and resolved
against `KonanTarget.predefinedTargets`, so an unknown name fails at configuration time instead of
during the clang invocation. `hostKonanTarget()` is the build host; `androidKonanTargets()` is every
Android ABI.

### Android, with JNI bindings
AGP modules declare interops on the project; AGP wiring is automatic:

```kotlin
import org.jetbrains.kotlin.konan.target.KonanTarget
import io.github.lemcoder.interop.jvmInterops

konanConfig {
    targets(KonanTarget.ANDROID_ARM64, KonanTarget.ANDROID_X64) // device + emulator
    libName.set("mymath")
}

jvmInterops {
    create("mymath") {
        defFile(project.file("src/main/nativeInterop/mymath.def"))
        includeDirs.from(file("native"))
        targets.set(listOf(KonanTarget.ANDROID_ARM64, KonanTarget.ANDROID_X64))
    }
}
```

That is the whole configuration: the `.a`s land in `build/native/<target>/`, the stubs in
`build/jvmInterop/<name>/jniLibs/<abi>/`, and the generated Kotlin bridges become a source directory
of every variant. See `examples/android`.

## Tasks
| Task                        | Description                                                             |
|-----------------------------|-------------------------------------------------------------------------|
| `runKonanClang`                  | Compiles the sources to `.o` and links them into `lib<libName>.a` per target. |
| `generateJvmInterop<Name>`       | Generates the JNI `.c` stub + runtime-free Kotlin bridges for one interop. |
| `linkJvmInterop<Name>`           | Umbrella task: links that interop for every target.                      |
| `linkJvmInterop<Name><Target>`   | Links one interop for one target.                                        |
| `cmakeConfigure<Name>`           | Configures an interop's external CMake build.                            |
| `cmakeBuild<Name>`               | Runs it, replacing the plugin's own link.                                |

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

## Migrating to 1.2.0

Alpha, and the interop DSL moved wholesale — there is no compatibility shim.

- `konanConfig { jvmInterop { … } }` is gone. Declare `jvmInterops { create("name") { … } }` on a
  Kotlin compilation, or on the project for AGP modules. `konanConfig` is compilation only now,
  mirroring the Kotlin/Native side.
- Bindings come from a `.def`, not from scanned headers: `defFile(...)` replaces `headers`,
  `headerFilter` and `headerDir`. The same def can drive cinterop.
- `staticLibraryDir` / `staticLibraryName` are gone; the archive is the def's `staticLibraries`,
  an explicit `library(...)`, or `konanConfig`'s own output for that target.
- Task names carry the interop name: `generateJvmInteropMymath`, `linkJvmInteropMymathMacos_arm64`.
- Targets are `org.jetbrains.kotlin.konan.target.KonanTarget`, not the plugin's own enum. Change the
  import; `KonanTarget.host()` becomes `hostKonanTarget()` and `KonanTarget.ANDROID` becomes
  `androidKonanTargets()`.
- `targets(...)` on `konanConfig` no longer implies anything about JNI; an interop declares its own.
- The default clang arguments are `-fno-sanitize=undefined -fPIC`. 1.1.x also passed `-std=c99`,
  which made any `.cpp` in the source dir a hard error; add it via `additionalCompilerArgs` if your
  C sources rely on it.

## License
This project is licensed under the Apache 2.0 License. For more details, see the `LICENSE` file.

Happy coding! 😊
