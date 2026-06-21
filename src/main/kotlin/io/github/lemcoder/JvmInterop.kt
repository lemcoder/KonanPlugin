package io.github.lemcoder

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

// ===========================================================================
// Public DSL
// ===========================================================================

/**
 * Configuration for generating JVM/Android **JNI** bindings from C headers, using the JVM flavor of
 * Kotlin/Native's `cinterop` stub generator. Configured via the `jvmInterop { }` block.
 *
 * The plugin produces two tiers of output for each library:
 *  - `generateJvmInterop` -> runtime-free Kotlin `external fun kniBridgeN(...)` declarations plus the
 *    JNI `.c` bridges. The Kotlin is stripped of `kotlinx.cinterop`, so it compiles and runs on plain
 *    JVM and Android with no extra runtime.
 *  - `linkJvmInterop` -> a self-contained shared library `lib<name>stubs.{so,dylib,dll}` per target,
 *    the JNI `.c` compiled and statically linked against [staticLibraryName].
 *
 * The user is expected to write the idiomatic `expect`/`actual` API on top of the generated bridges.
 */
interface JvmInteropExtension {
    /** Header file names (resolved against [headerDir]) to bind, e.g. `["mymath.h"]`. */
    val headers: ListProperty<String>

    /** Kotlin package for the generated bindings, e.g. `"io.example.mymath"`. */
    val packageName: Property<String>

    /** `.def` `headerFilter` glob. Optional; defaults to the header names. */
    val headerFilter: Property<String>

    /** Directory (relative to the project) holding the headers and used as the `-I` include root. */
    val headerDir: Property<String>

    /** Targets to build the JNI shared library for, e.g. `["macos_arm64", "android_arm64"]`. */
    val targets: ListProperty<String>

    /**
     * Root directory containing the per-target static libraries to link into the JNI stub, laid out as
     * `<staticLibraryDir>/<target>/lib<staticLibraryName>.a` (the layout `runKonanClang` produces).
     */
    val staticLibraryDir: Property<String>

    /** Base name of the static library to link (without `lib` prefix / `.a` suffix). */
    val staticLibraryName: Property<String>

    /** Root of the Kotlin/Native distribution. Defaults to the newest `~/.konan/kotlin-native-prebuilt-*` or `$KONAN_HOME`. */
    val konanPath: Property<String>

    /** A JDK home that ships `include/jni.h` (host targets only). Defaults to a scan of installed JDKs. */
    val jniHome: Property<String>

    /** Extra `-compiler-option` values forwarded to the binding generator's clang. */
    val additionalCompilerArgs: ListProperty<String>

    /** Extra arguments appended to the native link command (per target). */
    val additionalLinkerArgs: ListProperty<String>
}

// ===========================================================================
// Wiring
// ===========================================================================

