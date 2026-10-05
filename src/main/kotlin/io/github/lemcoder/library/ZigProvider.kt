package io.github.lemcoder.library

import io.github.lemcoder.cleanTask
import io.github.lemcoder.dsl.KonanExtension
import io.github.lemcoder.dsl.NativeLibrarySpec
import io.github.lemcoder.dsl.ZigOptimize
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/** The project's own `build.zig`, run once per target; selected by a library's `zig { }` block. */
internal object ZigProvider : LibraryProvider {
    override val kind = LibraryProviderKind.ZIG

    override fun conventions(project: Project, library: NativeLibrarySpec) {
        val zig = library.zig
        zig.projectDirectory.convention(library.sourceDirectory)
        zig.optimize.convention(ZigOptimize.RELEASE_FAST)
        zig.artifactName.convention(library.name)
    }

    override fun register(project: Project, konan: KonanExtension, library: NativeLibrarySpec): TaskProvider<*> =
        project.tasks.register("zigBuild${library.name.replaceFirstChar { it.uppercase() }}", ZigBuildTask::class.java) {
            group = "konan"
            description = "Build the '${library.name}' library with zig build."
            mustRunAfter(project.cleanTask())
            val zig = library.zig
            projectDirectory.set(zig.projectDirectory)
            sources.from(zig.projectDirectory.map { dir -> dir.asFileTree.matching { exclude(".zig-cache/**", "zig-out/**") } })
            executable.set(zig.executable.map { it.asFile.absolutePath }.orElse("zig"))
            targets.set(library.targets)
            optimize.set(zig.optimize)
            arguments.set(zig.arguments)
            artifactName.set(zig.artifactName)
            libraryName.set(library.name)
            outputDirectory.set(library.outputDirectory)
        }
}
