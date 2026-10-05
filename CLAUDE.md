# Working on this plugin

Gradle and KGP/AGP internals that cost time to discover. The README documents the DSL; this is what
bites while changing it.

## Gradle decorates classes at apply time

Any type appearing in a **decorated signature** — extension properties, task properties — is
resolved the moment the plugin is applied, in *every* project that applies it.

- `KotlinCompilation` and `KotlinTarget` are neither `ExtensionAware` nor open to third-party DSL
  blocks, which is why `jvmInterops` is an extension function over a project-scoped registry rather
  than a real extension. Putting a KGP type in a registry method signature fails with
  *"Could not generate a decorated class … KotlinCompilation"* in any project without KGP. The
  registry takes a `SourceWiring` callback instead.
- `kotlin-native-utils` is an `implementation` dependency, not the compile-only it arrives as
  transitively: `KonanTarget` appears in decorated DSL signatures, and the published POM previously
  declared no dependencies at all.
- AGP types are only touched inside `plugins.withId(...)` blocks. A plain reference in an eagerly
  executed method is `NoClassDefFoundError: KotlinMultiplatformAndroidComponentsExtension` in a
  plugin-only build. The KMP Android plugin does **not** apply `com.android.base`, so both ids need
  handling.

## Ordering rules that are not obvious

- **Configuration cache resolves conventions when it stores.** A `konanPath` convention that threw
  broke *configuration*; one that returned absent got the absent value cached before KGP had
  downloaded the distribution. Toolchain detection happens in the task action.
- **`whenObjectAdded` fires before `create(name) { … }` runs its configure block.** Reading
  `settings.targets` there gives the default, not what the build declared — per-target task
  registration is deferred to `afterEvaluate`.
- **AGP variant callbacks must be registered eagerly.** From `afterEvaluate` AGP refuses with
  *"It is too late to add actions as the callbacks already executed"*, so the jniLibs wiring runs at
  interop-registration time and tolerates an empty directory.
- **A CMake build task needs `CMakeCache.txt` as an input.** Otherwise changing a configure argument
  re-runs configure, leaves the build `UP-TO-DATE`, and silently keeps the previous library — a
  linker flag appears to do nothing.

## Layout and naming contracts

- **Only ABI-named directories may sit under `jniLibs`.** AGP reads each subdirectory as an ABI, so
  a host library in the root fails the merge with *"… is not an ABI"*. Host builds write to
  `jvmInterop/<name>/lib/`. This only reproduces on Linux: AGP ignores `.dylib`.
- **`Family.dynamicSuffix` has no leading dot.** `"${prefix}$name${suffix}"` yields
  `libfoostubsdylib`.
- **The stub library name is derived from the binding package** and baked into the generated
  `System.loadLibrary`. It, and the stub directory, are reported by the task
  (`stubLibraryBaseName`, `stubSourceDirectory`, `kotlinSourceDirectory`) — consumers must never
  reconstruct them, and `KONAN_JNI_STUB_DIR` / `KONAN_JNI_LIB_NAME` are the external-build contract.
- **`cmake --preset` resolves `CMakePresets.json` from the working directory**, so configure runs
  from the CMakeLists directory rather than the project root.

## The generated bindings

The `jni` generator (`src/indexExporter/.../JniGenerator.kt`) writes both sides of each bridge from
cinterop's `NativeIndex`: the Kotlin `external fun` named after the C function, and the `JNIEXPORT`
function that marshals strings, primitive buffers and structs by value. cinterop's own JVM flavor is
not used — it emits address-only bridges, every pointer a raw `Long`.

Bindings are `internal` by default and each carries **`@JvmName`**. JNI resolves the C symbol from the
unmangled method name, so a mangled internal name compiles, links, and dies on the first call.

## Kotlin DSL gotchas

- The `kotlin-dsl` plugin adds a `T.() -> Unit` overload of `whenObjectAdded`, making the SAM form
  ambiguous — use an object expression.
- Do not expose both a function and a property with the same name (`jvmInterops`): `x.jvmInterops {}`
  then reads as either the call or property-plus-`invoke`, and the IDE flags a suspicious receiver.
  The read-back accessor is `jvmInteropsContainer()`.
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
- **Kotlin/Native bindings are not an exporter generator.** `kotlinNativeInterop` adapts a binding to
  KGP's own `cinterops {}`, so the klib comes from the consumer's Kotlin version — a klib is a compiler
  artifact, unlike the sources the exporter writes. Running cinterop's pipeline in the exporter works
  (`Interop().interop("native", …)`), but would pin the klib to `kotlinNativeVersion`; and its native
  allocator setup must not nest inside the exporter's, which is why the index is built lazily.
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
plugin-under-test classpath itself (`kgp.classpath`, see `KotlinNativeInteropTest`); never add KGP to
the shared metadata, or the no-KGP fixtures stop proving anything.

Portal versions are immutable — bump and publish, never reuse an alpha. Consumers with `mavenLocal()`
ahead of the portal will silently use a local build instead, so a version that "works locally" proves
nothing about what CI resolves. Verify a publish by configuring a throwaway project with only
`gradlePluginPortal()`.