internal fun Project.registerJvmInterop(ext: JvmInteropExtension) {
    val konanHome = providers.provider {
        ext.konanPath.orNull?.let { File(it) } ?: JvmInteropSupport.detectKonanHome()
    }
    val jniIncludeDirs = providers.provider {
        ext.jniHome.orNull?.let { JvmInteropSupport.jniIncludeDirsOf(File(it)) ?: error("No include/jni.h under ${ext.jniHome.get()}") }
            ?: JvmInteropSupport.detectJniIncludeDirs()
    }

    val generatedRoot = layout.buildDirectory.dir("generated/jvmInterop")
    val jniLibsRoot = layout.buildDirectory.dir("jvmInterop/jniLibs")

    val generate = tasks.register("generateJvmInterop", GenerateJvmInteropTask::class.java) {
        group = "interop"
        description = "Generate runtime-free JNI Kotlin bindings + .c stubs from C headers."
        this.konanPath.set(konanHome.map { it.absolutePath })
        this.headers.set(ext.headers)
        this.packageName.set(ext.packageName)
        this.headerFilter.set(ext.headerFilter)
        this.headerDir.set(ext.headerDir.map { layout.projectDirectory.dir(it).asFile.absolutePath })
        // The generator indexes headers once on the host; the produced bridges are platform-independent.
        this.hostTarget.set(JvmInteropSupport.hostTarget())
        this.jniIncludeDirs.set(jniIncludeDirs.map { dirs -> dirs.map { it.absolutePath } })
        this.additionalCompilerArgs.set(ext.additionalCompilerArgs)
        this.outputDirectory.set(generatedRoot)
    }

    val link = tasks.register("linkJvmInterop") {
        group = "interop"
        description = "Compile + link the JNI stub shared library for every configured target."
    }

    // One link task per target; the umbrella `linkJvmInterop` depends on all of them.
    afterEvaluate {
        // jvmInterop is optional — skip all wiring when the project doesn't configure it.
        if (!ext.packageName.isPresent) return@afterEvaluate

        val cFile = generatedRoot.map { it.dir("c").file("${JvmInteropSupport.stubBaseName(ext.packageName.get())}.c") }
        val stubBase = JvmInteropSupport.stubBaseName(ext.packageName.get())
        ext.targets.get().forEach { target ->
            val linkTarget = tasks.register("linkJvmInterop${target.replaceFirstChar { it.uppercase() }}", LinkJvmInteropTask::class.java) {
                group = "interop"
                description = "Link lib$stubBase for $target."
                dependsOn(generate)
                this.konanPath.set(konanHome.map { it.absolutePath })
                this.target.set(target)
                this.stubBaseName.set(stubBase)
                this.stubCFile.set(cFile)
                this.headerDir.set(ext.headerDir.map { layout.projectDirectory.dir(it).asFile.absolutePath })
                this.staticLibrary.set(
                    ext.staticLibraryDir.flatMap { dir ->
                        ext.staticLibraryName.map { name ->
                            layout.projectDirectory.file("$dir/$target/lib$name.a")
                        }
                    }
                )
                if (target.startsWith("android")) {
                    this.ndkResourceDir.set(JvmInteropSupport.ndkResourceDir(konanHome.get())?.absolutePath ?: "")
                } else {
                    this.jniIncludeDirs.set(jniIncludeDirs.map { dirs -> dirs.map { it.absolutePath } })
                }
                this.additionalLinkerArgs.set(ext.additionalLinkerArgs)
                this.outputDirectory.set(jniLibsRoot.map { it.dir(JvmInteropSupport.abiDir(target)) })
            }
            link.configure { dependsOn(linkTarget) }
        }

        // Auto-wire: run generation before compilation. (Add the generated `kotlin/` dir as a source
        // directory in your build — see the examples — since the source set differs per project type.)
        tasks.matching { it.name == "compileKotlin" || it.name == "compileJava" || it.name == "preBuild" }
            .configureEach { dependsOn(generate) }
    }
}

// ===========================================================================
// Tasks
// ===========================================================================

