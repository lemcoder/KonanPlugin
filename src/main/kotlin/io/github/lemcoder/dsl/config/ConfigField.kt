package io.github.lemcoder.dsl.config

import org.gradle.api.InvalidUserDataException
import java.io.File
import java.io.Serializable

/**
 * A path the plugin needs from the machine it runs on: `konan { config { } }`, else the Gradle property
 * [gradleProperty], else the environment variable [environmentVariable]. Nothing is searched for: unset
 * is an error, raised by the task that needs the value when it runs.
 */
enum class ConfigField(val dslName: String, val environmentVariable: String, val kind: Kind, val example: String) {
    KONAN_HOME("konanHome", "KONAN_HOME", Kind.DIRECTORY, "/path/to/kotlin-native-prebuilt-<host>-<version>"),
    JEXTRACT("jextract", "KONAN_JEXTRACT", Kind.EXECUTABLE, "/path/to/jextract"),
    CMAKE("cmake", "KONAN_CMAKE", Kind.EXECUTABLE, "/path/to/cmake"),
    MAKE("make", "KONAN_MAKE", Kind.EXECUTABLE, "/path/to/make"),
    CARGO("cargo", "KONAN_CARGO", Kind.EXECUTABLE, "/path/to/cargo"),
    ZIG("zig", "KONAN_ZIG", Kind.EXECUTABLE, "/path/to/zig"),
    ANDROID_NDK("androidNdk", "ANDROID_NDK_HOME", Kind.DIRECTORY, "/path/to/android-sdk/ndk/<version>"),
    ;

    val gradleProperty: String get() = "konan.config.$dslName"

    enum class Kind { DIRECTORY, EXECUTABLE }
}

/** Where a [ConfigValue] came from, in the order they are tried. */
enum class ConfigSource(val description: String) {
    DSL("build script"),
    GRADLE_PROPERTY("Gradle property"),
    ENVIRONMENT("environment variable"),
}

/**
 * One [field] as resolved for [usedBy], a task path: its [raw] value and [source], or neither when it is
 * unset. Resolved lazily and checked by [require] when the task runs, so a build that never runs such a
 * task never needs the value.
 */
data class ConfigValue(
    val field: ConfigField,
    val raw: String?,
    val source: ConfigSource?,
    val usedBy: String,
    /** Extra lines for the error, e.g. the default the build could use. */
    val hint: String? = null,
) : Serializable {
    /** The file named, checked to exist as the field's kind; otherwise an error saying how to set it. */
    fun require(): File {
        problem()?.let(::fail)
        return File(checkNotNull(raw))
    }

    /** What is wrong with the value, or null if it names an existing file of the field's kind. */
    fun problem(): String? {
        val file = File(raw ?: return "is not set")
        return when {
            !file.isAbsolute -> "is not an absolute path"
            !file.exists() -> "does not exist"
            field.kind == ConfigField.Kind.DIRECTORY && !file.isDirectory -> "is not a directory"
            field.kind == ConfigField.Kind.EXECUTABLE && !(file.isFile && file.canExecute()) -> "is not an executable file"
            else -> null
        }
    }

    /** The current value and its origin, for reports: `/opt/x (Gradle property konan.config.x)`. */
    fun describe(): String = when (source) {
        null -> "unset"
        ConfigSource.DSL -> "$raw (${source.description})"
        ConfigSource.GRADLE_PROPERTY -> "$raw (${source.description} ${field.gradleProperty})"
        ConfigSource.ENVIRONMENT -> "$raw (${source.description} ${field.environmentVariable})"
    }

    private fun fail(problem: String): Nothing {
        val name = "konan.config.${field.dslName}"
        val headline = if (raw == null) "$name $problem — $usedBy needs it." else "$name ${describe()} $problem — $usedBy needs it."
        throw InvalidUserDataException(
            buildString {
                appendLine(headline)
                appendLine("Set it to an absolute path in one of these; the first one set wins:")
                appendLine("  1. build script         konan { config { ${field.dslName}.set(file(\"${field.example}\")) } }")
                appendLine("  2. gradle.properties    ${field.gradleProperty}=${field.example}")
                appendLine("                          (~/.gradle/gradle.properties sets it for every build on this machine)")
                append("  3. environment          ${field.environmentVariable}=${field.example}")
                hint?.let { appendLine(); append(it) }
            }
        )
    }
}
