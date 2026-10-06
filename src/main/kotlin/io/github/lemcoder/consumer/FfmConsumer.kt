package io.github.lemcoder.consumer

import io.github.lemcoder.index.GenerateFromNativeIndexTask
import io.github.lemcoder.index.GeneratorRequest
import io.github.lemcoder.link.LinkSharedLibraryTask
import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.model.taskName
import io.github.lemcoder.toolchain.command
import io.github.lemcoder.wiring.GradlePluginId
import org.gradle.api.tasks.SourceSetContainer
import java.io.File

/**
 * FFM bindings: jextract over the same `.def`, and the library itself as a shared library for the host —
 * FFM loads it directly, with no stub. Opted into with `ffm { }`; in a `kotlin("jvm")` project it
 * replaces JNI.
 */
internal class FfmConsumer(private val context: BindingContext) : BindingConsumer {
    override fun isUsed(): Boolean = context.binding.ffm.isEnabled

    override fun register() {
        val project = context.project
        val binding = context.binding
        val bindingName = context.bindingName
        val artifacts = context.artifacts()
        val libraryName = artifacts?.name ?: bindingName
        val host = NativeTarget.host
        val packageName = context.packageName
        val generate = project.tasks.register(taskName("generateFfm", bindingName), GenerateFromNativeIndexTask::class.java) {
            group = "konan"
            description = "Generate the '$bindingName' FFM bindings with jextract."
            defFile.set(binding.defFile)
            includeDirs.from(binding.includeDirs)
            compilerOptions.set(binding.compilerOptions)
            target.set(host)
            // Resolved here, in the daemon, which knows the usual install locations; unresolved, the exporter
            // runs `jextract` from its own PATH.
            request.set(binding.ffm.jextract.map { jextract ->
                GeneratorRequest.Jextract(library = libraryName, jextract = File(jextract.command()).takeIf { it.isAbsolute })
            })
            packageName.orNull?.let { extraCinteropArgs.addAll("-pkg", it) }
            outputDirectory.set(context.buildDirectory.dir("generated/konan/$bindingName/ffm"))
        }
        val ffmLib = context.buildDirectory.dir("konan/bindings/$bindingName/ffm/lib/${host.konanName}")
        binding.ffm.libraryDirectory.convention(ffmLib)
        if (artifacts != null && host in artifacts.targets.getOrElse(emptyList())) {
            project.tasks.register(taskName("linkFfm", bindingName), LinkSharedLibraryTask::class.java) {
                group = "konan"
                description = "Link the '$libraryName' library as a shared library for the FFM bindings."
                dependsOn(artifacts.builtBy)
                konanHome.set(context.konan.konanHome)
                target.set(host)
                libraryBaseName.set(libraryName)
                staticLibrary.set(artifacts.archive(host))
                additionalLinkerArgs.set(binding.ffm.linkerOptions)
                outputDirectory.set(ffmLib)
            }
        }
        if (context.hasPlugin(GradlePluginId.KOTLIN_JVM)) {
            // Plain Gradle source sets: Java is compiled by the java plugin, which kotlin("jvm") applies.
            project.extensions.getByType(SourceSetContainer::class.java).getByName("main")
                .java.srcDir(generate.map { it.outputDirectory.dir("java") })
        } else {
            project.logger.info("konan.bindings.$bindingName: FFM bindings are only wired into kotlin(\"jvm\") projects; add build/generated/konan/$bindingName/ffm/java yourself.")
        }
    }
}
