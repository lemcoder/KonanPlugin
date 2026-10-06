package io.github.lemcoder.jni

import io.github.lemcoder.dsl.BindingSpec
import io.github.lemcoder.dsl.KonanExtension
import io.github.lemcoder.link.LinkSharedLibraryTask
import io.github.lemcoder.model.DefFile
import io.github.lemcoder.model.NativeArtifacts
import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.model.taskName
import io.github.lemcoder.toolchain.Jdk
import io.github.lemcoder.util.cleanTask
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
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
    val generate = inputs.generate
    val artifacts = inputs.artifacts
    val jniIncludeDirs = project.providers.provider {
        binding.jni.jdkHome.orNull?.asFile
            ?.let { Jdk.jniIncludeDirectoriesOf(it) ?: error("No include/jni.h under $it") }
            ?: Jdk.detectJniIncludeDirectories()
    }
    return inputs.targets.map { target ->
        // Where a consumer looks for the library: the host's, the one a JVM running this build loads.
        if (target == NativeTarget.host) binding.jni.libraryDirectory.convention(binding.jni.libraryDirectoryFor(target))

        project.tasks.register(taskName("linkJni", bindingName, target), LinkSharedLibraryTask::class.java) {
            group = "konan"
            description = "Link the '$bindingName' JNI library for ${target.konanName}."
            dependsOn(generate)
            artifacts?.let { dependsOn(it.builtBy) }
            mustRunAfter(project.cleanTask())
            konanHome.set(konan.konanHome)
            this.target.set(target)
            libraryBaseName.set(generate.flatMap { it.stubLibraryBaseName })
            stubCFile.set(generate.flatMap { it.stubSourceFile })
            includeDirs.from(binding.includeDirs)
            // A plain path, not a tracked directory: a .def at the project root would make the whole
            // project, build/ included, an input of this task.
            includePaths.add(binding.defFile.map { it.asFile.parentFile.absolutePath })
            staticLibrary.set(project.layout.file(project.provider { archive(binding, artifacts, target) }))
            if (!target.isAndroid) this.jniIncludeDirs.from(jniIncludeDirs)
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
private fun archive(binding: BindingSpec, artifacts: NativeArtifacts?, target: NativeTarget): File? {
    binding.jni.archive.orNull?.asFile?.let { return it.takeIf { f -> f.isFile } }
    artifacts?.let {
        return it.archive(target).get().asFile.takeIf { _ -> target in it.targets.getOrElse(emptyList()) }
    }
    val def = binding.defFile.orNull?.asFile?.takeIf { it.isFile } ?: return null
    return DefFile.parse(def).resolveStaticLibraries(def.parentFile).firstOrNull()
}
