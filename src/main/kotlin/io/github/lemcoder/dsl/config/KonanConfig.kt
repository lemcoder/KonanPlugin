package io.github.lemcoder.dsl.config

import io.github.lemcoder.dsl.KonanExtension
import io.github.lemcoder.kotlin.KonanDistribution
import io.github.lemcoder.model.Executable
import java.io.File
import javax.inject.Inject
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemLocationProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory

/**
 * `konan { config { } }`: the paths the plugin needs from the machine — the Kotlin/Native distribution and
 * the programs it runs. The one place they come from; nothing is searched for.
 *
 * Each is read from this block, else the Gradle property `konan.config.<name>` (a build's
 * `gradle.properties`, `~/.gradle/gradle.properties` or `-P`), else an environment variable:
 *
 * | Field       | Environment variable | Needed by |
 * |-------------|----------------------|-----------|
 * | `konanHome` | `KONAN_HOME`         | anything that compiles, links or generates bindings |
 * | `jextract`  | `KONAN_JEXTRACT`     | `ffm()` |
 * | `cmake`     | `KONAN_CMAKE`        | `cmake { }` |
 * | `make`      | `KONAN_MAKE`         | `make { }` |
 * | `cargo`     | `KONAN_CARGO`        | `cargo { }` |
 * | `zig`       | `KONAN_ZIG`          | `zig { }` |
 *
 * Values are absolute paths. All are optional until a task that needs one runs; it then fails saying
 * which value it needs and how to set it. `gradlew konanConfig` prints them all.
 */
abstract class KonanConfig @Inject constructor(private val providers: ProviderFactory) {
    /** The Kotlin/Native distribution: `kotlin-native-prebuilt-<host>-<version>`. See [kotlinNativeDistribution]. */
    abstract val konanHome: DirectoryProperty

    /** The jextract program, for `ffm()`. */
    abstract val jextract: RegularFileProperty

    /** The cmake program, for `cmake { }` blocks. */
    abstract val cmake: RegularFileProperty

    /** The make program, for `make { }` blocks. */
    abstract val make: RegularFileProperty

    /** The cargo program, for `cargo { }`. */
    abstract val cargo: RegularFileProperty

    /** The zig program, for `zig { }`. */
    abstract val zig: RegularFileProperty

    /**
     * An Android NDK (r23 or newer), required for Android targets: the plugin compiles and links every
     * Android library with it — the default library build, the JNI library, the library FFM loads — and
     * external builds on the `AUTO` toolchain get its compilers. Kotlin/Native's own Android toolchain (NDK
     * r20) is used only by external builds that ask for it with `KONAN`.
     */
    abstract val androidNdk: DirectoryProperty

    /**
     * Where the Kotlin plugin installs Kotlin/Native for this host: `konanHome.set(kotlinNativeDistribution())`.
     * The project's Kotlin version when it applies the Kotlin plugin, else the plugin's default. Computed,
     * not searched for; a Kotlin Multiplatform build of that version with a native target downloads it.
     */
    fun kotlinNativeDistribution(): File = KonanDistribution.defaultHome(kotlinVersion())

    /** The Kotlin plugin's version, set when the project applies it. */
    internal var kotlinPluginVersion: String? = null

    private fun kotlinVersion(): String = kotlinPluginVersion ?: KonanDistribution.defaultVersion

    /**
     * [field] for the task [usedBy]: this block, else the Gradle property, else the environment variable,
     * else unset. Always present; [ConfigValue.require] checks it when the task runs.
     */
    internal fun readConfigField(field: ConfigField, usedBy: String): Provider<ConfigValue> {
        usages.getOrPut(field) { sortedSetOf() } += usedBy
        return resolve(field, usedBy)
    }

    /** [field] as [readConfigField] reads it, without counting [usedBy] as needing it. */
    internal fun resolve(field: ConfigField, usedBy: String): Provider<ConfigValue> {
        val hint = when (field) {
            ConfigField.KONAN_HOME -> konanHomeHint()
            ConfigField.ANDROID_NDK ->
                "Android Studio installs NDKs under <Android SDK>/ndk/<version> (SDK Manager → SDK Tools → NDK (Side by side))."
            else -> null
        }
        fun value(source: ConfigSource) = { raw: String -> ConfigValue(field, raw, source, usedBy, hint) }
        return property(field).map { it.asFile.absolutePath }.map(value(ConfigSource.DSL))
            .orElse(providers.gradleProperty(field.gradleProperty).map(value(ConfigSource.GRADLE_PROPERTY)))
            .orElse(providers.environmentVariable(field.environmentVariable).map(value(ConfigSource.ENVIRONMENT)))
            .orElse(ConfigValue(field, null, null, usedBy, hint))
    }

    /** [field] as the program an external build step runs. */
    internal fun executable(field: ConfigField, usedBy: String): Provider<Executable> =
        readConfigField(field, usedBy).map { Executable.Configured(it) }

    /** The tasks that asked for each field, for `konanConfig`. */
    internal val usages = mutableMapOf<ConfigField, MutableSet<String>>()

    private fun property(field: ConfigField): FileSystemLocationProperty<*> = when (field) {
        ConfigField.KONAN_HOME -> konanHome
        ConfigField.JEXTRACT -> jextract
        ConfigField.CMAKE -> cmake
        ConfigField.MAKE -> make
        ConfigField.CARGO -> cargo
        ConfigField.ZIG -> zig
        ConfigField.ANDROID_NDK -> androidNdk
    }

    /** Not whether it is installed: that is known when the task runs, not when the configuration cache stores. */
    private fun konanHomeHint(): String =
        "Kotlin/Native ${kotlinVersion()}, this project's, installs to ${KonanDistribution.defaultHome(kotlinVersion())}:\n" +
            "  konan { config { konanHome.set(kotlinNativeDistribution()) } }"
}

/** The project's `konan { config { } }`. */
internal val Project.konanConfig: KonanConfig get() = extensions.getByType(KonanExtension::class.java).config
