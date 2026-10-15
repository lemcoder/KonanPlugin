package io.github.lemcoder.libraries

import io.github.lemcoder.dsl.libraries.NativeLibrarySpec
import io.github.lemcoder.model.NativeArtifacts
import io.github.lemcoder.model.taskName
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.DisableCachingByDefault

/**
 * `prebuilt { }`: archives built elsewhere, placed in the layout every library build leaves —
 * `<target>/lib<name>.a`, whatever the file was called — so consumers cannot tell them apart.
 */
internal fun registerPrebuilt(project: Project, library: NativeLibrarySpec): TaskProvider<*> {
    val libraryName = library.name
    val archives = library.prebuilt.libraries.getOrElse(emptyMap())
    val targets = library.targets.getOrElse(emptyList())
    val missing = targets.filter { it !in archives }
    check(missing.isEmpty()) {
        "konan.libraries.$libraryName.prebuilt has no archive for ${missing.joinToString { it.konanName }}."
    }
    val output = library.outputDirectory.get().asFile
    val placements = targets.associate { archives.getValue(it) to output.resolve(NativeArtifacts.archivePath(libraryName, it)) }

    return project.tasks.register(taskName("prebuilt", libraryName), PlacePrebuiltFilesTask::class.java) {
        group = "konan"
        description = "Place the prebuilt '$libraryName' archives where consumers look for them."
        place(placements)
    }
}

/**
 * Copies each prebuilt file to where the plugin's layout wants it, under the name consumers look for. Not
 * a `Copy`: that skips a missing source as NO-SOURCE, and a consumer would then fail far from the cause.
 */
@DisableCachingByDefault(because = "Copying files is not worth caching")
internal abstract class PlacePrebuiltFilesTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val sources: ConfigurableFileCollection

    /** Each prebuilt file and where it goes. */
    @get:Input abstract val placements: MapProperty<File, File>

    @get:OutputFiles abstract val destinations: ConfigurableFileCollection

    fun place(placements: Map<File, File>) {
        sources.from(placements.keys)
        destinations.from(placements.values)
        this.placements.set(placements)
    }

    @TaskAction
    fun copy() {
        val files = placements.get()
        val absent = files.keys.filterNot { it.isFile }
        check(absent.isEmpty()) { "$path: no such file: ${absent.joinToString()}" }
        files.forEach { (source, destination) -> source.copyTo(destination, overwrite = true) }
    }
}
