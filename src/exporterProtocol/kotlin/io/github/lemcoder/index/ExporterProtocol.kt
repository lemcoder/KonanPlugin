package io.github.lemcoder.index

import io.github.lemcoder.model.BindingVisibility
import java.io.File
import java.io.Serializable

/**
 * What the index exporter is asked to generate: one subtype per generator, carrying that generator's
 * settings. Compiled into both the plugin and the exporter, so the two sides cannot disagree on it.
 */
sealed class GeneratorRequest : Serializable {
    /** A text rendering of the index, `index.txt`. */
    data object Dump : GeneratorRequest() {
        private fun readResolve(): Any = Dump
    }

    /**
     * JNI bindings and their C stub.
     *
     * @property stubLibrary the library the bindings `System.loadLibrary`; the plugin owns that name.
     */
    data class Jni(val stubLibrary: String, val visibility: BindingVisibility = BindingVisibility.INTERNAL) :
        GeneratorRequest() {
        companion object {
            /** The Kotlin bindings: `kotlin/<package>/<facade>.kt`. */
            const val KOTLIN_DIR = "kotlin"

            /** The stub, `c/<stubLibrary>.c`: what the plugin or the project's own build compiles. */
            const val C_DIR = "c"

            /** `jni.h`, which the plugin ships, for compiling the stub without a JDK. */
            const val INCLUDE_DIR = "include"
        }
    }

    /**
     * FFM bindings, by jextract.
     *
     * @property library the shared library the bindings `System.loadLibrary`.
     * @property jextract the jextract executable; `jextract` on the PATH when null.
     * @property headerClass the class holding the functions; the package's last segment when null.
     * @property api the FFM API the bindings are written against.
     */
    data class Jextract(
        val library: String,
        val jextract: File? = null,
        val headerClass: String? = null,
        val api: FfmApi = FfmApi.JAVA,
    ) : GeneratorRequest()
}

/** Which implementation of the Foreign Function & Memory API generated FFM bindings call. */
enum class FfmApi {
    /** The JDK's own `java.lang.foreign`, JDK 22+. */
    JAVA,

    /** PanamaPort's backport for Android 8.0+: `com.v7878.foreign`, with its own `VarHandle`. */
    PANAMA_PORT,
}

/**
 * One run of the exporter, as its command line: the [request], where its output goes, and the cinterop
 * command line that describes the library. [toArgs] and [parse] are the only places the flags are spelled.
 */
data class ExporterInvocation(
    val request: GeneratorRequest,
    val outputDirectory: File,
    val cinteropArgs: List<String>,
) {
    fun toArgs(): List<String> = buildList {
        fun flag(flag: Flag, value: Any) { add(flag.spelling); add(value.toString()) }
        flag(Flag.GENERATOR, Kind.of(request))
        flag(Flag.OUTPUT, outputDirectory.absolutePath)
        when (request) {
            GeneratorRequest.Dump -> {}
            is GeneratorRequest.Jni -> {
                flag(Flag.STUB_LIBRARY, request.stubLibrary)
                flag(Flag.VISIBILITY, request.visibility)
            }
            is GeneratorRequest.Jextract -> {
                flag(Flag.LIBRARY, request.library)
                request.jextract?.let { flag(Flag.JEXTRACT, it.absolutePath) }
                request.headerClass?.let { flag(Flag.HEADER_CLASS, it) }
                flag(Flag.FFM_API, request.api)
            }
        }
        add(SEPARATOR)
        addAll(cinteropArgs)
    }

    companion object {
        /** Everything after it is cinterop's own command line. */
        private const val SEPARATOR = "--"

        fun parse(args: Array<String>): ExporterInvocation {
            val separator = args.indexOf(SEPARATOR)
            require(separator >= 0) { "Expected '$SEPARATOR' before the cinterop command line: ${args.joinToString(" ")}" }
            val own = args.copyOfRange(0, separator)
            require(own.size % 2 == 0) { "Exporter options come in flag/value pairs: ${own.joinToString(" ")}" }
            val values = own.toList().chunked(2).associate { (flag, value) ->
                (Flag.entries.firstOrNull { it.spelling == flag } ?: error("Unknown exporter flag $flag")) to value
            }
            fun required(flag: Flag) = values[flag] ?: error("Missing ${flag.spelling}")

            val request = when (Kind.valueOf(required(Flag.GENERATOR))) {
                Kind.DUMP -> GeneratorRequest.Dump
                Kind.JNI -> GeneratorRequest.Jni(
                    stubLibrary = required(Flag.STUB_LIBRARY),
                    visibility = BindingVisibility.valueOf(required(Flag.VISIBILITY)),
                )
                Kind.JEXTRACT -> GeneratorRequest.Jextract(
                    library = required(Flag.LIBRARY),
                    jextract = values[Flag.JEXTRACT]?.let(::File),
                    headerClass = values[Flag.HEADER_CLASS],
                    api = FfmApi.valueOf(required(Flag.FFM_API)),
                )
            }
            return ExporterInvocation(request, File(required(Flag.OUTPUT)), args.copyOfRange(separator + 1, args.size).toList())
        }
    }

    private enum class Kind {
        DUMP, JNI, JEXTRACT;

        companion object {
            fun of(request: GeneratorRequest) = when (request) {
                GeneratorRequest.Dump -> DUMP
                is GeneratorRequest.Jni -> JNI
                is GeneratorRequest.Jextract -> JEXTRACT
            }
        }
    }

    private enum class Flag(val spelling: String) {
        GENERATOR("--generator"),
        OUTPUT("--output"),
        STUB_LIBRARY("--stub-library"),
        VISIBILITY("--visibility"),
        LIBRARY("--library"),
        JEXTRACT("--jextract"),
        HEADER_CLASS("--header-class"),
        FFM_API("--ffm-api"),
    }
}
