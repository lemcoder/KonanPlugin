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
- **One home per concept.** `model/` holds the domain (targets, naming, the artifact layout, the
  external-build contract, `selectOne`), `kotlin/` the touchpoints with Kotlin, `toolchain/` the
  others (JDK, Xcode, executables, Zig). Task names come from `taskName(...)`, OS checks from
  `HostOs`, the distribution's layout from `KonanDistribution` — add to those, don't re-derive.
  `BindingVisibility` lives in `src/exporterProtocol` so the DSL and the generator share one enum.
- AGP types are only touched inside `plugins.withId(...)` blocks. A plain reference in an eagerly
  executed method is `NoClassDefFoundError: KotlinMultiplatformAndroidComponentsExtension` in a
  plugin-only build. The KMP Android plugin does **not** apply `com.android.base`, so both ids need
  handling.

## Ordering rules that are not obvious

- **Configuration cache resolves conventions when it stores.** A `konanHome` convention that threw
  broke *configuration*; one that returned absent got the absent value cached before KGP had
  downloaded the distribution. Toolchain detection happens in the task action.
- **`all`/`whenObjectAdded` fires before `create(name) { … }` runs its configure block.** Reading a
  binding's `library` or a library's `targets` there gives the default, not what the build declared —
  per-target tasks, consumer detection and KGP's cinterops are deferred to `afterEvaluate` (KGP
  accepts cinterops declared there). Both sides follow this split: a `LibraryProvider` registers
  after evaluation (its block's conventions are set in the DSL constructors); a `BindingConsumer`
  (`consumer/`) has `prepare()` for the eager part, then `isUsed()` and `register()` after evaluation.
- **Build systems are sealed hierarchies with a shared `External` branch** — `LibraryProvider` for
  libraries, `JniStubBuild` for the JNI stub. Every external one is `ExternalBuildTask` per target
  (`external/ExternalBuilds.kt`); a preset (Zig, CMake) only adds commands. Prefer a preset over a new
  task type.
- **AGP variant callbacks must be registered eagerly.** From `afterEvaluate` AGP refuses with
  *"It is too late to add actions as the callbacks already executed"*, so the jniLibs wiring runs at
  binding-registration time and tolerates an empty directory.
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
- **The stub library name is derived from the binding package** and baked into the generated
  `System.loadLibrary`. It, and the stub directory, are reported by the task
  (`stubLibraryBaseName`, `stubSourceDirectory`, `kotlinSourceDirectory`) — consumers must never
  reconstruct them. The external-build contract is `ExternalStubVariable` (`KONAN_JNI_STUB_DIR`,
  `KONAN_JNI_LIB_NAME`, `KONAN_JNI_OUTPUT_DIR`, `KONAN_TARGET`, `KONAN_JNI_ABI`, `JAVA_HOME`): every
  `JniStubBuild.External` gets it as environment in `External.register`; a subclass only adds commands
  (CMake also passes it as cache entries). The same values are on `JniSpec` as providers.
- **`cmake --preset` resolves `CMakePresets.json` from the working directory**, so configure runs
  from the CMakeLists directory rather than the project root.

## The generated bindings

The `jni` generator (`src/indexExporter/.../JniGenerator.kt`) writes both sides of each bridge from
cinterop's `NativeIndex`: the Kotlin `external fun` named after the C function, and the `JNIEXPORT`
function that marshals strings, primitive buffers and structs by value. cinterop's own JVM flavor is
not used — it emits address-only bridges, every pointer a raw `Long`.

Bindings are `internal` by default and each carries **`@JvmName`**. JNI resolves the C symbol from the
unmangled method name, so a mangled internal name compiles, links, and dies on the first call.

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

- **One version everywhere.** `kotlinNativeVersion` in `gradle.properties` sets `kotlin-native-utils`,
  the compiler jar the exporter compiles against, and the distribution it must run in. The wiring lives
  in `buildSrc/src/main/kotlin/native-index.gradle.kts`.
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

The TestKit functional tests are what catch decoration and classloading failures, because they apply
the plugin to a project with neither KGP nor AGP. Keep at least one fixture like that.

`withPluginClasspath()` loads the plugin under test where a plugin the test build applies on its own
(`kotlin("multiplatform") version …`) cannot see it: code reaching KGP's types fails with
`NoClassDefFoundError`, though a real build loads both together. A test that needs KGP passes it on the
plugin-under-test classpath itself (`withPluginClasspathAndKgp()` in `TestKitSupport.kt`); never add KGP to
the shared metadata, or the no-KGP fixtures stop proving anything.

Portal versions are immutable — bump and publish, never reuse an alpha. Consumers with `mavenLocal()`
ahead of the portal will silently use a local build instead, so a version that "works locally" proves
nothing about what CI resolves. Verify a publish by configuring a throwaway project with only
`gradlePluginPortal()`.
