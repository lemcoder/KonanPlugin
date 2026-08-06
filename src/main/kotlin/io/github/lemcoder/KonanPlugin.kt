package io.github.lemcoder

import io.github.lemcoder.jvm.JvmInteropSupport
import io.github.lemcoder.interop.JvmInteropRegistry
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

/**
 * Default clang arguments for [RunKonanClangTask]; `konanConfig.additionalCompilerArgs` is appended.
 *
 * No `-std=` here: sources are compiled in one invocation, so a C standard would also reach any `.cpp`
 * in the source dir, which clang rejects outright ("invalid argument '-std=c99' not allowed with
 * 'C++'"). Builds that need a specific standard set it via `additionalCompilerArgs`.
 *
 * `-fPIC` because the archive is routinely linked into a shared library — without it an x86_64
 * Android build fails with "relocation R_X86_64_PC32 cannot be used against symbol …".
 */
private val DEFAULT_COMPILER_ARGS = listOf("-fno-sanitize=undefined", "-fPIC")

/**
 * The `clean` task as a lazy collection — empty when the base plugin isn't applied, so it is safe to
 * pass to `mustRunAfter` unconditionally.
 */
internal fun Project.cleanTask() = tasks.matching { it.name == "clean" }

abstract class KonanPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // konanConfig owns compilation only, mirroring Kotlin/Native: C/C++ -> static .a per target.
        val extension = project.extensions.create("konanConfig", KonanPluginExtension::class.java)

        project.applyConventions(extension)
        project.registerRunKonanClang(extension)
        // Holds the per-compilation `jvmInterops` containers; see JvmInterops.kt for the DSL.
        project.extensions.create("jvmInteropRegistry", JvmInteropRegistry::class.java, project)
    }
}

/** Conventions for everything that can be inferred, so a build declares only what is project-specific. */
private fun Project.applyConventions(ext: KonanPluginExtension) {
    ext.sourceDir.convention("native")
    ext.headerDir.convention(ext.sourceDir)
    ext.libName.convention(name)
    ext.outputDir.convention("build/native")
    // Auto-detected: $KONAN_HOME, else the newest ~/.konan/kotlin-native-prebuilt-*. Absent rather
    // than failing when there is none: the convention is queried whenever the configuration cache is
    // stored, so throwing here breaks configuration of builds that never touch a konan task. The
    // tasks that need it fail at execution instead, and say what to do.
    ext.konanPath.convention(providers.provider { JvmInteropSupport.findKonanHome()?.absolutePath })
}

private fun Project.registerRunKonanClang(ext: KonanPluginExtension) {
    tasks.register("runKonanClang", RunKonanClangTask::class.java) {
        group = "interop"
        description = "Cross-compile the C/C++ sources to a static library per target."

        // `clean` and this task are otherwise unordered, and the scratch dir this one writes to lives
        // under build/tmp — so `gradle clean assembleDebug` can delete it mid-compile.
        mustRunAfter(cleanTask())

        // All wiring is lazy: the task can be registered even when konanConfig is left empty (e.g. a
        // project that only consumes the JNI leg), and only fails if it is actually asked to run.
        targets.set(ext.targets)
        outputDir.set(ext.outputDir.map { layout.projectDirectory.dir(it) })
        sourceFiles.from(ext.sourceDir.map { layout.projectDirectory.dir(it).asFileTree })
        includeDirs.from(ext.headerDir.map { layout.projectDirectory.dir(it) })
        libName.set(ext.libName)

        arguments.addAll(DEFAULT_COMPILER_ARGS)
        arguments.addAll(ext.additionalCompilerArgs)

        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val scriptPath = if (isWindows) "bin/run_konan.bat" else "bin/run_konan"
        runKonan.fileProvider(ext.konanPath.map { File(it).resolve(scriptPath) })

        onlyIf { targets.get().isNotEmpty() }
    }
}
