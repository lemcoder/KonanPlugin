package io.github.lemcoder.dsl.libraries

import io.github.lemcoder.dsl.builds.CargoSettings
import io.github.lemcoder.dsl.builds.ExternalBuildBlocks
import io.github.lemcoder.dsl.builds.PrebuiltSettings
import io.github.lemcoder.dsl.builds.ZigSettings
import io.github.lemcoder.model.NativeTarget
import org.gradle.api.tasks.TaskProvider
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Provider

/**
 * A C/C++ library built into a static archive per target, `<outputDirectory>/<target>/lib<name>.a`: by
 * Kotlin/Native's clang from [sourceDirectory], by the project's own build ([cmake], [zig], [cargo], [make], [external]), or built
 * elsewhere ([prebuilt]). An external build gets `KONAN_SOURCE_DIR` besides the common contract, and leaves
 * its archives in `KONAN_OUTPUT_DIR` — several are merged into `lib<name>.a` — or names where it leaves its
 * archive with `producedLibrary`.
 */
abstract class NativeLibrarySpec @Inject constructor(private val name: String, objects: ObjectFactory) :
    ExternalBuildBlocks(objects), Named {
    override fun getName(): String = name

    /** Targets to build for. Required, unless [prebuilt] declares them. */
    abstract val targets: ListProperty<NativeTarget>

    /** The C/C++ sources. Defaults to `native/`. */
    abstract val sourceDirectory: DirectoryProperty

    /** Include roots beyond [sourceDirectory], which always is one. */
    abstract val includeDirs: ConfigurableFileCollection

    /** Extra clang options, appended to `-fno-sanitize=undefined -fPIC`. */
    abstract val compilerOptions: ListProperty<String>

    /** Root of the per-target archives. Defaults to `build/konan/libraries/<name>`. */
    abstract val outputDirectory: DirectoryProperty

    /**
     * [targets] without repeats, which is what tasks are registered from: `targets(NativeTarget.host,
     * NativeTarget.LINUX_ARM64)` names one target twice on a Linux arm64 host.
     */
    internal val distinctTargets: Provider<List<NativeTarget>> get() = targets.map { it.distinct() }

    fun targets(vararg targets: NativeTarget) = this.targets.set(targets.toList())

    /** All four Android ABIs. */
    fun androidTargets() = targets.set(NativeTarget.android)

    /** Each target's build, once the build script has run: what a consumer of one target depends on. */
    internal val buildsByTarget = mutableMapOf<NativeTarget, TaskProvider<*>>()

    /** The archive built for [target]. */
    fun archive(target: NativeTarget): Provider<RegularFile> = outputDirectory.file(NativeArtifacts.archivePath(name, target))

    /** The directory holding [target]'s archive. */
    fun archiveDirectory(target: NativeTarget): Provider<Directory> = outputDirectory.dir(target.konanName)

    /**
     * Builds the library with the project's own `build.zig` instead of compiling [sourceDirectory] with
     * Kotlin/Native's clang. The archives land in the same place, so bindings do not change.
     */
    val zig: ZigSettings = objects.newInstance(ZigSettings::class.java)

    fun zig(action: Action<in ZigSettings>) = select(zig, action)

    /** Builds the library from a Rust crate (`crate-type = ["staticlib"]`) with `cargo build`, once per target. */
    val cargo: CargoSettings = objects.newInstance(CargoSettings::class.java)

    fun cargo(action: Action<in CargoSettings>) = select(cargo, action)

    /** Archives built elsewhere, one per target; [targets] default to the targets they are declared for. */
    val prebuilt: PrebuiltSettings = objects.newInstance(PrebuiltSettings::class.java)

    fun prebuilt(action: Action<in PrebuiltSettings>) = select(prebuilt, action)

    init {
        zig.projectDirectory.convention(sourceDirectory)
        zig.artifactName.convention(name)
        cargo.crateName.convention(name)
        cargo.manifest.convention(sourceDirectory.file("Cargo.toml"))
        cmake.path.convention(sourceDirectory.file("CMakeLists.txt"))
        targets.convention(prebuilt.libraries.keySet().map { it.toList() })
    }
}
