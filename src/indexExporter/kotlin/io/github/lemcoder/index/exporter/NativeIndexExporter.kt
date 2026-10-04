package io.github.lemcoder.index.exporter

import kotlinx.cinterop.JvmCInteropCallbacks
import org.jetbrains.kotlin.konan.util.DefFile
import org.jetbrains.kotlin.native.interop.gen.ImportsImpl
import org.jetbrains.kotlin.native.interop.gen.jvm.KotlinPlatform
import org.jetbrains.kotlin.native.interop.gen.jvm.buildNativeLibrary
import org.jetbrains.kotlin.native.interop.gen.jvm.parseKeyValuePairs
import org.jetbrains.kotlin.native.interop.gen.jvm.prepareTool
import org.jetbrains.kotlin.native.interop.indexer.buildNativeIndex
import org.jetbrains.kotlin.native.interop.tool.CInteropArguments
import org.jetbrains.kotlin.utils.NativeMemoryAllocator
import java.io.File

/**
 * Entry point run in the Kotlin/Native distribution's JVM: indexes headers with cinterop's own code and
 * hands the resulting index to a [BindingGenerator].
 *
 * ```
 * --generator <name> --output <dir> [--option <key>=<value>]... -- <cinterop command line>
 * ```
 */
fun main(args: Array<String>) {
    val separator = args.indexOf("--")
    require(separator >= 0) { "Expected '--' before the cinterop command line: ${args.joinToString(" ")}" }
    val options = parseOptions(args.copyOfRange(0, separator))
    val cinteropArgs = args.copyOfRange(separator + 1, args.size)

    fun single(name: String) = options[name]?.singleOrNull() ?: error("Expected exactly one --$name")
    val generatorName = single("generator")
    val generator = bindingGenerators[generatorName]
        ?: error("Unknown generator '$generatorName'. Known: ${bindingGenerators.keys.sorted().joinToString()}")
    val output = File(single("output"))
    val generatorOptions = options["option"].orEmpty().associate {
        require('=' in it) { "Expected --option key=value, got '$it'" }
        it.substringBefore('=') to it.substringAfter('=')
    }

    // As cinterop's own entry point does around its run.
    NativeMemoryAllocator.init()
    JvmCInteropCallbacks.init()
    try {
        val context = index(cinteropArgs, generatorOptions)
        output.deleteRecursively()
        output.mkdirs()
        generator.generate(context, output)
    } finally {
        JvmCInteropCallbacks.dispose()
        NativeMemoryAllocator.dispose()
    }
}

/**
 * cinterop's own path from a command line to a `NativeIndex`: the steps its private `processCLib` takes
 * before generating any Kotlin. `-def`, `-pkg`, `-target`, `-compiler-option`, `-Xkonan-home` and the rest
 * are cinterop's options, parsed by cinterop.
 *
 * [prepareTool] and [buildNativeLibrary] are `internal` to the compiler jar; this source set compiles with
 * that jar as a friend module (see buildSrc's native-index plugin), so a signature change in a Kotlin
 * release fails compilation here, at the `kotlinNativeVersion` bump.
 */
private fun index(cinteropArgs: Array<String>, generatorOptions: Map<String, String>): GenerationContext {
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
    val defFile = arguments.def?.let(::File)
    val def = DefFile(defFile, tool.target)
    // No dependency klibs, so no headers are imported from elsewhere.
    val library = buildNativeLibrary(tool, def, arguments, ImportsImpl(emptyMap()))
    val index = buildNativeIndex(library, arguments.verbose, macroNamesCollectingMode = arguments.macroCollectionImpl).index

    // As processCLib names the package.
    val packageName = arguments.pkg ?: def.config.packageName
        ?: checkNotNull(defFile) { "Either -def or -pkg is required" }.name.split('.').reversed().drop(1).joinToString(".")
    return GenerationContext(
        index = index,
        packageName = packageName,
        headers = library.includes.map { it.headerPath },
        preambleLines = def.defHeaderLines,
        options = generatorOptions,
    )
}

private fun parseOptions(args: Array<String>): Map<String, List<String>> {
    require(args.size % 2 == 0) { "Options come in --name value pairs: ${args.joinToString(" ")}" }
    return args.toList().chunked(2).groupBy({ (name, _) -> name.removePrefix("--") }, { (_, value) -> value })
}
