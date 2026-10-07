package io.github.lemcoder.consumer

import io.github.lemcoder.ffm.FfmLibraryInputs
import io.github.lemcoder.ffm.ffmLibraryBuildFor
import io.github.lemcoder.index.FfmApi
import io.github.lemcoder.index.GenerateFromNativeIndexTask
import io.github.lemcoder.index.GeneratorRequest
import io.github.lemcoder.model.Architecture
import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.model.taskName
import io.github.lemcoder.toolchain.command
import io.github.lemcoder.wiring.AgpWiring
import io.github.lemcoder.wiring.GradlePluginId
import io.github.lemcoder.wiring.SourceKind
import org.gradle.api.Task
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import java.io.File

/**
 * FFM bindings: jextract over the same `.def`, and the C library itself as a shared library — FFM loads it
 * directly, with no stub — from the binding's [io.github.lemcoder.ffm.FfmLibraryBuild]. Opted into with
 * `ffm()`; in a `kotlin("jvm")` project or an Android module it replaces JNI.
 *
 * On a JVM the bindings use the JDK's `java.lang.foreign` and the library is provided for the host. In an
 * Android module they use PanamaPort's backport, which the plugin adds as a dependency, and the library
 * is provided for each 64-bit ABI under `jniLibs/`.
 */
internal class FfmConsumer(private val context: BindingContext) : BindingConsumer {
    private val project = context.project
    private val binding = context.binding
    private val bindingName = context.bindingName

    private val generatedRoot = context.buildDirectory.dir("generated/konan/$bindingName/ffm")

    private lateinit var linkAll: TaskProvider<Task>

    private val isAndroid get() = context.hasPlugin(GradlePluginId.ANDROID_BASE)

    override fun prepare() {
        val ffm = binding.ffm
        ffm.libraryName.convention(binding.library.map { it.name }.orElse(bindingName))
        ffm.jniLibsRoot.set(context.buildDirectory.dir("konan/bindings/$bindingName/ffm/jniLibs"))
        ffm.hostLibRoot.set(context.buildDirectory.dir("konan/bindings/$bindingName/ffm/lib"))
        linkAll = project.tasks.register(taskName("linkFfm", bindingName)) {
            group = "konan"
            description = "Make the shared library the '$bindingName' FFM bindings load exist for every target, however it is built."
        }
        // Eager, as the variant API needs; whether FFM is on is only known in the callbacks. linkFfm<Name>
        // depends on generation, so it orders both the compile and the packaging.
        val enabled = { ffm.isEnabled }
        AgpWiring.addSources(project, SourceKind.JAVA, generatedRoot.map { it.dir("java") }, linkAll, linkAll, enabled)
        AgpWiring.addJniLibs(project, ffm.jniLibsRoot, linkAll, enabled)
    }

    override fun isUsed(): Boolean = binding.ffm.isEnabled

    override fun register() {
        val ffm = binding.ffm
        val artifacts = context.artifacts()
        val libraryName = ffm.libraryName.get()
        val api = if (isAndroid) FfmApi.PANAMA_PORT else FfmApi.JAVA
        val packageName = context.packageName
        val generate = project.tasks.register(taskName("generateFfm", bindingName), GenerateFromNativeIndexTask::class.java) {
            group = "konan"
            description = "Generate the '$bindingName' FFM bindings with jextract."
            defFile.set(binding.defFile)
            includeDirs.from(binding.includeDirs)
            compilerOptions.set(binding.compilerOptions)
            // One index, for the host: its type layouts are those of every LP64 target, which is why Android
            // gets only its 64-bit ABIs.
            target.set(NativeTarget.host)
            // Resolved here, in the daemon, which knows the usual install locations; unresolved, the exporter
            // runs `jextract` from its own PATH.
            request.set(ffm.jextract.map { jextract ->
                GeneratorRequest.Jextract(library = libraryName, jextract = File(jextract.command()).takeIf { it.isAbsolute }, api = api)
            })
            packageName.orNull?.let { extraCinteropArgs.addAll("-pkg", it) }
            outputDirectory.set(generatedRoot)
        }
        linkAll.configure { dependsOn(generate) }

        val targets = targets(artifacts?.targets?.getOrElse(emptyList()).orEmpty())
        val builds = ffmLibraryBuildFor(binding).register(project, context.konan, binding, FfmLibraryInputs(artifacts, targets, libraryName))
        linkAll.configure { dependsOn(builds) }

        if (isAndroid) {
            ffm.libraryDirectory.convention(ffm.jniLibsRoot)
            project.dependencies.add("implementation", ffm.panamaPortVersion.map { "$PANAMA_PORT:$it" })
        } else {
            ffm.libraryDirectory.convention(ffm.libraryDirectoryFor(NativeTarget.host))
        }
        if (context.hasPlugin(GradlePluginId.KOTLIN_JVM)) {
            // Plain Gradle source sets: Java is compiled by the java plugin, which kotlin("jvm") applies.
            val main = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")
            main.java.srcDir(generate.map { it.outputDirectory.dir("java") })
            if (ffm.bundleInJar.get()) {
                val bundle = project.registerJarBundle(
                    taskName = taskName("bundleFfm", bindingName),
                    taskDescription = "Put the libraries the '$bindingName' FFM bindings load in the jar.",
                    libraries = targets.associateWith { target -> ffm.libraryDirectoryFor(target).map { it.file(target.sharedLibraryName(libraryName)) } },
                    destination = context.buildDirectory.dir("konan/bindings/$bindingName/ffm/resources").get().asFile,
                    builtBy = linkAll,
                )
                main.resources.srcDir(bundle.map { it.destinationDir })
            }
        } else if (!isAndroid) {
            project.logger.info("konan.bindings.$bindingName: FFM bindings are wired into kotlin(\"jvm\") and Android projects; add build/generated/konan/$bindingName/ffm/java yourself.")
        }
    }

    /**
     * `ffm.targets`, else the library's targets the project can load, else all of those: the host on a JVM,
     * the 64-bit ABIs in an Android module — jextract writes LP64 layouts, so 32-bit ABIs are left out.
     */
    private fun targets(libraryTargets: List<NativeTarget>): List<NativeTarget> {
        val loadable = if (isAndroid) NativeTarget.android.filter { it.architecture in LP64 } else listOf(NativeTarget.host)
        binding.ffm.targets.getOrElse(emptyList()).takeIf { it.isNotEmpty() }?.let { declared ->
            val unsupported = declared - loadable.toSet()
            check(unsupported.isEmpty()) {
                "konan.bindings.$bindingName.ffm.targets: ${unsupported.joinToString { it.konanName }} cannot load these bindings here; " +
                    "they are generated for ${loadable.joinToString { it.konanName }}."
            }
            return declared
        }
        val skipped = libraryTargets.filter { it.isAndroid && it.architecture !in LP64 }
        if (isAndroid && skipped.isNotEmpty()) {
            project.logger.warn(
                "konan.bindings.$bindingName: FFM bindings are generated for 64-bit layouts, so no library is provided for " +
                    "${skipped.joinToString { it.androidAbi ?: it.konanName }}; leave those ABIs out of the app."
            )
        }
        return libraryTargets.filter { it in loadable }.ifEmpty { loadable }
    }

    private companion object {
        const val PANAMA_PORT = "io.github.vova7878.panama:Core"
        val LP64 = setOf(Architecture.ARM64, Architecture.X64)
    }
}
