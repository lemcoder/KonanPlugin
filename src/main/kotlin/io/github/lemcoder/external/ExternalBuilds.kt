package io.github.lemcoder.external

import io.github.lemcoder.cleanTask
import io.github.lemcoder.dsl.ExternalBuildSettings
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.konan.target.KonanTarget

/** A variable of an external build's contract: what the plugin tells the build, by environment. */
internal interface ExternalBuildVariable {
    /** The environment variable's name — public contract, read by build scripts. */
    val variableName: String
}

/**
 * Registers one target's [ExternalBuildTask]: [environment] as the contract, the library expected in
 * [outputDirectory] as [libraryFileName]. [configure] adds the build system's commands.
 */
internal fun Project.registerExternalBuild(
    taskName: String,
    taskDescription: String,
    environment: Map<out ExternalBuildVariable, Provider<String>>,
    outputDirectory: Provider<Directory>,
    libraryFileName: Provider<String>,
    configure: ExternalBuildTask.() -> Unit,
): TaskProvider<ExternalBuildTask> {
    val clean = cleanTask()
    // Read into locals: inside the block, `environment` and `outputDirectory` are the task's own.
    val contract = environment
    val output = outputDirectory
    val fileName = libraryFileName
    return tasks.register(taskName, ExternalBuildTask::class.java) {
        group = "konan"
        description = taskDescription
        mustRunAfter(clean)
        contract.forEach { (variable, value) -> this.environment.put(variable.variableName, value) }
        this.libraryFileName.set(fileName)
        this.outputDirectory.set(output)
        configure()
    }
}

/** The build script's own steps, as they are, for [target]: what a generic `external { }` block runs. */
internal fun ExternalBuildTask.addSteps(settings: ExternalBuildSettings, target: KonanTarget, owner: String) {
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
