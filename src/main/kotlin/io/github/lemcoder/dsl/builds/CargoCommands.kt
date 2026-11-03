package io.github.lemcoder.dsl.builds

import io.github.lemcoder.dsl.config.ConfigField
import io.github.lemcoder.dsl.config.konanConfig
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider

/**
 * [ExternalBuild.Cargo]: `cargo build --lib` for one target, into [targetDirectory]; the crate's static
 * library is then copied to where every other build puts the archive.
 */
internal fun ExternalBuildTask.addCargoCommands(cargo: CargoSettings, targetDirectory: Provider<Directory>, context: ExternalBuildContext) {
    val target = context.target
    val triple = target.rustTriple()
    val manifest = cargo.manifest

    val cargoExecutable = project.konanConfig.executable(ConfigField.CARGO, path)
    command {
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
    producedLibrary.set(
        targetDirectory.zip(cargo.profile) { directory, profile -> directory.dir("$triple/${profile.directory}") }
            .zip(cargo.crateName) { directory, crate -> directory.file("lib${crate.replace('-', '_')}.a") }
    )
}
