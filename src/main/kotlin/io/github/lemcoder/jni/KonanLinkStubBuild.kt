package io.github.lemcoder.jni

import io.github.lemcoder.cleanTask
import io.github.lemcoder.dsl.BindingSpec
import io.github.lemcoder.dsl.KonanExtension
import io.github.lemcoder.hostKonanTarget
import io.github.lemcoder.isAndroid
import io.github.lemcoder.library.NativeArtifacts
import io.github.lemcoder.link.LinkSharedLibraryTask
import io.github.lemcoder.taskSuffix
import io.github.lemcoder.toolchain.NativeToolchain
import io.github.lemcoder.wiring.DefFile
import io.github.lemcoder.wiring.capitalized
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File

/**
 * [JniStubBuild.KonanLink]: the stub and the library's archive into one shared library per target, with
 * Kotlin/Native's toolchain.
 */
internal fun registerKonanLink(
    project: Project,
    konan: KonanExtension,
    binding: BindingSpec,
    inputs: JniStubInputs,
): List<TaskProvider<*>> {
    val bindingName = binding.name
    val suffix = bindingName.capitalized()
    val generate = inputs.generate
    val artifacts = inputs.artifacts
    val konanHome = konan.konanHome
    val jniIncludeDirs = project.providers.provider {
        binding.jni.jdkHome.orNull?.asFile
            ?.let { NativeToolchain.jniIncludeDirsOf(it) ?: error("No include/jni.h under $it") }
            ?: NativeToolchain.detectJniIncludeDirs()
    }
    return inputs.targets.map { target ->
        // Where a consumer looks for the library: the host's, the one a JVM running this build loads.
        if (target == hostKonanTarget()) binding.jni.libraryDirectory.convention(binding.jni.libraryDirectoryFor(target))

        project.tasks.register("linkJni$suffix${target.taskSuffix}", LinkSharedLibraryTask::class.java) {
            group = "konan"
            description = "Link the '$bindingName' JNI library for ${target.name}."
            dependsOn(generate)
            artifacts?.let { dependsOn(it.builtBy) }
            mustRunAfter(project.cleanTask())
            konanPath.set(konanHome.map { it.asFile.absolutePath })
            this.target.set(target)
            libraryBaseName.set(generate.flatMap { it.stubLibraryBaseName })
            stubCFile.set(generate.flatMap { it.stubSourceFile })
            includeDirs.from(binding.includeDirs)
            // A plain path, not a tracked directory: a .def at the project root would make the whole
            // project, build/ included, an input of this task.
            includePaths.add(binding.defFile.map { it.asFile.parentFile.absolutePath })
            staticLibrary.set(project.layout.file(project.provider { archive(binding, artifacts, target) }))
            if (target.isAndroid) {
                ndkResourceDir.set(konanHome.map { NativeToolchain.ndkResourceDir(it.asFile)?.absolutePath ?: "" })
            } else {
                this.jniIncludeDirs.set(jniIncludeDirs.map { dirs -> dirs.map { it.absolutePath } })
            }
            additionalLinkerArgs.set(
                binding.jni.linkerOptions.zip(binding.jni.targetLinkerOptions) { common, perTarget ->
                    common + perTarget[target].orEmpty()
                }
            )
            outputDirectory.set(binding.jni.libraryDirectoryFor(target))
            // From the task's own property: a closure over the artifacts would drag their TaskProvider into
            // the configuration cache.
            onlyIf {
                staticLibrary.isPresent || run {
                    logger.lifecycle(
                        "konan.bindings.$bindingName: no library(), jni.archive or staticLibraries in the .def, so only " +
                            "the JNI bindings and the .c stub were generated — link them from your own build."
                    )
                    false
                }
            }
        }
    }
}

/**
 * The archive to link: `jni.archive` if set, else the library's for [target], else the `.def`'s
 * `staticLibraries`.
 */
private fun archive(binding: BindingSpec, artifacts: NativeArtifacts?, target: KonanTarget): File? {
    binding.jni.archive.orNull?.asFile?.let { return it.takeIf { f -> f.isFile } }
    artifacts?.let {
        return it.archive(target).get().asFile.takeIf { _ -> target in it.targets.getOrElse(emptyList()) }
    }
    val def = binding.defFile.orNull?.asFile?.takeIf { it.isFile } ?: return null
    return DefFile.parse(def).resolveStaticLibraries(def.parentFile).firstOrNull()
}
