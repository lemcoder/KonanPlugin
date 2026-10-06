package io.github.lemcoder.library

import io.github.lemcoder.dsl.NativeLibrarySpec
import io.github.lemcoder.model.NativeArtifacts
import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.model.taskName
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * [LibraryProvider.Prebuilt]: archives built elsewhere, placed in the layout every provider leaves —
 * `<target>/lib<name>.a`, whatever the file was called — so consumers cannot tell them apart.
 */
internal fun registerPrebuilt(project: Project, library: NativeLibrarySpec): TaskProvider<*> {
    val libraryName = library.name
    val archives = library.prebuilt.archives.getOrElse(emptyMap())
    val targets = library.targets.getOrElse(emptyList())
    val missing = targets.filter { it !in archives }
    check(missing.isEmpty()) {
        "konan.libraries.$libraryName.prebuilt has no archive for ${missing.joinToString { it.konanName }}."
    }
    val placements = targets.associateWith { archives.getValue(it) }

    return project.tasks.register(taskName("prebuilt", libraryName), PlacePrebuiltArchivesTask::class.java) {
        group = "konan"
        description = "Place the prebuilt '$libraryName' archives where consumers look for them."
        archiveFiles.from(placements.values)
        this.placements.set(placements)
        libraryFileName.set(NativeArtifacts.archiveFileName(libraryName))
        outputDirectory.set(library.outputDirectory)
    }
}

/**
 * Copies each target's archive to `<outputDirectory>/<target>/<libraryFileName>`. Not a `Copy`: that
 * skips a missing source as NO-SOURCE, and a consumer would then fail far from the cause.
 */
@DisableCachingByDefault(because = "Copying files is not worth caching")
internal abstract class PlacePrebuiltArchivesTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val archiveFiles: ConfigurableFileCollection

    /** Which archive each target gets. */
    @get:Input abstract val placements: MapProperty<NativeTarget, File>

    @get:Input abstract val libraryFileName: Property<String>

    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun place() {
        val archives = placements.get()
        val absent = archives.values.filterNot { it.isFile }
        check(absent.isEmpty()) { "$path: no such archive: ${absent.joinToString()}" }
        val output = outputDirectory.get().asFile
        archives.forEach { (target, archive) ->
            archive.copyTo(output.resolve(target.konanName).resolve(libraryFileName.get()), overwrite = true)
        }
    }
}
