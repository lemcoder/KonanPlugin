package io.github.lemcoder.index.exporter

import io.github.lemcoder.index.ExporterInvocation
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
 * Entry point run in the Kotlin/Native distribution's JVM: hands cinterop's view of a library to the
 * generator an [ExporterInvocation] asks for. Its command line is that invocation's [ExporterInvocation.toArgs].
 */
fun main(args: Array<String>) {
    val invocation = ExporterInvocation.parse(args)
    val context = context(invocation.cinteropArgs.toTypedArray())
    val output = invocation.outputDirectory
    output.deleteRecursively()
    output.mkdirs()
    generate(invocation.request, context, output)
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
private fun context(cinteropArgs: Array<String>): GenerationContext {
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

    // As processCLib names the package.
    val packageName = arguments.pkg ?: def.config.packageName
        ?: checkNotNull(defFile) { "Either -def or -pkg is required" }.name.split('.').reversed().drop(1).joinToString(".")
    return GenerationContext(
        packageName = packageName,
        preambleLines = def.defHeaderLines,
    ) {
        // As cinterop's own entry point does around its run; the index is plain objects once built.
        NativeMemoryAllocator.init()
        JvmCInteropCallbacks.init()
        try {
            // No dependency klibs, so no headers are imported from elsewhere.
            val library = buildNativeLibrary(tool, def, arguments, ImportsImpl(emptyMap()))
            val index = buildNativeIndex(library, arguments.verbose, macroNamesCollectingMode = arguments.macroCollectionImpl).index
            IndexedLibrary(index, headers = library.includes.map { it.headerPath }, compilerArgs = library.compilerArgs)
        } finally {
            JvmCInteropCallbacks.dispose()
            NativeMemoryAllocator.dispose()
        }
    }
}
