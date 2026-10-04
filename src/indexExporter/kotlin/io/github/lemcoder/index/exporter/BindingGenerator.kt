package io.github.lemcoder.index.exporter

import org.jetbrains.kotlin.native.interop.indexer.NativeIndex
import java.io.File

/**
 * Turns the [NativeIndex] cinterop built into generated files.
 *
 * Runs in the exporter's process, next to the index, so it reads upstream's model directly: nothing about
 * the index crosses into the Gradle daemon, only what the generator writes.
 */
interface BindingGenerator {
    /** How the exporter's command line and the Gradle tasks name this generator. */
    val name: String

    /** Writes this generator's output into [outputDirectory], which starts out empty. */
    fun generate(context: GenerationContext, outputDirectory: File)
}

/** What a generator gets: the index, and the facts about the library cinterop derived it from. */
class GenerationContext(
    val index: NativeIndex,
    /** The bindings' package, decided the way cinterop decides it: `-pkg`, the `.def`'s `package`, or its file name. */
    val packageName: String,
    /** The headers the library includes, as the `.def` (or `-header`) names them. */
    val headers: List<String>,
    /** The `.def`'s lines after `---`: C code every translation unit of the library sees. */
    val preambleLines: List<String>,
    /**
     * The clang arguments cinterop parsed the headers with: the `.def`'s and the command line's
     * `compilerOpts`, plus the target's own (`-target`, sysroot, builtin includes).
     */
    val compilerArgs: List<String>,
    /** `--option key=value` pairs from the exporter's command line, for the generator to interpret. */
    val options: Map<String, String>,
)

/** Every generator the exporter can run, by [BindingGenerator.name]. */
val bindingGenerators: Map<String, BindingGenerator> =
    listOf(DumpGenerator, JniGenerator, JextractGenerator).associateBy { it.name }
