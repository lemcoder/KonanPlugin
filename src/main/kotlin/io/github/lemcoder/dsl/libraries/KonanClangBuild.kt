package io.github.lemcoder.dsl.libraries

import io.github.lemcoder.dsl.config.ConfigField
import io.github.lemcoder.dsl.KonanExtension
import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.model.taskName
import io.github.lemcoder.util.cleanTask
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
 * The default library build: the sources in [NativeLibrarySpec.sourceDirectory], compiled with
 * Kotlin/Native's clang and archived with its llvm-ar — for Android targets, the NDK's.
 */
internal fun registerKonanClang(project: Project, konan: KonanExtension, library: NativeLibrarySpec): Map<NativeTarget, TaskProvider<*>> =
    library.distinctTargets.getOrElse(emptyList()).associateWith { target ->
        project.tasks.register(taskName("konanClang", library.name, target), CompileNativeLibraryTask::class.java) {
            group = "konan"
            description = "Compile the '${library.name}' C/C++ sources for ${target.konanName}, with " +
                (if (target.isAndroid) "the Android NDK's clang." else "Kotlin/Native's clang.")
            // `clean` and this task are otherwise unordered, and the scratch dir this one writes to lives
            // under build/tmp — so `gradle clean assembleDebug` can delete it mid-compile.
            mustRunAfter(project.cleanTask())

            this.target.set(target)
            outputDirectory.set(library.archiveDirectory(target))
            sourceFiles.from(library.sourceDirectory.map { it.asFileTree })
            includeDirs.from(library.sourceDirectory, library.includeDirs)
            libName.set(library.name)
            if (target.isAndroid) {
                androidNdk.set(konan.config.readConfigField(ConfigField.ANDROID_NDK, path))
                androidApiLevel.set(konan.androidApiLevel)
            }
            arguments.addAll(DEFAULT_COMPILER_OPTIONS)
            arguments.addAll(library.compilerOptions)
        }
    }
