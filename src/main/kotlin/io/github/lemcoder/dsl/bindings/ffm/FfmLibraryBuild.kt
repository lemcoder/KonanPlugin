package io.github.lemcoder.dsl.bindings.ffm

import io.github.lemcoder.dsl.KonanExtension
import io.github.lemcoder.dsl.bindings.BindingSpec
import io.github.lemcoder.dsl.bindings.LinkSharedLibraryTask
import io.github.lemcoder.dsl.libraries.NativeArtifacts
import io.github.lemcoder.dsl.libraries.PlacePrebuiltFilesTask
import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.model.taskName
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/**
 * The FFM library's default build: the binding's library's archive, linked whole into a shared library
 * with Kotlin/Native's clang, for each of [targets] it is built for. FFM needs no stub — this is the C
 * library itself, as `ffm.libraryDirectoryFor(target)/<lib><libraryName><suffix>`.
 */
internal fun registerFfmLink(
    project: Project,
    konan: KonanExtension,
    binding: BindingSpec,
    artifacts: NativeArtifacts?,
    libraryName: String,
    targets: List<NativeTarget>,
): List<TaskProvider<*>> {
    if (artifacts == null) {
        // Nothing to link: a platform library (libaaudio), or one the app gets some other way.
        project.logger.info("konan.bindings.${binding.name}.ffm: no library(), so the bindings load lib$libraryName as the system provides it.")
        return emptyList()
    }
    val built = artifacts.targets.getOrElse(emptyList())
    val (linked, missing) = targets.partition { it in built }
    if (missing.isNotEmpty()) {
        project.logger.warn(
            "konan.bindings.${binding.name}.ffm: no library is built for ${missing.joinToString { it.konanName }}, so nothing " +
                "is linked there — name one with library(…), or use ffm { external { } } or ffm { prebuilt { } }."
        )
    }
    val ffm = binding.ffm
    return linked.map { target ->
        project.tasks.register(taskName("linkFfm", binding.name, target), LinkSharedLibraryTask::class.java) {
            group = "konan"
            description = "Link the '$libraryName' library for ${target.konanName}, for the FFM bindings."
            dependsOn(artifacts.builtBy)
            androidApiLevel.set(konan.androidApiLevel)
            this.target.set(target)
            libraryBaseName.set(libraryName)
            staticLibrary.set(artifacts.archive(target))
            additionalLinkerArgs.set(ffm.linkerOptions.zip(ffm.targetLinkerOptions) { common, perTarget -> common + perTarget[target].orEmpty() })
            outputDirectory.set(ffm.libraryDirectoryFor(target))
        }
    }
}

/** `ffm { prebuilt { } }`: shared libraries built elsewhere, placed under the name the bindings load. */
internal fun registerFfmPrebuilt(project: Project, binding: BindingSpec, libraryName: String, targets: List<NativeTarget>): List<TaskProvider<*>> {
    val ffm = binding.ffm
    val libraries = ffm.prebuilt.libraries.getOrElse(emptyMap())
    val missing = targets.filter { it !in libraries }
    check(missing.isEmpty()) {
        "konan.bindings.${binding.name}.ffm.prebuilt has no shared library for ${missing.joinToString { it.konanName }}."
    }
    val placements = targets.associate { target ->
        libraries.getValue(target) to ffm.libraryDirectoryFor(target).get().file(target.sharedLibraryName(libraryName)).asFile
    }
    return listOf(
        project.tasks.register(taskName("prebuiltFfm", binding.name), PlacePrebuiltFilesTask::class.java) {
            group = "konan"
            description = "Place the prebuilt '$libraryName' libraries the '${binding.name}' FFM bindings load."
            place(placements)
        }
    )
}
