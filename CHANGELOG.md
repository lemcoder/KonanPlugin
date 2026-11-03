# Changelog

Versions are published to the [Gradle Plugin Portal](https://plugins.gradle.org/plugin/io.github.lemcoder.konanplugin)
and never republished. 2.0 replaces 1.x's DSL; the README's migration notes cover the move.

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
