# Changelog

Versions are published to the [Gradle Plugin Portal](https://plugins.gradle.org/plugin/io.github.lemcoder.konanplugin)
and never republished. 2.0 replaces 1.x's DSL; the README's migration notes cover the move.

## 2.0.0-alpha08

### Fixed
- **JNI and FFM bindings honor the `.def`'s `excludedFunctions`** ([#22](https://github.com/lemcoder/KonanPlugin/issues/22)).
  cinterop leaves the functions a `.def` excludes out of Kotlin/Native's bindings, but the JNI generator
  still bound them, and jextract was still asked to include them. One `.def` therefore gave a different
  API on the JVM and Android, and a function the JVM side could not bind could not be dropped. Excluded
  functions now get no `external fun`, no C stub and no FFM binding; the JNI bindings list them under
  `// Not bound:` as `excluded in the .def`.
- **Functions taking a `va_list` are no longer bound over JNI** ([#22](https://github.com/lemcoder/KonanPlugin/issues/22)).
  No JVM value can become a `va_list`, and on arm and arm64, where it is a struct, the stub's cast to it
  did not compile, so the JNI library failed to link for every Android ABI but x86 (Lua's
  `lua_pushvfstring`, for one). They are now listed under `// Not bound:` as `takes a va_list`, as variadic
  functions are.

## 2.0.0-alpha07

### Fixed
- **`kotlinNativeDistribution()` names the project's Kotlin under AGP 9's built-in Kotlin** ([#21](https://github.com/lemcoder/KonanPlugin/pull/21)).
  The plugin recognized Kotlin by plugin id, and built-in Kotlin applies none, so an Android module without
  `kotlin-android` got the plugin's default version (2.4.20) instead of its own (2.2.10 with AGP 9.2.1). The
  version now comes from the `kotlin` extension, whichever order the plugins are applied in.

  In such a module `konanHome.set(kotlinNativeDistribution())` now points at the project's Kotlin/Native
  version. If only the plugin's default version is installed, set `konan.config.konanHome` to it.
- **JNI bindings in an Android module that compiles no Kotlin fail configuration** ([#21](https://github.com/lemcoder/KonanPlugin/pull/21)).
  The JNI bindings are Kotlin. With `android { enableKotlin = false }`, nothing compiled them, so they went
  missing silently and Java callers could not find them. Configuring that variant's build now fails, saying to
  enable Kotlin or call `ffm()`, which generates Java.

## 2.0.0-alpha06

### Fixed
- **The Android Gradle plugin's NDK beats `ANDROID_NDK_HOME`** ([#19](https://github.com/lemcoder/KonanPlugin/issues/19)).
  `konan.config.androidNdk` read the environment before the module's own `android.ndkVersion`, and GitHub's
  runners preset `ANDROID_NDK_HOME` to NDK 27, so a project on NDK 28 silently shipped libraries built by 27
  (4 KB-aligned, not 16 KB). The order is now DSL, Gradle property `konan.config.androidNdk`, AGP's NDK,
  `ANDROID_NDK_HOME`. When AGP's NDK is not installed, the environment variable still applies.

  When a different `ANDROID_NDK_HOME` loses to AGP's NDK, the build warns once and `konanConfig` reports it
  as not used. To keep using it, set `konan.config.androidNdk`.

## 2.0.0-alpha05

### Changed
- **Pointers to records cross JNI as typed handles** ([#17](https://github.com/lemcoder/KonanPlugin/pull/17)).
  A pointer to a named struct or union was a bare `Long`, so any `Long` compiled where a `tsf*` was expected
  and a failed call's NULL came back as `0L`. Each such record now gets
  `@JvmInline value class <Tag>Pointer(val address: Long)`, named by the record's tag (or an anonymous
  record's typedef), so `node_t*` and `struct node*` are one `NodePointer`. Parameters take a non-null
  handle; results are nullable, NULL being `null`. `address` is the raw pointer, and `TsfPointer(0L)` passes
  NULL deliberately. Typedef'd `void*`, function pointers and pointers to pointers are unchanged.

  **Breaking:** code that passed these pointers as `Long` must use the handle types.

## 2.0.0-alpha04

### Fixed
- **JNI strings cross as real UTF-8** ([#14](https://github.com/lemcoder/KonanPlugin/issues/14)). `const char*`
  went through `GetStringUTFChars`/`NewStringUTF`, which speak modified UTF-8: a character outside the BMP
  (👍, 𠀀) reached C as two encoded surrogates on HotSpot, and a 4-byte sequence returned from C is undefined
  behavior that CheckJNI aborts on. Strings now cross as NUL-terminated UTF-8 bytes. The bindings keep their
  `String?` signatures; each wraps a private `external` named `<name>$kni`.
- **Generated KMP JNI sources carry their producer** ([#15](https://github.com/lemcoder/KonanPlugin/pull/15)).
  The generated bindings were added to multiplatform JVM and Android source sets without a task dependency,
  so anything but the compile, such as `androidSourcesJar` or `jvmSourcesJar` when publishing, failed with
  *"uses this output of task ':generateJni…' without declaring an explicit or implicit dependency"*.
- **Tasks needing Kotlin/Native run after KGP's download** ([#16](https://github.com/lemcoder/KonanPlugin/issues/16)).
  With `konanHome.set(kotlinNativeDistribution())`, `generateJni<Name>`, `konanClang<Name><Target>`,
  `linkJni<Name><Target>` and the other tasks needing Kotlin/Native could run before the Kotlin plugin's
  `downloadKotlinNativeDistribution` and fail on a machine without that distribution. They now depend on it
  when it exists (Kotlin 2.3 and newer) and `konanHome` is the directory it installs. Builds that added this
  `dependsOn` themselves can drop it.

## 2.0.0-alpha03

### Added
- `cmake { sources }`: declared sources make a CMake build up to date when they have not changed ([#13](https://github.com/lemcoder/KonanPlugin/issues/13)).

### Fixed
- Android packaging (APK, AAR) builds only the Android targets' libraries, not the host's
  ([#12](https://github.com/lemcoder/KonanPlugin/issues/12)).
- A target named twice registers its tasks once.
- Kotlin/Native's LLVM is downloaded on a machine that has never had it, instead of failing the first
  compile.
