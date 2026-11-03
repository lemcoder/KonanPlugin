package io.github.lemcoder.index.exporter

import io.github.lemcoder.index.GeneratorRequest
import org.jetbrains.kotlin.native.interop.indexer.NativeIndex
import java.io.File

/**
 * Turns the [NativeIndex] cinterop built into generated files.
 *
 * Runs in the exporter's process, next to the index, so it reads upstream's model directly: nothing about
 * the index crosses into the Gradle daemon, only what the generator writes.
 */
interface BindingGenerator<in R : GeneratorRequest> {
    /** Writes the output [request] asks for into [outputDirectory], which starts out empty. */
    fun generate(request: R, context: GenerationContext, outputDirectory: File)
}

/** Runs the generator [request] is for. A new request type does not compile until it is handled here. */
fun generate(request: GeneratorRequest, context: GenerationContext, outputDirectory: File) = when (request) {
    GeneratorRequest.Dump -> DumpGenerator.generate(GeneratorRequest.Dump, context, outputDirectory)
    is GeneratorRequest.Jni -> JniGenerator.generate(request, context, outputDirectory)
    is GeneratorRequest.Jextract -> JextractGenerator.generate(request, context, outputDirectory)
}

/**
 * What a generator gets: the index, and the facts about the library cinterop derived it from.
 *
 * The index and the library facts are built on first use, so a generator that needs neither does not
 * pay for parsing the headers.
 */
class GenerationContext(
    /** The bindings' package, decided the way cinterop decides it: `-pkg`, the `.def`'s `package`, or its file name. */
    val packageName: String,
    /** The `.def`'s lines after `---`: C code every translation unit of the library sees. */
    val preambleLines: List<String>,
    indexLibrary: () -> IndexedLibrary,
) {
    private val indexed by lazy(indexLibrary)

    val index: NativeIndex get() = indexed.index

    /** The headers the library includes, as the `.def` (or `-header`) names them. */
    val headers: List<String> get() = indexed.headers

    /**
     * The clang arguments cinterop parsed the headers with: the `.def`'s and the command line's
     * `compilerOpts`, plus the target's own (`-target`, sysroot, builtin includes).
     */
    val compilerArgs: List<String> get() = indexed.compilerArgs
}

/** cinterop's view of the library: its index, and what it was built from. */
class IndexedLibrary(val index: NativeIndex, val headers: List<String>, val compilerArgs: List<String>)

