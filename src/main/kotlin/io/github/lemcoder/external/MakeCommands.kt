package io.github.lemcoder.external

import io.github.lemcoder.dsl.MakeSettings
import io.github.lemcoder.dsl.konanConfig
import io.github.lemcoder.model.ConfigField
import io.github.lemcoder.model.ExternalBuildContext
import io.github.lemcoder.model.ToolchainVariable

/** The Make preset of an external build: one `make` run for the context's target; see [MakeSettings]. */
internal fun ExternalBuildTask.addMakeCommands(make: MakeSettings, context: ExternalBuildContext, owner: String) {
    check(make.directory.isPresent) { "$owner needs a directory." }
    val makeExecutable = project.konanConfig.executable(ConfigField.MAKE, path)
    command {
        executable.set(makeExecutable)
        arguments.addAll(make.directory.map { listOf("-C", it.asFile.absolutePath) })
        arguments.addAll(make.makefile.map { listOf("-f", it.asFile.absolutePath) }.orElse(emptyList()))
        arguments.add(make.jobs.map { "-j$it" })
        // Command-line variables override the Makefile's assignments: the compilers, then the contract.
        context.environment.filterKeys { it != ToolchainVariable.CMAKE_TOOLCHAIN_FILE }
            .forEach { (variable, value) -> arguments.add(value.map { "${variable.variableName}=$it" }) }
        arguments.addAll(make.arguments)
        arguments.addAll(make.targetArguments.getting(context.target).orElse(emptyList()))
        arguments.addAll(make.buildTargets)
    }
    sources.from(make.sources)
    producedLibrary.fileProvider(make.producedLibraries.getting(context.target))
}
