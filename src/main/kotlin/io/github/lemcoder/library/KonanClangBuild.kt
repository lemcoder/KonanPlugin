package io.github.lemcoder.library

import io.github.lemcoder.cleanTask
import io.github.lemcoder.dsl.KonanExtension
import io.github.lemcoder.dsl.NativeLibrarySpec
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/**
 * No `-std=` here: sources are compiled in one invocation, so a C standard would also reach any `.cpp`
 * in the source dir, which clang rejects outright ("invalid argument '-std=c99' not allowed with
 * 'C++'"). Builds that need a specific standard set it via `compilerOptions`.
 *
 * `-fPIC` because the archive is routinely linked into a shared library — without it an x86_64
 * Android build fails with "relocation R_X86_64_PC32 cannot be used against symbol …".
 */
private val DEFAULT_COMPILER_OPTIONS = listOf("-fno-sanitize=undefined", "-fPIC")

/**
 * [LibraryProvider.KonanClang]: the sources in [NativeLibrarySpec.sourceDirectory], compiled with
 * Kotlin/Native's clang and archived with its llvm-ar.
 */
internal fun registerKonanClang(project: Project, konan: KonanExtension, library: NativeLibrarySpec): TaskProvider<*> =
    project.tasks.register("konanClang${library.name.replaceFirstChar { it.uppercase() }}", CompileNativeLibraryTask::class.java) {
        group = "konan"
        description = "Compile the '${library.name}' C/C++ sources with Kotlin/Native's clang."
        // `clean` and this task are otherwise unordered, and the scratch dir this one writes to lives
        // under build/tmp — so `gradle clean assembleDebug` can delete it mid-compile.
        mustRunAfter(project.cleanTask())

        targets.set(library.targets)
        outputDir.set(library.outputDirectory)
        sourceFiles.from(library.sourceDirectory.map { it.asFileTree })
        includeDirs.from(library.sourceDirectory, library.includeDirs)
        libName.set(library.name)
        arguments.addAll(DEFAULT_COMPILER_OPTIONS)
        arguments.addAll(library.compilerOptions)

        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val script = if (isWindows) "bin/run_konan.bat" else "bin/run_konan"
        runKonan.set(konan.konanHome.file(script))

        onlyIf { targets.get().isNotEmpty() }
    }
