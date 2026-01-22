package io.github.lemcoder

import org.gradle.api.Project
import org.gradle.api.Plugin
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import java.io.File

abstract class KonanPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Define the properties that can be configured by the user
        val extension = project.extensions.create("konanConfig", KonanPluginExtension::class.java)

        project.tasks.register("runKonanClang", RunKonanClangTask::class.java) {
            group = project.name

            targets.set(extension.targets.get())

            outputDir.set(project.layout.projectDirectory.dir(extension.outputDir))

            sourceFiles.from(
                project.layout.projectDirectory
                    .dir(extension.sourceDir.get())
                    .asFileTree
            )

            libName.set(extension.libName)

            includeDirs.from(project.layout.projectDirectory.dir(extension.headerDir))

            arguments.addAll(
                "-std=c99",
                "-fno-sanitize=undefined",
                "-D" + "JPH_CROSS_PLATFORM_DETERMINISTIC",
                "-D" + "JPH_ENABLE_ASSERTS",
            )
            arguments.addAll(extension.additionalCompilerArgs)

            val isWindows = System.getProperty("os.name").lowercase().contains("windows")
            val scriptPath = if (isWindows) "bin/run_konan.bat" else "bin/run_konan"
            runKonan.set(File(extension.konanPath.get()).resolve(scriptPath))
        }
    }
}

interface KonanPluginExtension {
    val targets: ListProperty<String>
    val sourceDir: Property<String>
    val headerDir: Property<String>
    val libName: Property<String>
    val outputDir: Property<String>
    val konanPath: Property<String>
    val additionalCompilerArgs: ListProperty<String>
}
