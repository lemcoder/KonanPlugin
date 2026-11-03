package io.github.lemcoder.index.exporter

import io.github.lemcoder.index.ExporterInvocation
import kotlinx.cinterop.JvmCInteropCallbacks
import org.jetbrains.kotlin.konan.util.DefFile
import org.jetbrains.kotlin.native.interop.gen.ImportsImpl
import org.jetbrains.kotlin.native.interop.gen.jvm.buildNativeLibrary
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
 * `prepareTool` and [buildNativeLibrary] are `internal` to the compiler jar; the exporter compiles with
 * that jar as a friend module (see buildSrc's native-index plugin), once per supported Kotlin/Native
 * version, so a signature change fails compilation for that version. What differs between versions is in
 * `src/indexExporter/compat` ([cinteropArguments], [toolConfig], [nativeIndex]).
 */
private fun context(cinteropArgs: Array<String>): GenerationContext {
    val arguments = cinteropArguments(cinteropArgs)
    require(arguments.library.isEmpty()) { "Dependency klibs (-library) are not supported by the index exporter yet." }

    val tool = toolConfig(arguments)
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
            val index = nativeIndex(library, arguments)
            IndexedLibrary(index, headers = library.includes.map { it.headerPath }, compilerArgs = library.compilerArgs)
        } finally {
            JvmCInteropCallbacks.dispose()
            NativeMemoryAllocator.dispose()
        }
    }
}
