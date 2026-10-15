package io.github.lemcoder.bindings.jni

import io.github.lemcoder.bindings.LinkSharedLibraryTask
import io.github.lemcoder.dsl.KonanExtension
import io.github.lemcoder.dsl.bindings.BindingSpec
import io.github.lemcoder.index.GenerateFromNativeIndexTask
import io.github.lemcoder.index.GeneratorRequest
import io.github.lemcoder.model.DefFile
import io.github.lemcoder.model.NativeArtifacts
import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.model.taskName
import io.github.lemcoder.util.cleanTask
import java.io.File
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/**
 * The JNI library's default build: the generated stub and the library's archive into one shared library
 * per target, with Kotlin/Native's toolchain.
 */
internal fun registerKonanLink(
    project: Project,
    konan: KonanExtension,
    binding: BindingSpec,
    generate: TaskProvider<GenerateFromNativeIndexTask>,
    artifacts: NativeArtifacts?,
    targets: List<NativeTarget>,
): List<TaskProvider<*>> {
    val bindingName = binding.name
    val jni = binding.jni
    val output = generate.flatMap { it.outputDirectory }
    return targets.map { target ->
        project.tasks.register(taskName("linkJni", bindingName, target), LinkSharedLibraryTask::class.java) {
            group = "konan"
            description = "Link the '$bindingName' JNI library for ${target.konanName}."
            dependsOn(generate)
            artifacts?.let { dependsOn(it.builtBy) }
            mustRunAfter(project.cleanTask())
            androidApiLevel.set(konan.androidApiLevel)
            this.target.set(target)
            libraryBaseName.set(jni.stubLibraryName)
            stubCFile.set(generate.flatMap { it.outputDirectory.file(jni.stubLibraryName.map { name -> "${GeneratorRequest.Jni.C_DIR}/$name.c" }) })
            includeDirs.from(binding.includeDirs)
            // A plain path, not a tracked directory: a .def at the project root would make the whole
            // project, build/ included, an input of this task.
            includePaths.add(binding.defFile.map { it.asFile.parentFile.absolutePath })
            staticLibrary.set(project.layout.file(project.provider { archive(binding, artifacts, target) }))
            if (!target.isAndroid) jniIncludeDirs.from(output.map { it.dir(GeneratorRequest.Jni.INCLUDE_DIR) })
            additionalLinkerArgs.set(
                jni.linkerOptions.zip(jni.targetLinkerOptions) { common, perTarget ->
                    defLinkerOpts(binding) + common + perTarget[target].orEmpty()
                }
            )
            outputDirectory.set(jni.libraryDirectoryFor(target))
            // From the task's own property: a closure over the artifacts would drag their TaskProvider into
            // the configuration cache.
            // Linker options alone are enough: `-laaudio` links the stub against a platform library.
            onlyIf {
                staticLibrary.isPresent || additionalLinkerArgs.getOrElse(emptyList()).isNotEmpty() || run {
                    logger.lifecycle(
                        "konan.bindings.$bindingName: no library(), staticLibraries or linkerOpts in the .def, so " +
                            "only the JNI bindings and the .c stub were generated — link them from your own build."
                    )
                    false
                }
            }
        }
    }
}

/** The `.def`'s `linkerOpts`. */
private fun defLinkerOpts(binding: BindingSpec): List<String> =
    binding.defFile.orNull?.asFile?.takeIf { it.isFile }?.let { DefFile.parse(it).linkerOpts }.orEmpty()

/** The archive to link: the library's for [target], else the `.def`'s `staticLibraries`. */
private fun archive(binding: BindingSpec, artifacts: NativeArtifacts?, target: NativeTarget): File? {
    artifacts?.let {
        return it.archive(target).get().asFile.takeIf { _ -> target in it.targets.getOrElse(emptyList()) }
    }
    val def = binding.defFile.orNull?.asFile?.takeIf { it.isFile } ?: return null
    return DefFile.parse(def).resolveStaticLibraries(def.parentFile).firstOrNull()
}
