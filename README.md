# Konan Plugin

The **Konan Plugin** compiles C/C++ libraries with Kotlin/Native's toolchain and binds them for every Kotlin consumer from one cinterop `.def`: Kotlin/Native, the JVM and Android.

> This plugin is based on awesome work by [aSemy](https://gist.github.com/aSemy) 🚀 

## Features
- Builds C/C++ libraries into a static archive per target — with Kotlin/Native's clang (the Android NDK's
  for Android), or with the project's own build: CMake, Make, Cargo for a Rust crate, `zig build`, or any
  build system as a list of commands; or takes archives built elsewhere. A build that leaves several
  archives (a CMake project with dependencies) is merged into one.
- Binds one cinterop `.def` for every kind of Kotlin consumer the project has:
  - **Kotlin/Native** targets through KGP's own cinterop, the library's archive embedded in the klib;
  - **JVM** targets and **Android** through generated JNI bindings and a stub library per target, with
    strings, primitive buffers and structs marshalled;
  - the **JVM 22+** through jextract's FFM bindings, on request — and **Android** too, over
    [PanamaPort](https://github.com/vova7878/PanamaPort)'s backport of the FFM API.

  One project can mix them: several libraries, each bound through JNI or FFM, in plain JVM, Android and
  Kotlin Multiplatform modules. JavaScript and WebAssembly targets get no bindings.
- Wires generated sources, `jniLibs`, jar resources and task order into Kotlin and Android builds; can
  hand the JNI library, or the library FFM loads, to the project's own build instead.
- Builds every Android library with the Android NDK — the one the Android Gradle plugin uses, or one you
  name — with its C++ runtime linked in, so C++ and Rust libraries load on a device.
- Has its own target type, `NativeTarget`, one entry per Kotlin/Native target; a KGP target converts
  with `NativeTarget.of(kotlinNativeTarget.konanTarget)`. The plugin's API does not depend on the
  Kotlin compiler's.
- Takes every machine path from one place, [`config { }`](#config--) — nothing is searched for. Ships
  `jni.h`, so no JDK is needed to link JNI.

## Installation
1. Apply the plugin in your `build.gradle.kts`:
   ```kotlin
   plugins {
       id("io.github.lemcoder.konanplugin") version "2.0.0-alpha01"
   }
   ```

2. Tell it where the Kotlin/Native distribution is — nothing is searched for (see [`config { }`](#config--)):
   ```properties
   # ~/.gradle/gradle.properties, for every build on this machine
   konan.config.konanHome=/Users/me/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20
   ```
   or `konan { config { konanHome.set(kotlinNativeDistribution()) } }`, which names where the Kotlin
   plugin installs your project's Kotlin/Native version — see [Kotlin versions](#kotlin-versions).

3. For Android targets, install an NDK (r23 or newer). In an Android module the plugin uses the one the
   Android Gradle plugin does — `android { ndkVersion = "…" }`, else AGP's default — so installing it
   (SDK Manager → SDK Tools → NDK (Side by side)) is enough; elsewhere, or to choose another, set
   `konan.config.androidNdk` or `ANDROID_NDK_HOME`. See [Android: the NDK](#android-the-ndk-and-its-c-runtime).

### Kotlin versions
JNI and FFM bindings are generated from cinterop's own C model, by code that runs inside the
Kotlin/Native distribution `konanHome` names and calls its compiler. The plugin carries that code for
**every Kotlin/Native release from 2.0.0 to 2.4.21** and runs the one matching `konanHome`; any other
version fails, listing the supported ones.

`kotlinNativeDistribution()` names the distribution of your project's Kotlin version, the one the Kotlin
plugin downloads for a native target:

```kotlin
konan { config { konanHome.set(kotlinNativeDistribution()) } }   // ~/.konan/kotlin-native-prebuilt-<host>-<your Kotlin version>
```

A project with no native target never makes the Kotlin plugin download it: build any Kotlin Multiplatform
project with a native target on that version once, or unpack `kotlin-native-prebuilt-<host>-<version>`
from [Maven Central](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-native-prebuilt/).
Kotlin/Native bindings (KGP's cinterop, the klib) always use your project's Kotlin, whatever `konanHome` is.

The plugin itself is tested on Gradle 8.7 with Kotlin 2.0.0 and on Gradle 9.8 with Kotlin 2.4.20.

## Configuration
Everything lives in one block: the **libraries** the project builds, and the **bindings** that reach
them from Kotlin.

```kotlin
import io.github.lemcoder.model.NativeTarget

konan {
    val mymath = libraries.create("mymath") {
        targets(NativeTarget.MACOS_ARM64, NativeTarget.ANDROID_ARM64, NativeTarget.ANDROID_X64)
    }
    bindings {
        create("mymath") {
            library(mymath)
            packageName.set("example")
        }
    }
}
```

A binding is consumed wherever the project's Kotlin targets need it, with nothing more to declare:

| The project has | It gets | Through |
|---|---|---|
| Kotlin/Native targets (`kotlin("multiplatform")`) | a cinterop klib per target the library is built for | KGP's own `cinterops {}`, with the library's archive embedded |
| `kotlin("jvm")`, multiplatform `jvm()` or Android targets, or an Android app | JNI bindings in the main sources, a JNI library per target | the plugin's JNI generator and link |
| `ffm()` on the binding, in a `kotlin("jvm")` project or for a multiplatform `jvm()` target | FFM bindings in place of JNI, the library as a shared library | jextract |
| `ffm()` on the binding, in an Android module or for a multiplatform Android target | FFM bindings on PanamaPort in place of JNI, the library as `.so` per 64-bit ABI | jextract |

**Calling the cinterop binding from a shared source set** — `nativeMain`, `appleMain`, `iosMain` — needs KGP
to commonize it: set `kotlin.mpp.enableCInteropCommonization=true` in `gradle.properties`. Without it,
each target's own source set sees the binding, but the shared one fails with *"Unresolved reference"*.

**JavaScript and WebAssembly targets get no bindings.** A multiplatform project with `js()`, `wasmJs()` or
`wasmWasi()` targets builds as usual, but the plugin leaves those targets alone: nothing is generated or
linked for them, and no error is reported. Declare the API you need as an `expect` in common code, let
the JVM, Android and Kotlin/Native `actual`s call the bindings, and implement the JS and Wasm `actual`s
yourself — in Kotlin, or over your own build of the library for the web.

| Property          | Type  | Default | Description |
|-------------------|-------|---------|-------------|
| `androidApiLevel` | `Int` | the module's lowest `minSdk`, else 21 | The API level Android libraries are compiled and linked for, with the NDK's matching clang driver, so platform libraries such as `libaaudio` or `libcamera2ndk` link. A level outside the NDK's range uses its nearest. |
| `config { }`      |       |         | The paths the plugin needs from the machine; below. |

### `config { }`
The machine's paths: the Kotlin/Native distribution and the programs the plugin runs. They are the
plugin's only source — nothing is looked up on the `PATH` or in install directories. Each field is read
from the build script, else the Gradle property `konan.config.<field>` (the build's `gradle.properties`,
`~/.gradle/gradle.properties`, or `-P`), else its environment variable:

| Field       | Gradle property            | Environment variable | Needed by |
|-------------|----------------------------|----------------------|-----------|
| `konanHome` | `konan.config.konanHome`   | `KONAN_HOME`         | anything that compiles, links or generates bindings |
| `jextract`  | `konan.config.jextract`    | `KONAN_JEXTRACT`     | `ffm()` |
| `cmake`     | `konan.config.cmake`       | `KONAN_CMAKE`        | `cmake { }` |
| `make`      | `konan.config.make`        | `KONAN_MAKE`         | `make { }` |
| `cargo`     | `konan.config.cargo`       | `KONAN_CARGO`        | `cargo { }` |
| `zig`       | `konan.config.zig`         | `KONAN_ZIG`          | `zig { }` |
| `androidNdk` | `konan.config.androidNdk` | `ANDROID_NDK_HOME`   | every Android target: the plugin's own compiles and links, and external builds on `AUTO` (below). In an Android module, last of all the NDK the Android Gradle plugin uses |

```kotlin
konan {
    config {
        konanHome.set(kotlinNativeDistribution())          // ~/.konan/kotlin-native-prebuilt-<host>-<Kotlin version>
        jextract.set(file("/opt/jextract-25/bin/jextract"))
    }
}
```

Machine paths belong in `~/.gradle/gradle.properties` rather than a shared build script:

```properties
konan.config.jextract=/Users/me/opt/jextract-25/bin/jextract
konan.config.cmake=/opt/homebrew/bin/cmake
```

Values are absolute paths. Every field is optional until a task that needs it runs; that task then fails
naming the field and the three ways to set it:

```
konan.config.jextract is not set — :generateFfmAndroidAaudio needs it.
Set it to an absolute path in one of these; the first one set wins:
  1. build script         konan { config { jextract.set(file("/path/to/jextract")) } }
  2. gradle.properties    konan.config.jextract=/path/to/jextract
                          (~/.gradle/gradle.properties sets it for every build on this machine)
  3. environment          KONAN_JEXTRACT=/path/to/jextract
```

`gradlew konanConfig` prints every field, where its value comes from, and which tasks need it.

### `libraries { }`
A C/C++ library built to `<outputDirectory>/<target>/lib<name>.a` for each target: by Kotlin/Native's
clang from `sourceDirectory`, by Zig, or by any other build system. Bindings see the same archives
either way.

| Property          | Type                | Default                          | Description |
|-------------------|---------------------|----------------------------------|-------------|
| `targets`         | `List<NativeTarget>`| —                                | Targets to build for. `targets(...)` takes `NativeTarget`s; `NativeTarget.host` is the build host, `androidTargets()` every Android ABI. |
| `sourceDirectory` | `Directory`         | `native/`                        | The C/C++ sources; also an include root. |
| `includeDirs`     | `FileCollection`    | —                                | Further include roots. |
| `compilerOptions` | `List<String>`      | —                                | Appended to `-fno-sanitize=undefined -fPIC`. Put `-std=…` here — C and C++ sources share one invocation. |
| `outputDirectory` | `Directory`         | `build/konan/libraries/<name>`   | Root of the per-target archives; `archive(target)` gives one. |
| `zig { }`         |                     | —                                | Build with the project's `build.zig` instead; below. |
| `external { }`    |                     | —                                | Build with any other build system, as commands; below. |
| `cmake { }`       |                     | —                                | Build with the project's CMakeLists; below. |
| `cargo { }`       |                     | —                                | Build a Rust crate; below. |
| `make { }`        |                     | —                                | Build with the project's Makefile; below. |
| `prebuilt { }`    |                     | —                                | Use archives built elsewhere; below. |

#### Building with Zig — `zig { }`
```kotlin
create("mymath") {
    targets(NativeTarget.MACOS_ARM64, NativeTarget.LINUX_X64, NativeTarget.MINGW_X64)
    zig { }   // runs native/build.zig once per target
}
```

`zigBuild<Name><Target>` runs `zig build` once per target, with `-Dtarget` set to the Zig triple matching Kotlin/Native's
sysroot (glibc 2.19 on Linux, macOS 12.0, iOS 14.0), and its static library is copied to where the
plugin's own build would put it — Windows' `mymath.lib` included. The `build.zig` must install a static
library (`b.addLibrary(.{ .linkage = .static, … })`).

| Property           | Type           | Default             | Description |
|--------------------|----------------|---------------------|-------------|
| `projectDirectory` | `Directory`    | `sourceDirectory`   | Where `build.zig` is. |
| `optimize`         | `ZigOptimize`  | `RELEASE_FAST`      | `-Doptimize`. Zig's `DEBUG` and `RELEASE_SAFE` compile C with UBSan traps, whose runtime the Kotlin/Native and JNI links do not have. |
| `arguments`        | `List<String>` | —                   | Further `zig build` arguments. |
| `artifactName`     | `String`       | the library's name  | The static library `build.zig` installs. |

Android targets need a libc file for the NDK's bionic, which Zig does not bundle.

#### Building with CMake — `cmake { }`
```kotlin
create("mymath") {
    targets(NativeTarget.host, NativeTarget.LINUX_X64, NativeTarget.ANDROID_ARM64)
    cmake { }   // native/CMakeLists.txt: add_library(${KONAN_LIB_NAME} STATIC …)
}
```

The same block as `jni { cmake { } }`, below, building the library's archive: `cmakeBuild<Name><Target>`
configures and builds per target, with the contract as cache entries and `CMAKE_ARCHIVE_OUTPUT_DIRECTORY`
set to where the archive must land. Cross-compiled targets get Kotlin/Native's toolchain file, so the
Linux and Android archives above build on a Mac with no NDK or sysroot of your own. `path` defaults to
`sourceDirectory/CMakeLists.txt`.

#### Building a Rust crate — `cargo { }`
```kotlin
create("mymath") {
    targets(NativeTarget.host, NativeTarget.ANDROID_ARM64)
    cargo { }   // native/Cargo.toml, crate-type = ["staticlib"]
}
```

`cargoBuild<Name><Target>` runs `cargo build --lib --target <rust triple> --release` per target into
`build/konan/libraries/<name>/cargo`, and its `lib<crate>.a` becomes the library's archive: bindings
written against a C header for the crate's `extern "C"` functions (cbindgen writes one) bind it like any
C library, for every consumer. Each target's standard library must be installed (`rustup target add
aarch64-linux-android`). Crates with C code get Kotlin/Native's compilers as `CC_<triple>`,
`CXX_<triple>` and `AR_<triple>` — what the `cc` crate reads for the target, leaving build scripts on
the host's. The plugin's Android links carry the NDK's unwinder, which Rust's std needs; keep
`panic = "abort"` all the same, since unwinding out of an `extern "C"` function is undefined behaviour.

| Property     | Type                | Default                  | Description |
|--------------|---------------------|--------------------------|-------------|
| `manifest`   | `File`              | `sourceDirectory/Cargo.toml` | The crate. |
| `crateName`  | `String`            | the library's name       | As `Cargo.toml` spells it; `-` read as `_` in the archive name. |
| `profile`    | `CargoProfile`      | `RELEASE`                | `DEBUG` or `RELEASE`. |
| `arguments`  | `List<String>`      | —                        | Further `cargo build` arguments, e.g. `--features`. |
| `toolchain`  | `ExternalToolchain` | `AUTO`                   | Whose C compilers C dependencies get. |

#### Building with Make — `make { }`
```kotlin
create("mymath") {
    targets(NativeTarget.LINUX_X64, NativeTarget.ANDROID_ARM64)
    make { directory.set(file("native")) }   // make -C native -j<n> CC=… CXX=… AR=… KONAN_…=…
}
```

`makeBuild<Name><Target>` runs make once per target. The compilers and the contract go on make's command
line, where they win over the Makefile's own assignments, so a Makefile written against `$(CC)`, `$(AR)`
and `$(KONAN_OUTPUT_DIR)` cross-compiles unchanged:

```make
OUT := $(KONAN_OUTPUT_DIR)
all:
	$(CC) -c -fPIC mymath.c -o $(OUT)/mymath.o
	$(AR) rcs $(OUT)/lib$(KONAN_LIB_NAME).a $(OUT)/mymath.o
```

Every target builds in the same directory: keep intermediate files per target, as above, or a second
target reuses the first one's objects.

| Property           | Type                | Default            | Description |
|--------------------|---------------------|--------------------|-------------|
| `directory`        | `Directory`         | —                  | Where make runs (`-C`). Required. |
| `makefile`         | `File`              | `Makefile`         | `-f`. |
| `buildTargets`     | `List<String>`      | the default goal   | Make targets. |
| `arguments`        | `List<String>`      | —                  | Further arguments; `argumentsFor(target, …)` for one target. |
| `jobs`             | `Int`               | processors         | `-j`. |
| `toolchain`        | `ExternalToolchain` | `AUTO`             | Whose compilers; below. |
| `sources`, `producedLibrary(target, file)` | | —            | As for `external { }`. |

`jni { make { } }` and `ffm { make { } }` take the same block, for the libraries those load.

#### Kotlin/Native's compilers for any build — `toolchain`
Cross-compiling with your own build needs a compiler and sysroot per target. The plugin hands over
Kotlin/Native's — the ones its own builds use, so the archives match Kotlin/Native's ABI (glibc 2.19 on
Linux, its NDK sysroot on Android):

| `toolchain`   | Meaning |
|---------------|---------|
| `AUTO`        | Kotlin/Native's when cross-compiling, the system's for the build host — and for Android, the NDK in `konan.config.androidNdk`, which it then requires. The default of `make { }`, `cmake { }` and `cargo { }`. |
| `KONAN`       | Always Kotlin/Native's. |
| `SYSTEM`      | Never: the build finds its own. The default of `external { }`, whose steps are the build script's own. |

With Kotlin/Native's, the build gets `CC`, `CXX`, `AR` and `RANLIB` (and `KONAN_CMAKE_TOOLCHAIN_FILE`) in its
environment; `make { }` also passes them on the command line, and `cmake { }` passes the toolchain file
as `-DCMAKE_TOOLCHAIN_FILE` unless it uses a preset — a toolchain file in `arguments` still wins, an NDK's
say. They are written per target by `konanToolchain<Target>` under `build/konan/toolchains/<target>/`:
`cc` and `c++` wrap the clang `run_konan` would run, with the target's link flags when linking.

**Android builds with your NDK.** An external build's Android targets on `AUTO` get the NDK in
`konan.config.androidNdk` (or `ANDROID_NDK_HOME`): its `<triple><api>-clang` drivers, `llvm-ar` and
`llvm-ranlib` as `CC`, `CXX`, `AR` and `RANLIB`; for `cmake { }` its `android.toolchain.cmake` with
`ANDROID_ABI` and `ANDROID_PLATFORM` (`androidApiLevel`); and `ANDROID_NDK_HOME` in the environment, for
presets that read it. Kotlin/Native's own Android toolchain — NDK r20's sysroot, libc++ and linker — is used
only by a build that asks for it with `toolchain.set(KONAN)`.

#### Archives built elsewhere — `prebuilt { }`
```kotlin
create("mymath") {
    prebuilt {
        library(NativeTarget.MACOS_ARM64, file("vendor/macos/libmymath.a"))
        library(NativeTarget.MINGW_X64, file("vendor/windows/mymath.lib"))
    }
}
```

`prebuilt<Name>` places each archive as `<target>/lib<name>.a`, whatever it was called, and fails naming
any that does not exist. `targets` default to the ones with an archive; declaring a target without one
fails configuration. The headers stay the binding's business: point its `includeDirs` at them.

#### Building with anything else — `external { }`
The same block as `jni { external { } }`, below: a list of `step { }`s run in order once per target by
`externalBuild<Name><Target>`, with `sources` for up-to-date checks and `producedLibrary(target, file)`
when the build leaves its archive somewhere of its own.

#### The external-build contract
Every build the project's own build system runs — `cmake { }`, `make { }`, `cargo { }`, `zig { }` and
`external { }`, for a library, a binding's `jni` or its `ffm` alike — gets the same variables, as
environment variables and, for CMake, as cache entries:

| variable | meaning |
|---|---|
| `KONAN_LIB_NAME` | the library's name: a library's archive becomes `lib<name>.a`; for `jni` and `ffm`, what the bindings `System.loadLibrary` |
| `KONAN_OUTPUT_DIR` | where this target's library must land: `jniLibs/<abi>/` for a binding's Android library, which the plugin packages. For a library, where its archives go: every `.a` the build leaves there becomes the one `lib<name>.a` — copied if there is one, merged if there are several (a CMake project with dependencies leaves one per `add_library`) |
| `KONAN_TARGET` | the Kotlin/Native target, e.g. `android_arm64` |
| `KONAN_ABI` | its Android ABI, e.g. `arm64-v8a`; the target's name elsewhere |
| `KONAN_SOURCE_DIR` | a library's `sourceDirectory` — libraries only |
| `KONAN_JNI_STUB_DIR` | the directory holding the generated `.c` stub — `jni` only |
| `KONAN_JNI_INCLUDE_DIR` | the directory holding `jni.h`, which the plugin ships, so no JDK is needed — `jni` only |

With Kotlin/Native's `toolchain`, also `CC`, `CXX`, `AR`, `RANLIB` and `KONAN_CMAKE_TOOLCHAIN_FILE` (above).
An owner selects at most one build block.

```kotlin
create("mymath") {
    targets(NativeTarget.LINUX_X64)
    external {
        step {
            executable.set(Executable.File(file("/Users/me/.cargo/bin/cargo")))
            arguments.addAll("build", "--release", "--target", "x86_64-unknown-linux-gnu")
            workingDirectory.set(layout.projectDirectory.dir("native"))
        }
        sources.from("native/src", "native/Cargo.toml")
        producedLibrary(NativeTarget.LINUX_X64, file("native/target/x86_64-unknown-linux-gnu/release/libmymath.a"))
    }
}
```

### `bindings { }`
A C API described by a cinterop `.def`.

| Property          | Type             | Default                                 | Description |
|-------------------|------------------|-----------------------------------------|-------------|
| `defFile`         | `File`           | `src/nativeInterop/cinterop/<name>.def` | cinterop's own default location. |
| `library`         | `NativeLibrarySpec` | —                                    | One of `libraries` — `library(mymath)`: its archive is linked for every consumer and built first, so the `.def` needs no `staticLibraries`. |
| `packageName`     | `String`         | the `.def`'s `package`                  | Package of the generated bindings. |
| `includeDirs`     | `FileCollection` | —                                       | Include roots beyond the `.def`'s directory. |
| `compilerOptions` | `List<String>`   | —                                       | Extra clang options for reading the headers. |
| `jni { }`         |                  |                                         | How the JNI bindings are generated and linked; below. |
| `ffm { }`, `ffm()`|                  | off                                     | Turns on FFM bindings; below. |

### `jni { }`

| Property             | Type                     | Default | Description |
|----------------------|--------------------------|---------|-------------|
| `visibility`         | `BindingVisibility`      | `INTERNAL` | `PUBLIC` when the idiomatic wrapper lives in another module. |
| `targets`            | `List<NativeTarget>`     | `cmake { target(…) }`'s, else the library's targets a JVM can load — the host and Android — else the host | Targets to link the JNI library for. |
| `linkerOptions`      | `List<String>`           | — | Extra options for every target's link. |
| `linkerOptionsFor(target, …)` |                 | — | Extra options for one target's link; `-framework` belongs here, since the Android linker rejects it. |
| `libraryDirectory`   | `Directory`              | output | Where the host's JNI library ends up. |
| `bundleInJar`        | `Boolean`                | `true` | Puts each JVM target's library in the jar, where the bindings load it from; see below. |
| `cmake { }`, `make { }` |                       | — | The project's CMakeLists or Makefile compiles the stub instead; below. |
| `external { }`       |                          | — | Any other build system that compiles the stub, as commands; below. |
| `stubDirectory`, `stubLibraryName`, `libraryDirectoryFor(target)` | read-only | — | The external-build contract as providers, for arguments. |

A platform library needs no archive: `linkerOpts = -laaudio` in the `.def` (or `jni.linkerOptions`) is
enough for the plugin to link the stub against it. An archive built elsewhere is a library with
`prebuilt { }`. Without a library, `staticLibraries` or linker options, the plugin stops after the Kotlin bindings and the `.c` stub, for another build system
to compile. That is the mode for a native library
owned by CMake, Bazel or a vendor: linking an archive built by a foreign toolchain with konan's linker
is what produces mismatched C++ runtimes and missing compiler-rt.

#### Letting your own build link it
When the native library belongs to another build system, the plugin drives that build instead of
linking anything itself, the way AGP's `externalNativeBuild` hands a module's native code to CMake. No
link task is registered: one build task per target takes its place, ordered after generation, and
`linkJni<Name>` depends on them, so "make the JNI library exist" means the same thing either way. A
binding selects at most one of `cmake { }`, `make { }` and `external { }`.

Every run gets [the external-build contract](#the-external-build-contract), `KONAN_JNI_STUB_DIR` and
`KONAN_JNI_INCLUDE_DIR` included. The same values are on `jni` as providers — `stubDirectory`, `stubLibraryName`,
`libraryDirectoryFor(target)` — for building arguments. A step's program is an `Executable`:
`File(file)`, or `OnPath(name)`, which the Gradle daemon's own `PATH` resolves — not a login shell's, so
prefer `File` outside the system directories. `cmake { }` and `make { }` run `konan.config`'s.

The plugin ships Android's `jni.h` (Apache 2.0, VM-neutral, JNI 1.6), so linking a JNI library for a
desktop JVM needs no JDK. A build that needs a newer JNI, or calls `find_package(JNI)`, finds its own.

##### CMake — `jni { cmake { } }`
```kotlin
create("koinference") {
    packageName.set("com.example.native")
    jni {
        cmake {
            path.set(file("native/CMakeLists.txt"))
            preset.set("macosArm64")          // or buildDirectory, for preset-less projects
            buildTargets.add("koinference-jni")
            arguments.add("-DMY_OWN_SWITCH=ON")
        }
    }
}
```

`cmakeBuildJni<Name><Target>` configures, then builds. It always runs and leaves incrementality to CMake,
so a changed configure argument always reaches the build. Android needs a library per ABI, so declare
targets: `target(NativeTarget.ANDROID_ARM64) { preset.set("androidNativeArm64") }` — the `target` blocks
are the targets when `jni.targets` is not set. The contract also arrives as cache entries, and
`CMAKE_LIBRARY_OUTPUT_DIRECTORY` is set to `KONAN_OUTPUT_DIR`, so the CMakeLists needs no knowledge of the
layout:

```cmake
set(KONAN_JNI_STUB_DIR "" CACHE PATH "")
set(KONAN_LIB_NAME "" CACHE STRING "")

file(GLOB JNI_SOURCES "${KONAN_JNI_STUB_DIR}/*.c")
add_library(mylib-jni SHARED ${JNI_SOURCES})
set_target_properties(mylib-jni PROPERTIES OUTPUT_NAME "${KONAN_LIB_NAME}")
target_link_libraries(mylib-jni PRIVATE mylib)   # frameworks, libc++ etc. arrive transitively
```

| Property         | Type             | Default            | Description |
|------------------|------------------|--------------------|-------------|
| `path`           | `File`           | —                  | The `CMakeLists.txt`. Required unless `preset` is set. |
| `preset`         | `String`         | —                  | A configure preset from `CMakePresets.json`. |
| `buildDirectory` | `Directory`      | the preset's `binaryDir` (`<source>/build/<preset>`), else under `build/` | Where CMake builds. Set, it is passed as `-B`, overriding a preset's `binaryDir` (CMake 3.21+). |
| `buildTargets`   | `List<String>`   | the default target | `--target`s to build. |
| `arguments`      | `List<String>`   | —                  | Extra configure arguments. |
| `libraryDirectory` | `Directory`    | —                  | Where the build leaves the library, if it ignores `CMAKE_LIBRARY_OUTPUT_DIRECTORY`: the plugin copies it into place. |
| `target(t) { }`  |                  | —                  | A per-target build with its own `preset`, `buildDirectory`, `arguments`. |

##### Any build system — `jni { external { } }`
A list of commands, run in order once per JNI target (`jni.targets`):

```kotlin
jni {
    external {
        step {
            executable.set(Executable.File(file("/usr/bin/make")))
            arguments.addAll("-C", "native/jni")
            argumentsFor(NativeTarget.ANDROID_ARM64, "ARCH=arm64")
        }
        sources.from("native")                       // what it reads; omit to always run
        producedLibrary(NativeTarget.MACOS_ARM64, file("native/jni/out/libstub.dylib"))
    }
}
```

`externalBuildJni<Name><Target>` runs the steps. The build writes the library into `KONAN_OUTPUT_DIR`,
or names where it leaves it with `producedLibrary(target, file)`, and the plugin copies it into place
under the name the bindings load. With `sources` declared the task is up to date while they and the
steps are unchanged; without, the plugin cannot know what the build reads, so it always runs.

| Step property      | Type                | Description |
|--------------------|---------------------|-------------|
| `executable`       | `Executable`        | The program. Required. |
| `arguments`        | `List<String>`      | Arguments for every target; `add(provider)` composes the contract providers. |
| `argumentsFor(target, …)` |              | Arguments for one target, after `arguments`. |
| `environment`      | `Map<String, String>` | Variables beyond the contract; these win over it. |
| `workingDirectory` | `Directory`         | Where it runs. |

### Libraries in the jar
In a `kotlin("jvm")` project or a multiplatform `jvm()` target, the JNI library of every JVM target —
and, with `ffm()`, the library FFM loads — goes into the jar as `konan/natives/<target>/<file>`
(`bundleJni<Name>`, `bundleFfm<Name>`). The generated bindings load it with `System.loadLibrary` when
`java.library.path` has it, so a build's own setup still wins, and otherwise extract it for the running
platform to a temporary file. `run`, `installDist` and a plain `java -cp` need no library path.

Declare more JVM targets to ship one jar for several platforms — Linux libraries cross-link from a Mac:

```kotlin
val mymath = libraries.create("mymath") { targets(NativeTarget.host, NativeTarget.LINUX_X64) }
bindings { create("mymath") { library(mymath); jni { targets(NativeTarget.host, NativeTarget.LINUX_X64) } } }
```

The loader is generated into each binding's package, so the bindings still need nothing at runtime.
`jni { bundleInJar.set(false) }` (or `ffm { … }`) leaves the libraries out.

### The generated JNI bindings
One `external fun` per C function, named after it and carrying its C signature as a doc comment.
cinterop's own indexer reads the headers — the same `.def` the Kotlin/Native targets use — and the
plugin writes both sides of each JNI bridge from the C declarations:

| C type                                       | Kotlin type                      | Crosses as                                         |
|----------------------------------------------|----------------------------------|----------------------------------------------------|
| integers, `char`, `bool`, floating point, enums | `Int`, `Long`, `Byte`, `Boolean`, `Double`, … | the JNI primitive of the same size |
| `const char*` (argument or result)           | `String?`                        | `GetStringUTFChars` / `NewStringUTF`               |
| `int*`, `uint8_t*`, `float*`, … buffer       | `IntArray?`, `ByteArray?`, `FloatArray?`, … | pinned for the call; written back unless `const` |
| struct by value (argument or result)         | `ByteArray`                      | the struct's bytes                                 |
| pointer to pointer (`handle_t**` out-parameter) | `LongArray?`                 | addresses, copied into a C pointer array and back unless `const` |
| `void*`, `const void*` argument               | `java.nio.Buffer?`               | a direct buffer's start address (`GetDirectBufferAddress`); a heap buffer throws |
| opaque handle, typedef'd `void*`, struct or function pointer, any pointer result | `Long` | the address                         |

Enum constants and integer, floating-point and string macros become `const val`s. Functions that
cannot be bound — variadic ones, `long double` — are listed with the reason at the end of the generated
file. Unsigned integers cross as the signed Kotlin type of the same size. Struct bytes are
in native order, so read them with `ByteBuffer.order(ByteOrder.nativeOrder())` and the layout from the
C header.

The bindings are `internal` by default — they are an implementation detail of the module that writes
the idiomatic API over them. Each carries `@JvmName`, because JNI resolves the symbol from the
unmangled method name. Set `jni { visibility.set(BindingVisibility.PUBLIC) }` if the wrapper is in a
different Gradle module.

### `ffm { }`
FFM (`java.lang.foreign`) bindings, written by jextract from the same `.def`, and the library linked
as a shared library for the build host — FFM needs no stub. In a `kotlin("jvm")` project they replace
the JNI bindings and are added to the main Java sources; in a multiplatform project they replace them on
every target — the `jvm()` targets get the JDK's in their Java source set, the Android target
PanamaPort's (below); compile the JVM side with a JDK 22+ toolchain and run
with `--enable-native-access`; the library travels in the jar (below), or put `ffm.libraryDirectory` on
`java.library.path`.

| Property           | Type        | Default              | Description |
|--------------------|-------------|----------------------|-------------|
| `panamaPortVersion`| `String`    | `v0.1.5`             | The PanamaPort an Android module's bindings use, added as a dependency. |
| `libraryName`      | `String`    | the library's, else the binding's | What the bindings `System.loadLibrary`. Set it to a prebuilt library's own name. |
| `targets`          | `List<NativeTarget>` | `cmake { target(…) }`'s, else the library's loadable ones, else all loadable | The host on a JVM; 64-bit ABIs on Android. |
| `cmake { }`, `make { }` |        | —                    | The project's CMakeLists or Makefile makes the shared library; below. |
| `external { }`     |             | —                    | Any other build system makes it, as commands; below. |
| `prebuilt { }`     |             | —                    | Shared libraries built elsewhere: `library(target, file)`; below. |
| `linkerOptions`    | `List<String>` | —                 | Extra options for linking the library the bindings load; `linkerOptionsFor(target, …)` for one target. |
| `libraryDirectory` | `Directory` | output               | Where the shared library ends up: the host's, or the `jniLibs` root on Android. |
| `bundleInJar`      | `Boolean`   | `true`               | Puts the library in a `kotlin("jvm")` project's jar; see "Libraries in the jar". |

#### The shared library the bindings load
FFM needs no stub: the bindings load the C library itself. By default the plugin links the binding's
library's archive into it (`linkFfm<Name><Target>`). Instead:

- `ffm { cmake { } }` — the same block as `jni { cmake { } }`, run once per target by
  `cmakeBuildFfm<Name><Target>`: the contract as cache entries, `CMAKE_LIBRARY_OUTPUT_DIRECTORY` set to
  where the bindings load the library, so `add_library(${KONAN_LIB_NAME} SHARED …)` is all the
  CMakeLists needs. It builds for its `target(…) { }` blocks, else for `ffm.targets`; on Android, pass
  the NDK's toolchain per ABI:

  ```kotlin
  ffm {
      cmake {
          path.set(file("native/CMakeLists.txt"))
          arguments.addAll("-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake", "-DANDROID_PLATFORM=26")
          target(NativeTarget.ANDROID_ARM64) { arguments.add("-DANDROID_ABI=arm64-v8a") }
          target(NativeTarget.ANDROID_X64) { arguments.add("-DANDROID_ABI=x86_64") }
      }
  }
  ```
- `ffm { external { step { } } }` — the same block as `jni { external { } }`, run once per target by
  `externalBuildFfm<Name><Target>`. Each run gets [the contract](#the-external-build-contract), and writes
  `lib<KONAN_LIB_NAME>` with the platform's suffix into `KONAN_OUTPUT_DIR`, or names where it leaves it
  with `producedLibrary(target, file)`. `ffm { make { } }` works the same way.
- `ffm { prebuilt { library(NativeTarget.ANDROID_ARM64, file("vendor/libggml.so")) } }` — a vendor's
  libraries, placed by `prebuiltFfm<Name>`. Set `libraryName` to their own name (`"ggml"`), so libraries
  that depend on them by that name still find them.

Neither needs a `library(…)`; the bindings then come from the `.def` and its headers alone. With none of
these and no `library(…)`, nothing is built: the bindings load `lib<libraryName>` as the system provides
it — `ffm { libraryName.set("aaudio") }` binds Android's `libaaudio.so`.

#### FFM on Android
In an Android module, and for a multiplatform project's Android target, `ffm()` replaces JNI there too.
A multiplatform project with both `jvm()` and Android targets gets the bindings twice — `generateFfm<Name>`
for the JDK, `generateFfmAndroid<Name>` for PanamaPort — so `jvmMain` and `androidMain` each implement
your `expect`s over theirs. The multiplatform Android library plugin compiles Java only with
`kotlin { android { withJava() } }`, which the bindings need. jextract's output is moved from `java.lang.foreign`
onto PanamaPort's `com.v7878.foreign` — the FFM API backported to Android 8.0+ — which the plugin adds
as an `implementation` dependency, and the library is linked as `lib<name>.so` per ABI into `jniLibs/`,
with no stub. The app needs:

- `compileSdk` 37 and `minSdk` 26, which PanamaPort requires;
- `sourceCompatibility` 21: jextract's output uses pattern matching in `switch`, which D8 desugars;
- 64-bit ABIs only: jextract writes the build host's LP64 layouts, so 32-bit ABIs get no library and
  a warning.

`examples/android` runs FFM and JNI side by side on an emulator.

#### Android: the NDK, and its C++ runtime
Every Android library the plugin builds — the default library build, the JNI library, the library FFM
loads — is compiled and linked with the Android NDK in `konan.config.androidNdk` (r23 or newer), which
Android targets require. In an Android module it is the NDK the Android Gradle plugin uses — the one
`android { ndkVersion = "…" }` names, else AGP's default, once it is installed — so nothing needs setting;
`konan.config.androidNdk=<Android SDK>/ndk/<version>` or `ANDROID_NDK_HOME` choose another, and are what a
module without AGP sets. `konanConfig` shows which one a build uses. One NDK for everything, so an archive
your own build made with it links against the same libc++, and `androidApiLevel` reaches every API level
the NDK has.

Android gives apps neither libc++ nor an unwinder, so the links use `-static-libstdc++`: the NDK's libc++,
libc++abi and libunwind go inside each library, and nothing needs `libc++_shared.so`. A C library links as
before. C++ code (`new`, `std::string`, exceptions) and Rust's std then load on a device. The links also
pass `-Wl,--no-undefined`, so a symbol nothing provides fails the build, not the app when it loads the
library; a library that resolves symbols from another `.so` at load time names it in `linkerOptions`
(`-lfoo`), or turns the check off with `-Wl,-z,undefs`. Archives Kotlin/Native's toolchain built still link:
the NDK's libc++ is the newer one.

## Tasks
| Task                          | Description |
|-------------------------------|-------------|
| `compileNativeLibrary<Name>`  | Builds a library to `lib<name>.a` per target, with whichever provider it uses. |
| `konanClang<Name>`, `cmakeBuild<Name><Target>`, `zigBuild<Name><Target>`, `cargoBuild<Name><Target>`, `makeBuild<Name><Target>`, `externalBuild<Name><Target>`, `prebuilt<Name>` | The provider doing it: Kotlin/Native's clang, `zig build`, make, the library's own steps, or placing prebuilt archives. |
| `konanToolchain<Target>`      | Writes Kotlin/Native's compilers for a target, for external builds that use them. |
| `generateJni<Name>`           | Generates a binding's JNI `.c` stub and Kotlin bindings. |
| `linkJni<Name>`               | Makes the binding's JNI library exist for every target, however it is built. |
| `linkJni<Name><Target>`       | Links it for one target. |
| `cmakeBuildJni<Name><Target>`, `makeBuildJni<Name><Target>`, `externalBuildJni<Name><Target>` | The project's own build, in place of the JNI link. |
| `linkFfm<Name>`, `linkFfm<Name><Target>` | Makes the library FFM loads exist; the plugin's link of it for one target. |
| `cmakeBuildFfm<Name><Target>`, `makeBuildFfm<Name><Target>`, `externalBuildFfm<Name><Target>`, `prebuiltFfm<Name>` | The project's own build of it, or placing a prebuilt one, in place of that link. |
| `generateFfm<Name>`, `generateFfmAndroid<Name>` | jextract's bindings: on the JDK's FFM, and on PanamaPort's for Android. |
| `cinterop<Name><Target>`      | KGP's own cinterop, declared by the plugin. |

On Android these are ordered ahead of `preBuild` and the Kotlin compile tasks automatically, so
`assembleDebug` alone runs the whole chain.

## Examples
- `examples/native` — Kotlin/Native consumer through KGP's cinterop.
- `examples/jvm` — plain JVM app loading the JNI library bundled in its jar.
- `examples/android` — one Android app over three libraries: C and Rust through JNI, C++ through FFM.
  `scripts/run-android.sh` builds, installs and launches it in one command.
- `examples/kmp` — Kotlin Multiplatform library for iOS, macOS, Linux and Android: one binding, cinterop on Kotlin/Native and JNI on
  Android, behind an `expect`/`actual` API, plus an Android app over it.

## Migrating to 2.0
The DSL is new and there is no compatibility shim.

- `konanConfig { targets; libName; sourceDir; … }` becomes a library:
  `konan { libraries { create("<libName>") { targets(…); sourceDirectory.set(…) } } }`. Its archives move
  from `build/native/<target>/` to `build/konan/libraries/<name>/<target>/`, and `runKonanClang` becomes
  `compileNativeLibrary<Name>` (which runs `konanClang<Name>`).
- `jvmInterops { create(…) { … } }` — on a compilation or on the project — becomes
  `konan { bindings { create(…) { … } } }`, with the JNI-only settings in `jni { }`: `jniHome` is gone
  (the plugin ships `jni.h`), `library` → a library with `prebuilt { library(target, file) }`, named with
  `library(…)`, `additionalLinkerArgs` → `linkerOptions`, `linkerArgsFor` →
  `linkerOptionsFor`, `externalNativeBuild { cmake { } }` → `cmake { }` (`targets` → `buildTargets`,
  `abi("arm64-v8a")` → `target(NativeTarget.ANDROID_ARM64)`, `executable` → `konan.config.cmake`, no `$CMAKE`;
  its tasks are `cmakeBuildJni<Name><Target>`),
  `resolvedLibraryDirectory` →
  `libraryDirectory`. `additionalCompilerArgs` becomes `compilerOptions`. Bindings are wired into the
  project's JVM and Android targets automatically.
- Refer to the library instead of naming the archive: `library(mymath)`, where
  `val mymath = libraries.create("mymath") { … }`. Drop `staticLibraries` for it from the
  `.def`, or Kotlin/Native would embed it twice.
- `kotlinNativeInterop` is gone: a binding is declared for every Kotlin/Native target by itself.
- Targets are `NativeTarget`s (`io.github.lemcoder.model`), not `KonanTarget`s: `KonanTarget.X` →
  `NativeTarget.X`, `hostKonanTarget()` → `NativeTarget.host`, a KGP target's `konanTarget` →
  `NativeTarget.of(konanTarget)` (`import io.github.lemcoder.kotlin.of`). `jniHome()` is gone: external
  builds get `KONAN_JNI_INCLUDE_DIR`. Programs (`zig`, `cmake`, `jextract`) and the distribution come from
  `konan { config { } }`.
- External builds read one contract: `KONAN_JNI_LIB_NAME`, `KONAN_FFM_LIB_NAME` and `KONAN_LIBRARY_NAME` →
  `KONAN_LIB_NAME`; `KONAN_JNI_OUTPUT_DIR`, `KONAN_FFM_OUTPUT_DIR` and `KONAN_LIBRARY_OUTPUT_DIR` →
  `KONAN_OUTPUT_DIR`; `KONAN_JNI_ABI` and `KONAN_FFM_ABI` → `KONAN_ABI`; `KONAN_LIBRARY_SOURCE_DIR` →
  `KONAN_SOURCE_DIR`. `KONAN_JNI_STUB_DIR`, `KONAN_JNI_INCLUDE_DIR` and `KONAN_TARGET` stay.
- `jni { cmake { libraryDirectory } }` is where the plugin copies the library from, as for libraries and
  FFM, not where consumers look; `jni.archive` is gone — declare the archive as a `prebuilt { }` library.
- The build blocks' types live in `io.github.lemcoder.dsl.builds` — `ExternalToolchain`, `ZigOptimize` and
  `CargoProfile` included — and share one interface, `BuildSettings`. Both `prebuilt { }` blocks take
  `library(target, file)`: `archive(…)` and `sharedLibrary(…)` are gone.
- JNI bindings are named after the C functions (`my_add`), not `kniBridge0`; tasks are `generateJni…` and
  `linkJni…`.
- Android needs an Android NDK (r23 or newer), which builds every Android library: in an Android module the
  one the Android Gradle plugin uses, else `konan.config.androidNdk` or `ANDROID_NDK_HOME`. Kotlin/Native's
  own Android toolchain is no longer used, and `androidApiLevel` is no longer capped at 29.
- A library's build may leave several archives in `KONAN_OUTPUT_DIR`; they are merged into `lib<name>.a`.

## License
This project is licensed under the Apache 2.0 License. For more details, see the `LICENSE` file.

The plugin bundles `jni.h` from the Android Open Source Project
(`libnativehelper`), Copyright (C) 2006 The Android Open Source Project, licensed under the Apache
License 2.0; its license header is kept in the file.

Happy coding! 😊
