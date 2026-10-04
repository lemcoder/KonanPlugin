package io.github.lemcoder.index.exporter

import kotlinx.cinterop.JvmCInteropCallbacks
import org.jetbrains.kotlin.konan.util.DefFile
import org.jetbrains.kotlin.native.interop.gen.ImportsImpl
import org.jetbrains.kotlin.native.interop.gen.jvm.KotlinPlatform
import org.jetbrains.kotlin.native.interop.gen.jvm.buildNativeLibrary
import org.jetbrains.kotlin.native.interop.gen.jvm.parseKeyValuePairs
import org.jetbrains.kotlin.native.interop.gen.jvm.prepareTool
import org.jetbrains.kotlin.native.interop.indexer.NativeIndex
import org.jetbrains.kotlin.native.interop.indexer.buildNativeIndex
import org.jetbrains.kotlin.native.interop.tool.CInteropArguments
import org.jetbrains.kotlin.utils.NativeMemoryAllocator
import java.io.File

/**
 * Entry point run in the Kotlin/Native distribution's JVM: indexes headers with cinterop's own code and
 * hands the resulting [NativeIndex] to a [BindingGenerator].
 *
 * ```
 * --generator <name> --output <dir> -- <cinterop command line>
 * ```
 */
fun main(args: Array<String>) {
    val separator = args.indexOf("--")
    require(separator >= 0) { "Expected '--' before the cinterop command line: ${args.joinToString(" ")}" }
    val options = parseOptions(args.copyOfRange(0, separator))
    val cinteropArgs = args.copyOfRange(separator + 1, args.size)
    val generatorName = options.getValue("generator")
    val generator = bindingGenerators[generatorName]
        ?: error("Unknown generator '$generatorName'. Known: ${bindingGenerators.keys.sorted().joinToString()}")
    val output = File(options.getValue("output"))

    // As cinterop's own entry point does around its run.
    NativeMemoryAllocator.init()
    JvmCInteropCallbacks.init()
    try {
        val index = index(cinteropArgs)
        output.deleteRecursively()
        output.mkdirs()
        generator.generate(index, output)
    } finally {
        JvmCInteropCallbacks.dispose()
        NativeMemoryAllocator.dispose()
    }
}

/**
 * cinterop's own path from a command line to a [NativeIndex]: the steps its private `processCLib` takes
 * before generating any Kotlin. `-def`, `-target`, `-compiler-option`, `-Xkonan-home` and the rest are
 * cinterop's options, parsed by cinterop.
 *
 * [prepareTool] and [buildNativeLibrary] are `internal` to the compiler jar; this source set compiles with
 * that jar as a friend module (see buildSrc's native-index plugin), so a signature change in a Kotlin
 * release fails compilation here, at the `kotlinNativeVersion` bump.
 */
private fun index(cinteropArgs: Array<String>): NativeIndex {
    val arguments = CInteropArguments()
    arguments.argParser.parse(cinteropArgs)
    require(arguments.library.isEmpty()) { "Dependency klibs (-library) are not supported by the index exporter yet." }

    val tool = prepareTool(
        arguments.target,
        KotlinPlatform.NATIVE,
        runFromDaemon = false,
        parseKeyValuePairs(arguments.overrideKonanProperties),
        konanDataDir = arguments.konanDataDir,
        konanHome = arguments.konanHome,
    )
    val def = DefFile(arguments.def?.let(::File), tool.target)
    // No dependency klibs, so no headers are imported from elsewhere.
    val library = buildNativeLibrary(tool, def, arguments, ImportsImpl(emptyMap()))
    return buildNativeIndex(library, arguments.verbose, macroNamesCollectingMode = arguments.macroCollectionImpl).index
}

private fun parseOptions(args: Array<String>): Map<String, String> {
    require(args.size % 2 == 0) { "Options come in --name value pairs: ${args.joinToString(" ")}" }
    return args.toList().chunked(2).associate { (name, value) -> name.removePrefix("--") to value }
}
