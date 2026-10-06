package io.github.lemcoder.jni

import io.github.lemcoder.index.GeneratorRequest
import io.github.lemcoder.kotlin.runIndexExporter
import io.github.lemcoder.model.BindingVisibility
import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.model.stubLibraryBaseName
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

/**
 * Generates the JNI `.c` stub and the Kotlin bindings from a cinterop `.def`, with the index exporter's
 * `jni` generator: cinterop's own indexer reads the headers, and the generator writes both sides of each
 * bridge from the C declarations.
 *
 * The same def feeds the native targets' cinterop, so the two legs cannot drift: only the package is
 * overridden here, since the JVM bindings live in their own package.
 */
@DisableCachingByDefault(because = "Output depends on the local Kotlin/Native distribution, which is not a tracked input")
internal abstract class GenerateJniBindingsTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val defFile: RegularFileProperty

    @get:Input abstract val packageName: Property<String>

    /** Extra include roots; the def's own directory is always added. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val includeDirs: ConfigurableFileCollection

    /** The headers are indexed once, for the host; the bridges are the same on every target. */
    @get:Input abstract val hostTarget: Property<NativeTarget>
    @get:Input @get:Optional abstract val compilerOptions: ListProperty<String>

    /** `INTERNAL` keeps the bindings out of the module's published API. */
    @get:Input abstract val visibility: Property<BindingVisibility>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    /**
     * Directory holding the generated JNI `.c` stub — the handover point when another build system
     * compiles it. Derived from [outputDirectory] so the layout stays the plugin's to change.
     */
    @get:Internal
    val stubSourceDirectory: Provider<Directory> get() = outputDirectory.dir(C_DIR)

    /** The generated stub itself, named after the binding package. */
    @get:Internal
    val stubSourceFile: Provider<RegularFile>
        get() = outputDirectory.file(packageName.map { "$C_DIR/${stubLibraryBaseName(it)}.c" })

    /**
     * Base name of the JNI library the generated bindings will `System.loadLibrary`, derived from the
     * binding package. A build that links the stub itself has to produce exactly this name.
     */
    @get:Internal
    val stubLibraryBaseName: Provider<String> get() = packageName.map { stubLibraryBaseName(it) }

    /** Directory holding the generated Kotlin bindings. */
    @get:Internal
    val kotlinSourceDirectory: Provider<Directory> get() = outputDirectory.dir(KOTLIN_DIR)

    @TaskAction
    fun run() {
        val def = defFile.get().asFile
        val includes = listOf(def.parentFile) + includeDirs.files
        exec.runIndexExporter(
            request = GeneratorRequest.Jni(stubLibrary = stubLibraryBaseName.get(), visibility = visibility.get()),
            outputDirectory = outputDirectory.get().asFile,
            cinteropArgs = buildList {
                add("-def"); add(def.absolutePath)
                add("-pkg"); add(packageName.get())
                add("-target"); add(hostTarget.get().konanName)
                includes.filter { it.exists() }.forEach { add("-compiler-option"); add("-I${it.absolutePath}") }
                compilerOptions.getOrElse(emptyList()).forEach { add("-compiler-option"); add(it) }
            },
            workDir = temporaryDir,
        )
    }

    private companion object {
        // The jni generator's layout: kotlin/<package>/<facade>.kt and c/<stubLibrary>.c.
        const val C_DIR = "c"
        const val KOTLIN_DIR = "kotlin"
    }
}
