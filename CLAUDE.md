# Working on this plugin

Gradle and KGP/AGP internals that cost time to discover. The README documents the DSL; this is what
bites while changing it.

## Gradle decorates classes at apply time

Any type appearing in a **decorated signature** — extension properties, task properties — is
resolved the moment the plugin is applied, in *every* project that applies it.

- `KotlinCompilation` and `KotlinTarget` are neither `ExtensionAware` nor open to third-party DSL
  blocks, which is why the whole DSL is one project extension, `konan`, and consumers are found from
  the project's plugins and targets. No KGP or AGP type may appear in the DSL classes: putting one in a
  decorated signature fails with *"Could not generate a decorated class … KotlinCompilation"* in any
  project without KGP. All KGP code lives in `kotlin/KotlinGradlePlugin` and AGP code in
  `wiring/AgpWiring`, reached only from `plugins.withId` callbacks or once the plugin is known to be
  applied.
- **The DSL speaks `model.NativeTarget`, never `KonanTarget`.** Only the `kotlin/` package imports
  `org.jetbrains.kotlin.*`: `NativeTarget.of(konanTarget)`, the KGP adapter, the distribution
  (`KonanDistribution`) and the index exporter client. So `kotlin-native-utils` is `compileOnly` —
  everything that touches it runs where KGP, which brings it, is applied. `NativeTargetTest` fails when
  a Kotlin bump adds or retires a target; add the `NativeTarget` entry (and its Zig triple, if any).
- **The `dsl` packages nest as the DSL does**: `dsl` is `konan { }`, then `dsl.config`, `dsl.libraries`,
  `dsl.bindings` with `dsl.bindings.jni` and `dsl.bindings.ffm`. Build blocks appear at every level, so
  they share `dsl.builds`. A new block goes in the package of the block that contains it.
  Never name a source package `build`: `.gitignore`'s `build/` matches it, and `git add` silently skips it.
