package io.github.lemcoder.jni

import io.github.lemcoder.dsl.BindingSpec
import io.github.lemcoder.external.ExternalBuildTask

/** [JniStubBuild.External.Generic]: the build script's own steps, as they are, for one target. */
internal fun addGenericCommands(task: ExternalBuildTask, binding: BindingSpec, contract: ExternalContract) {
    val external = binding.jni.external
    check(external.steps.isNotEmpty()) { "konan.bindings.${binding.name}.jni.external declares no step." }
    external.steps.forEach { step ->
        task.command {
            executable.set(step.executable)
            arguments.addAll(step.arguments)
            arguments.addAll(step.targetArguments.getting(contract.target).orElse(emptyList()))
            environment.putAll(step.environment)
            workingDirectory.set(step.workingDirectory.map { it.asFile.absolutePath })
        }
    }
    task.sources.from(external.sources)
    task.producedLibrary.fileProvider(external.producedLibraries.getting(contract.target))
}