/** Generates the JNI `.c` stubs and the stripped, runtime-free Kotlin bindings. */
abstract class GenerateJvmInteropTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:Input abstract val konanPath: Property<String>
    @get:Input abstract val headers: ListProperty<String>
    @get:Input abstract val packageName: Property<String>
    @get:Input @get:Optional abstract val headerFilter: Property<String>
    @get:Input abstract val headerDir: Property<String>
    @get:Input abstract val hostTarget: Property<String>
    @get:Input abstract val jniIncludeDirs: ListProperty<String>
    @get:Input @get:Optional abstract val additionalCompilerArgs: ListProperty<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun run() {
        val konanHome = File(konanPath.get())
        val embeddableJar = konanHome.resolve("konan/lib/kotlin-native-compiler-embeddable.jar")
        check(embeddableJar.isFile) { "Embeddable compiler jar not found: $embeddableJar" }
        val nativeLibDir = konanHome.resolve("konan/nativelib")

        val out = outputDirectory.get().asFile
        out.deleteRecursively(); out.mkdirs()

        // Synthesize the .def from the configured headers + package.
        val defFile = out.resolve("${JvmInteropSupport.stubBaseName(packageName.get())}.def")
        defFile.writeText(buildString {
            appendLine("headers = ${headers.get().joinToString(" ")}")
            appendLine("headerFilter = ${headerFilter.orNull ?: headers.get().joinToString(" ")}")
            appendLine("package = ${packageName.get()}")
        })

        val includeOpts = (listOf(headerDir.get()) + jniIncludeDirs.get())
            .map { File(it) }.filter { it.exists() }
            .flatMap { listOf("-compiler-option", "-I${it.absolutePath}") }
        val extraOpts = additionalCompilerArgs.getOrElse(emptyList()).flatMap { listOf("-compiler-option", it) }

        val result = exec.execCapture {
            executable("java")
            args(
                "-ea",
                "-Dkonan.home=${konanHome.absolutePath}",
                "-Djava.library.path=${nativeLibDir.absolutePath}",
                "-cp", embeddableJar.absolutePath,
                "org.jetbrains.kotlin.native.interop.gen.jvm.MainKt",
                "-flavor", "jvm",
                "-def", defFile.absolutePath,
                "-generated", out.resolve("kotlin").absolutePath,
                "-Xtemporary-files-dir", out.resolve("c").absolutePath,
                "-target", hostTarget.get(),
            )
            args(includeOpts); args(extraOpts)
            environment("LIBCLANG_DISABLE_CRASH_RECOVERY", "1")
        }
        logger.lifecycle(result.output)
        result.assertNormalExitValue()

        // Strip kotlinx.cinterop from the generated Kotlin so it ships on plain JVM / Android.
        out.resolve("kotlin").walkTopDown().filter { it.extension == "kt" }.forEach { kt ->
            kt.writeText(JvmInteropSupport.stripCinterop(kt.readText()))
        }
    }
}

/** Compiles the generated `.c` and statically links the target's `.a` into a shared JNI library. */
abstract class LinkJvmInteropTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:Input abstract val konanPath: Property<String>
    @get:Input abstract val target: Property<String>
    @get:Input abstract val stubBaseName: Property<String>
    @get:InputFile abstract val stubCFile: org.gradle.api.file.RegularFileProperty
    @get:Input abstract val headerDir: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val staticLibrary: org.gradle.api.file.RegularFileProperty
    @get:Input @get:Optional abstract val jniIncludeDirs: ListProperty<String>
    @get:Input @get:Optional abstract val ndkResourceDir: Property<String>
    @get:Input @get:Optional abstract val additionalLinkerArgs: ListProperty<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun run() {
        val konanHome = File(konanPath.get())
        val tgt = target.get()
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val runKonan = konanHome.resolve(if (isWindows) "bin/run_konan.bat" else "bin/run_konan")
        check(runKonan.isFile) { "run_konan not found: $runKonan" }

        val outDir = outputDirectory.get().asFile
        outDir.deleteRecursively(); outDir.mkdirs()
        val outLib = outDir.resolve("lib${stubBaseName.get()}${JvmInteropSupport.sharedLibExt(tgt)}")

        val cmd = buildList {
            add(runKonan.absolutePath); add("clang"); add("clang"); add(tgt)
            add("-shared")
            add("-I${headerDir.get()}")
            if (tgt.startsWith("android")) {
                // jni.h comes from the NDK sysroot; supply compiler-rt and skip the (absent) unwinder.
                ndkResourceDir.orNull?.takeIf { it.isNotEmpty() }?.let { add("-resource-dir=$it") }
                add("--unwindlib=none")
            } else {
                jniIncludeDirs.getOrElse(emptyList()).forEach { add("-I$it") }
            }
            add(stubCFile.get().asFile.absolutePath)
            add(staticLibrary.get().asFile.absolutePath)
            add("-o"); add(outLib.absolutePath)
            addAll(additionalLinkerArgs.getOrElse(emptyList()))
        }

        val result = exec.execCapture { commandLine(cmd) }
        logger.lifecycle(result.output)
        result.assertNormalExitValue()
        logger.lifecycle("JNI stub library: $outLib")
    }
}