- **Implementation lives beside its DSL**, all of it `internal`:
  - `dsl.config` — `ConfigField`/`ConfigValue` and the `konanConfig` report;
  - `dsl.libraries` — a library's registration and builds, and `NativeArtifacts`, the layout it publishes;
  - `dsl.bindings` — a binding's registration, `DefFile`, the consumers (`BindingConsumer`,
    `KotlinNativeConsumer`) and what JNI and FFM share (`BindingLibraryWiring`, the jar bundle,
    `LinkSharedLibraryTask`); `dsl.bindings.jni` and `dsl.bindings.ffm` each hold one consumer and its own link;
  - `dsl.builds` — the external builds (`ExternalBuild`, `ExternalBuildTask`, each build system's commands),
    their contract (`ExternalVariable`), `selectOne`, and the Zig and Rust target triples.
- **What no single block owns stays outside `dsl`**: `model/` the shared domain (`NativeTarget`, `HostOs`,
  `Executable`, `taskName`), `kotlin/` the touchpoints with Kotlin (distribution, KGP, Xcode's compiler-rt for
  its links, the index exporter client), `android/` the Android NDK every Android build uses, `index/` the generate task, `wiring/` AGP and plugin ids, `util/`
  process and Gradle helpers. Task names come from `taskName(...)`, OS checks from `HostOs`, the
  distribution's layout from `KonanDistribution` — add to those, don't re-derive. `BindingVisibility` lives
  in `src/exporterProtocol` so the DSL and the generator share one enum.
- AGP types are only touched inside `plugins.withId(...)` blocks. A plain reference in an eagerly
  executed method is `NoClassDefFoundError: KotlinMultiplatformAndroidComponentsExtension` in a
  plugin-only build. The KMP Android plugin does **not** apply `com.android.base`, so both ids need
  handling.

## Machine paths: `konan { config { } }`

- **The single source of every path the plugin needs from the machine** — `konanHome`, `jextract`,
  `cmake`, `make`, `cargo`, `zig`, `androidNdk`. Nothing is searched for: no `PATH` scan, no install-directory or
  `~/.konan` lookup, no JDK. `KonanConfig.readConfigField(field, usedBy)` reads the DSL, then the Gradle
  property `konan.config.<field>`, then the environment variable, and records `usedBy` for `konanConfig`.
  Add a path by adding a `ConfigField` entry and a property, never a per-block `executable`.
- **Tasks that run Kotlin/Native implement `UsesKonanDistribution`**; the plugin sets their `konanHome`
  in one `withType().configureEach`, and `distribution()` checks it when the task runs.
- **The plugin ships Android's `jni.h`** (`src/main/resources/io/github/lemcoder/dsl/bindings/jni/`, Apache 2.0): the
  JNI generator writes it to its `include/` output, the host link and `KONAN_JNI_INCLUDE_DIR` use it. Its
  JNI is 1.6 — enough for the generated stubs.

## Ordering rules that are not obvious

- **Configuration cache resolves conventions when it stores.** A `konanHome` convention that threw
  broke *configuration*; one that returned absent got the absent value cached before KGP had
  downloaded the distribution. So `konan.config` fields are always-present providers of a
  `ConfigValue` (possibly unset), and `ConfigValue.require()` checks them in the task action.
- **`all`/`whenObjectAdded` fires before `create(name) { … }` runs its configure block.** Reading a
  binding's `library` or a library's `targets` there gives the default, not what the build declared —
  per-target tasks, consumer detection and KGP's cinterops are deferred to `afterEvaluate` (KGP
  accepts cinterops declared there). Both sides follow this split: a library's build registers
  after evaluation (its block's conventions are set in the DSL constructors); a `BindingConsumer`
  (`dsl/bindings/`) has `prepare()` for the eager part, then `isUsed()` and `register()` after evaluation.
- **One set of build systems for every library the plugin makes.** A library's archive, a binding's JNI
  library and the library FFM loads are each built by the plugin unless the script calls a block of
  `ExternalBuildBlocks` (`cmake`, `make`, `external`; libraries add `zig`, `cargo`, `prebuilt`, FFM
  `prebuilt`). Every block's settings live in `dsl.builds` and implement the sealed `BuildSettings`; calling a
  block selects its settings object, and owners dispatch on `PrebuiltSettings` or `ExternalBuildSettings`.
  `ExternalBuild.of(settings)` (`dsl/builds/ExternalBuild.kt`) registers
  one `ExternalBuildTask` per target from an `ExternalBuildOutput` — the only thing that differs between
  owners: targets, output directory, file name, extra variables. A build system only adds commands, so a
  new one is one `ExternalBuild` subclass and a block, never a new task type. JNI and FFM share
  `BindingLibrarySpec` and `BindingLibraryWiring`, both in `dsl.bindings`.
- **AGP variant callbacks must be registered eagerly.** From `afterEvaluate` AGP refuses with
  *"It is too late to add actions as the callbacks already executed"*, so the jniLibs wiring runs at
  binding-registration time and tolerates an empty directory.
- **A library has one archive per target, however many its build leaves.** A library's external build writes
  into a staging directory (`KONAN_OUTPUT_DIR`, CMake's output directories), emptied before each run;
  `ExternalBuildTask` copies the one archive, or merges several with Kotlin/Native's llvm-ar (`qcL`, then `s`;
  `darwin` format for Apple) into `lib<name>.a`. Consumers keep embedding a single archive. Staging is what
  keeps a re-run from merging the previous merge in again (#7).
- **An external build's configure and build steps are one task.** As two tasks, changing a configure
  argument re-ran configure, left the build `UP-TO-DATE`, and silently kept the previous library — a
  linker flag appeared to do nothing. `ExternalBuildTask` runs every step, and without declared
  `sources` it always runs, leaving incrementality to the build system.
- **External-build steps stay lazy.** The stub directory comes from the generator's
  `@OutputDirectory`, so steps are `@Nested` `ExternalCommand` beans whose arguments are composed from
  providers (`add(provider.map { … })`); never `provider { x.get() }`. Executables are resolved in the
  task action (`Executable.command()`), not at configuration.

## Layout and naming contracts

- **Only ABI-named directories may sit under `jniLibs`.** AGP reads each subdirectory as an ABI, so
  a host library in the root fails the merge with *"… is not an ABI"*. Host builds write to
  `konan/bindings/<name>/jni/lib/`. This only reproduces on Linux: AGP ignores `.dylib`.
- **`Family.dynamicSuffix` has no leading dot.** `"${prefix}$name${suffix}"` yields
  `libfoostubsdylib`.
- **The stub library name is derived from the binding package** (`stubLibraryBaseName`) and baked into
  the generated `System.loadLibrary`; `jni.stubLibraryName` and `jni.stubDirectory` carry it. The
  generator's layout (`kotlin/`, `c/`, `include/`) is `GeneratorRequest.Jni`'s constants, shared with the
  exporter — never spell the directories. JNI is generated by the same `GenerateFromNativeIndexTask` as
  FFM; it writes `jni.h` for a JNI request.
- **The external-build contract is one enum, `ExternalVariable`**: `KONAN_LIB_NAME`, `KONAN_OUTPUT_DIR`,
  `KONAN_TARGET`, `KONAN_ABI` for every build, `KONAN_SOURCE_DIR` for libraries, `KONAN_JNI_STUB_DIR` and
  `KONAN_JNI_INCLUDE_DIR` for JNI. Public: build scripts read it, so renaming one breaks them.
- **A file under a generator's output needs the task's own property.** `generate.flatMap { it.outputDirectory.file(name) }`
  carries the producer; `generate.flatMap { it.outputDirectory }.flatMap { it.file(name) }` does not, and
  Gradle then rejects the link at execution ("implicit dependency") — only when several links run, so a
  dry run never shows it.
- **`cmake --preset` resolves `CMakePresets.json` from the working directory**, so configure runs
  from the CMakeLists directory rather than the project root.

## The generated bindings

The `jni` generator (`src/indexExporter/.../JniGenerator.kt`) writes both sides of each bridge from
cinterop's `NativeIndex`: the Kotlin `external fun` named after the C function, and the `JNIEXPORT`
function that marshals strings, primitive buffers and structs by value. cinterop's own JVM flavor is
not used — it emits address-only bridges, every pointer a raw `Long`.

Bindings are `internal` by default and each carries **`@JvmName`**. JNI resolves the C symbol from the
unmangled method name, so a mangled internal name compiles, links, and dies on the first call.

## Kotlin/Native's toolchain for external builds

- **`run_konan` starts a JVM per call**, so `CC` is not a wrapper around it: `KonanToolchainTask` runs
  `run_konan clang <driver> <target> --version` once, takes the clang command it echoes, and writes `cc`
  and `c++` scripts around that, plus a CMake toolchain file. Android is `Linux` to CMake — its Android
  platform wants an NDK — and Apple targets set `CMAKE_OSX_SYSROOT`, or CMake's host SDK would win.
- **Link flags live in `KonanDistribution.linkArguments`**: `-fuse-ld=lld` for Linux (the GCC toolchain's
  `ld.bfd` is a Linux binary; a cross link from macOS fails without it), NDK compiler-rt and
  `--unwindlib=none` for Android, Xcode's compiler-rt for macOS. The wrappers add them only when linking.
- **Android requires an NDK (`konan.config.androidNdk`), and everything Android uses it** — `android/AndroidNdk`:
  the default library build (`CompileNativeLibraryTask`), the JNI and FFM links (`LinkSharedLibraryTask`), and
  external builds on `AUTO` (`AndroidNdkToolchain`). Kotlin/Native's Android toolchain is clang 21 driving NDK
  r20's sysroot, libc++ and LLD 8, with sysroots only up to API 29; mixing it with archives built by a current NDK
  is what broke koinference. Only an external build with `toolchain = KONAN` still uses it. The tasks read the NDK
  as a `ConfigValue` checked when they run, and track its path and `Pkg.Revision` as an input.
- **An Android link compiles the stub with the NDK's C driver, then links with its C++ driver**:
  `-static-libstdc++` puts libc++, libc++abi and libunwind in the library (Android gives apps none), and
  `-Wl,--no-undefined` makes a missing symbol fail the link — without it, it linked fine and failed only on
  the device ("cannot locate symbol"); never drop it to make a link pass. The stub is C: `clang++` would
  compile a `.c` as C++, where `(*env)->…` does not compile. The NDK's clang prefix for 32-bit ARM is
  `armv7a-linux-androideabi`, its library directory `arm-linux-androideabi`.
- **The toolchain needs its own `ranlib`** (`llvm-ar s`): CMake indexes static archives with the host's,
  and Apple's `ranlib` silently drops ELF objects — a cross-built archive came out empty.
- **`external { }` defaults to `ExternalToolchain.SYSTEM`**, the presets to `AUTO`: generic steps may not
  compile anything, and must not need a Kotlin/Native distribution to run.
- **Cargo gets the compilers under the `cc` crate's target-specific names** (`CC_<triple>`…), not `CC`:
  a plain `CC` would also reach build scripts compiled for the host. `ExternalBuild.register` therefore
  carries the toolchain in the context (`ToolchainFiles`) and puts the standard names in the environment
  only where asked. Never register a toolchain task from inside another task's configuration — Gradle
  forbids it; resolve it in `ExternalBuild.register`.
- **Task names carry the side**: `<tool>Build<Name>` for a library, `<tool>BuildJni…`, `<tool>BuildFfm…`
  for a binding — a library and a binding are usually both called `mymath`.

## Kotlin/Native's dependencies

- **`run_konan` never downloads a target's sysroot**; the compiler does, when it links. So before
  compiling or linking for a target, `KonanDistribution.ensureDependencies` checks the `~/.konan/
  dependencies` paths in the clang command `run_konan` would run and, if any is missing, links a trivial
  program for that target. Without it, a target Kotlin itself has not built yet fails with
  "'string.h' file not found". Linux arm64's sysroot is glibc 2.25, x86-64's 2.19 — Zig's triples match.

## Multiplatform projects

- **JS and Wasm targets are ignored**, deliberately and silently (README says so). If they are ever
  bound: Kotlin/Wasm imports another module's functions with `@WasmImport(module, name) external fun`,
  the JNI generator's shape; Kotlin/Native's bundled LLVM already compiles `--target=wasm32` and links
  with `lld -flavor wasm`, but has no wasm libc (`string.h`, `malloc`: wasi-libc, Zig or Emscripten).
  What makes it real work: Kotlin/Wasm's memory is not the C module's, so anything but numbers is copied
  through the module's `malloc`; and cinterop indexes LP64 hosts and has no wasm target since 1.9.20,
  while wasm32 is ILP32 — `long`, pointers and struct offsets need a wasm32 index. Plain Kotlin/JS has
  no `@WasmImport` and would go through a JS loader.
- **`ffm()` replaces JNI everywhere, generated once per API**: `generateFfm<Name>` (JDK) for
  `kotlin("jvm")` and `jvm()` targets, `generateFfmAndroid<Name>` (PanamaPort) for Android modules and
  multiplatform Android targets. The JDK's Java goes into the Gradle source set Kotlin creates per JVM
  compilation (`<target>Main`, compiled by `compile<Target>MainJava`); PanamaPort's through AGP's
  variant API — the multiplatform Android plugin's only with `withJava()`, and its Kotlin compile
  (`compileAndroidMain`, no `Kotlin` in the name) gets the dependency through KGP, not by task name.
- **Before Kotlin 2.1.20 a `jvm()` target has no Java source set** unless the build calls `withJava()`, so
  `addKmpJvmJava` calls it there, and the Java goes into `main` (`compileJava`) rather than `jvmMain`.
- **JNI's sources leave `jvm()` lazily**, since targets are declared before `ffm()` is known — and a
  `project.provider { }` around a generated directory carries no producer, so the compile task's
  dependency on generation is spelled out alongside it. A missing one compiles before generating.

## Libraries in the jar

- **The loader is generated, not a runtime dependency**: `NativeLoaderSource` writes a Kotlin function
  into the JNI bindings and a `KonanNativeLoader` Java class beside jextract's output, which the exporter
  points jextract's `System.loadLibrary` at (JVM only — PanamaPort's Android bindings keep the plain
  call). `System.loadLibrary` first, so `java.library.path` still wins; then the jar's resource.
- **The resource layout is shared**: `BundledLibraries.RESOURCE_ROOT` (`konan/natives/<konan target>/`)
  lives in `src/exporterProtocol`, compiled into both the plugin that bundles and the exporter that
  writes the loader. The loader maps `os.name`/`os.arch` to Kotlin/Native target names itself.

## FFM on Android

- **jextract's output only needs its packages moved** onto PanamaPort: `java.lang.foreign` →
  `com.v7878.foreign`, `VarHandle` → `com.v7878.invoke.VarHandle` (a single-type import beside
  jextract's `java.lang.invoke.*`). The exporter does it for `FfmApi.PANAMA_PORT`.
- PanamaPort's AAR metadata **requires `compileSdk` 37**; jextract's output **needs Java 21 source**
  (switch patterns, which D8 desugars). Neither is the plugin's to set.
- **One index, the host's**: the generated layouts are LP64, so Android FFM links only 64-bit ABIs.
- **`ffm()` decides consumers after configuration**, but AGP callbacks are registered eagerly — so
  `AgpWiring` takes an `enabled` check evaluated inside `onVariants`, where configure blocks have run.
  JNI's Android wiring is gated the same way, since FFM replaces it.

## Kotlin DSL gotchas

- The `kotlin-dsl` plugin adds a `T.() -> Unit` overload of `whenObjectAdded`, making the SAM form
  ambiguous — use an object expression.
- Do not add an *extension* function and a property of the same name on a foreign receiver: `x.foo {}`
  then reads as either the call or property-plus-`invoke`, and the IDE flags a suspicious receiver (the
  old `KotlinCompilation.jvmInterops`). Members `val jni` plus `fun jni(Action)` are Gradle's own
  pattern for nested objects and are fine.
- Receivers shadow: `name` inside a `tasks.register(…) { }` block is the task's name, not the enclosing
  object's (a path built from it landed in `generated/konan/generateJniMymath/…`), and `executable`
  inside `exec { }` is the `ExecSpec`'s, not the task's. Read such values into differently named locals
  before the block.
- A nested settings object must be created through `ObjectFactory`; Gradle will not synthesise
  `abstract val cmake: CMakeSettings`.

## The index exporter

Bindings are generated from cinterop's own C model (`NativeIndex`), inside the Kotlin/Native
distribution's JVM: the exporter (`src/indexExporter`) indexes headers with cinterop's code and runs a
`BindingGenerator` over the index in the same process. Only generated files leave it; the plugin never
holds a `NativeIndex`.

- **One exporter per Kotlin/Native version.** It runs only in the distribution whose compiler it was
  compiled against, so it is compiled once per entry of `kotlinNativeVersions` in `gradle.properties` —
  every patch release from 2.0.0 — into `native-index-exporter-<version>.jar`, and `runIndexExporter` runs
  the one matching `konanHome`'s version (its compiler jar's `Implementation-Version`); any other version
  is an error. A jar per patch, not per minor: the `$default` overloads of cinterop's functions change
  shape between patches. Each variant compiles with `apiVersion` of its own minor, since it runs on that
  distribution's stdlib. `kotlinNativeVersion` is the default: `kotlin-native-utils`, KGP and the tests.
- **What differs between versions lives in `src/indexExporter/compat`**, one directory per facet variant,
  picked by a version threshold in `native-index.gradle.kts` (its README has the table). The shared
  sources call the same declarations whichever variant they got. When a Kotlin release breaks the
  exporter, add a variant and a threshold; check every version with
  `./gradlew compileIndexExporter<version>Kotlin`. Variants compile non-incrementally, two at a time: each
  holds a compiler jar, and twenty in parallel exhaust the heap.
- **Adding a Kotlin release**: append it to `kotlinNativeVersions`; if it compiles, run
  `GenerateFromNativeIndexTest` with that distribution installed — its `@TestFactory` requires every
  installed version to generate the same JNI bindings as the default.
- **The exporter is its own source set** because it compiles against the compiler jar, and anything it
  links against exists only in the distribution.
- **It calls cinterop's `internal` functions** (`prepareTool`, `buildNativeLibrary`) by compiling with
  the compiler jar as a friend module (`friendPaths` in the buildSrc plugin). Java is not a way around
  `internal` here: `native` is a Java keyword, so Java cannot import any `org.jetbrains.kotlin.native.…`
  class. And Kotlin cannot see the jar's types through Java sources in the same source set.
- **Kotlin/Native bindings are not an exporter generator.** `KotlinGradlePlugin.declareCinterops` adapts a
  binding to KGP's own `cinterops {}`, so the klib comes from the consumer's Kotlin version — a klib is a compiler
  artifact, unlike the sources the exporter writes. Running cinterop's pipeline in the exporter works
  (`Interop().interop("native", …)`), but would pin the klib to `kotlinNativeVersion`; and its native
  allocator setup must not nest inside the exporter's, which is why the index is built lazily.
- **The request is typed on both sides.** `src/exporterProtocol` (`GeneratorRequest`,
  `ExporterInvocation`) is compiled into both main and the exporter, so the command line is encoded and
  decoded by the same code. Add a generator by adding a request subclass; the `when` dispatch is
  exhaustive.
- **It runs through `javaexec`, not the Worker API.** A process-isolated worker puts Gradle's Kotlin
  stdlib and the plugin's classes next to the compiler jar: the compiler then ran on Gradle 9.8's
  stdlib 2.4.10 instead of its bundled 2.4.20. The daemon is out too: KGP's cinterop may already have
  loaded libclang there, and a native library loads in one classloader per JVM.

## Testing and publishing

`-PtestGradleVersion=8.7 -PkgpTestVersion=2.0.0` runs the suite as a Kotlin 2.0 project would: TestKit on
that Gradle, that KGP on the plugin-under-test classpath. Run it after touching the KGP adapter.

The TestKit functional tests are what catch decoration and classloading failures, because they apply
the plugin to a project with neither KGP nor AGP. Keep at least one fixture like that.

`withPluginClasspath()` loads the plugin under test where a plugin the test build applies on its own
(`kotlin("multiplatform") version …`) cannot see it: code reaching KGP's types fails with
`NoClassDefFoundError`, though a real build loads both together. A test that needs KGP passes it on the
plugin-under-test classpath itself (`withPluginClasspathAndKgp()` in `TestKitSupport.kt`); never add KGP to
the shared metadata, or the no-KGP fixtures stop proving anything.

Portal versions are immutable — bump and publish, never reuse an alpha. `checkVersionUnpublished` runs before
`publishToMavenLocal` and `publishPlugins` and fails when the version is already on the Portal: a local
copy of a published version shadowed the Portal's for koinference and broke it overnight (#8). Consumers with `mavenLocal()`
ahead of the portal will silently use a local build instead, so a version that "works locally" proves
nothing about what CI resolves. Verify a publish by configuring a throwaway project with only
`gradlePluginPortal()`.
