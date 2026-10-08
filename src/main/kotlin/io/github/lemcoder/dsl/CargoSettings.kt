package io.github.lemcoder.dsl

import io.github.lemcoder.model.Executable
import io.github.lemcoder.model.ExternalToolchain
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * `cargo build` for a library written in Rust, once per target, `--target` set to the Rust triple for
 * it. The crate must build a static library (`crate-type = ["staticlib"]`); its `lib<crate>.a` becomes the
 * library's archive, so bindings and consumers do not change. Each target's standard library has to be
 * installed: `rustup target add <triple>`.
 */
abstract class CargoSettings {
    init {
        executable.convention(Executable.OnPath("cargo"))
        profile.convention(CargoProfile.RELEASE)
        toolchain.convention(ExternalToolchain.AUTO)
    }

    /** The crate's `Cargo.toml`. Defaults to the library's `sourceDirectory/Cargo.toml`. */
    abstract val manifest: RegularFileProperty

    /** The crate's name, as `Cargo.toml` spells it; the archive is `lib<name>.a`, `-` read as `_`. Defaults to the library's name. */
    abstract val crateName: Property<String>

    /** Debug or release build. Defaults to [CargoProfile.RELEASE]. */
    abstract val profile: Property<CargoProfile>

    /** Further `cargo build` arguments, e.g. `--features`. */
    abstract val arguments: ListProperty<String>

    /** The `cargo` program. Defaults to `cargo` on the PATH or in the usual install locations. */
    abstract val executable: Property<Executable>

    /**
     * Whose C compilers crates with C code (the `cc` crate) get, as `CC_<triple>`, `CXX_<triple>` and
     * `AR_<triple>` — target-specific, so build scripts compiled for the host keep the host's.
     * Kotlin/Native's when cross-compiling, by default.
     */
    abstract val toolchain: Property<ExternalToolchain>
}

/** Cargo's build profiles. */
enum class CargoProfile(internal val directory: String) {
    DEBUG("debug"),
    RELEASE("release"),
}
