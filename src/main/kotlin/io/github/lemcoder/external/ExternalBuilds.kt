package io.github.lemcoder.external

import io.github.lemcoder.dsl.ExternalBuildSettings
import io.github.lemcoder.kotlin.KonanToolchain
import io.github.lemcoder.model.ExternalBuildContext
import io.github.lemcoder.model.ExternalToolchain
import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.model.ToolchainVariable
import io.github.lemcoder.model.taskName
import io.github.lemcoder.util.cleanTask
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

/**
 * An external build for each of [targets]: one [ExternalBuildTask] per target, named
 * `<taskPrefix><Owner><Target>`, run with [contextFor]'s contract as environment and expected to leave
 * [libraryFileName] in the context's output directory. [configure] adds the build system's commands —
 * the only part that differs between build systems and between a library and a JNI stub.
 *
 * Where [toolchain] picks Kotlin/Native's for a target, the context also carries its [ToolchainVariable]s
 * and the build waits for its files; null means the build system brings its own (Zig).
 */
internal fun Project.registerExternalBuilds(
    taskPrefix: String,
    owner: String,
    description: (NativeTarget) -> String,
    targets: List<NativeTarget>,
    contextFor: (NativeTarget) -> ExternalBuildContext,
    libraryFileName: (NativeTarget) -> Provider<String>,
    toolchain: ExternalToolchain?,
    configure: ExternalBuildTask.(ExternalBuildContext) -> Unit,
): List<TaskProvider<ExternalBuildTask>> {
    val clean = cleanTask()
    return targets.map { target ->
        val konanToolchain = toolchain?.takeIf { it.usesKonan(target) }?.let { KonanToolchain.of(this, target) }
        val context = contextFor(target).let { context ->
            konanToolchain?.let { ExternalBuildContext(context.target, context.outputDirectory, context.environment + it.environment) } ?: context
        }
        val taskDescription = description(target)
        val fileName = libraryFileName(target)
        tasks.register(taskName(taskPrefix, owner, target), ExternalBuildTask::class.java) {
            group = "konan"
            this.description = taskDescription
            mustRunAfter(clean)
            konanToolchain?.let { dependsOn(it.task) }
            context.environment.forEach { (variable, value) -> environment.put(variable.variableName, value) }
            this.libraryFileName.set(fileName)
            outputDirectory.set(context.outputDirectory)
            configure(context)
        }
    }
}

/** The build script's own steps, as they are, for [target]: what a generic `external { }` block runs. */
internal fun ExternalBuildTask.addSteps(settings: ExternalBuildSettings, target: NativeTarget, owner: String) {
    check(settings.steps.isNotEmpty()) { "$owner.external declares no step." }
    settings.steps.forEach { step ->
        command {
            executable.set(step.executable)
            arguments.addAll(step.arguments)
            arguments.addAll(step.targetArguments.getting(target).orElse(emptyList()))
            environment.putAll(step.environment)
            workingDirectory.set(step.workingDirectory.map { it.asFile.absolutePath })
        }
    }
    sources.from(settings.sources)
    producedLibrary.fileProvider(settings.producedLibraries.getting(target))
}
