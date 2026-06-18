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

        registerJvmInterop(project, extension)

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

    /**
     * Registers the [GenerateJvmInteropTask] (`generateJvmInterop`) that turns [KonanPluginExtension.defFile]
     * into JVM/JNI bindings. Only wired up when a `.def` file is configured.
     */
    private fun registerJvmInterop(project: Project, extension: KonanPluginExtension) {
        project.tasks.register("generateJvmInterop", GenerateJvmInteropTask::class.java) {
            group = project.name

            konanPath.set(extension.konanPath)
            defFile.set(project.layout.projectDirectory.file(extension.defFile))
            target.set(extension.targets.map { it.first() })
            headerDirs.from(project.layout.projectDirectory.dir(extension.headerDir))
            additionalCompilerArgs.set(extension.additionalCompilerArgs)
            outputDirectory.set(
                project.layout.buildDirectory.dir(
                    extension.jvmInteropOutputDir.orElse("generated/jvmInterop")
                )
            )

            // Default JDK include dirs (where jni.h / jni_md.h live) from the JVM running Gradle.
            jdkIncludeDirs.from(defaultJdkIncludeDirs())
        }
    }

    private fun defaultJdkIncludeDirs(): List<File> {
        val javaHome = File(System.getProperty("java.home"))
        val include = javaHome.resolve("include")
        val osName = System.getProperty("os.name").lowercase()
        val subDir = when {
            osName.contains("mac") || osName.contains("darwin") -> "darwin"
            osName.contains("windows") -> "win32"
            else -> "linux"
        }
        return listOf(include, include.resolve(subDir))
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

    /** Path (relative to the project) to the `.def` file used by `generateJvmInterop`. */
    val defFile: Property<String>

    /** Output dir (relative to `build/`) for generated JVM interop sources. Defaults to `generated/jvmInterop`. */
    val jvmInteropOutputDir: Property<String>
}
