package io.github.lemcoder.library

import io.github.lemcoder.dsl.CargoProfile
import io.github.lemcoder.dsl.NativeLibrarySpec
import io.github.lemcoder.dsl.konanConfig
import io.github.lemcoder.external.ExternalBuildTask
import io.github.lemcoder.model.ConfigField
import io.github.lemcoder.model.ExternalBuildContext
import io.github.lemcoder.toolchain.rustTriple
import org.gradle.api.Project

/**
 * [LibraryProvider.External.Cargo]: `cargo build --lib` for one target, into a target directory of the
 * plugin's own; the crate's static library is then copied to where every other provider puts the archive.
 */
internal fun addCargoCommands(task: ExternalBuildTask, project: Project, library: NativeLibrarySpec, context: ExternalBuildContext) {
    val cargo = library.cargo
    val target = context.target
    val triple = target.rustTriple()
    val targetDirectory = project.layout.buildDirectory.dir("konan/libraries/${library.name}/cargo")
    val manifest = cargo.manifest.orElse(library.sourceDirectory.file("Cargo.toml"))

    val cargoExecutable = project.konanConfig.executable(ConfigField.CARGO, task.path)
    task.command {
        executable.set(cargoExecutable)
        arguments.addAll("build", "--lib", "--target", triple)
        arguments.addAll(manifest.map { listOf("--manifest-path", it.asFile.absolutePath) })
        arguments.addAll(targetDirectory.map { listOf("--target-dir", it.asFile.absolutePath) })
        arguments.addAll(cargo.profile.map { if (it == CargoProfile.RELEASE) listOf("--release") else emptyList() })
        arguments.addAll(cargo.arguments)
        workingDirectory.set(manifest.map { it.asFile.parentFile.absolutePath })
        // Target-specific, as the cc crate reads them: a plain CC would also reach what is built for the host.
        context.toolchain?.let { toolchain ->
            val suffix = triple.replace('-', '_')
            environment.put("CC_$suffix", toolchain.cc.map { it.absolutePath })
            environment.put("CXX_$suffix", toolchain.cxx.map { it.absolutePath })
            environment.put("AR_$suffix", toolchain.ar.map { it.absolutePath })
        }
    }
    task.producedLibrary.set(
        targetDirectory.zip(cargo.profile) { directory, profile -> directory.dir("$triple/${profile.directory}") }
            .zip(cargo.crateName) { directory, crate -> directory.file("lib${crate.replace('-', '_')}.a") }
    )
}
