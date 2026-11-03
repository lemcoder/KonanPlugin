package io.github.lemcoder.index.exporter

import org.jetbrains.kotlin.native.interop.gen.jvm.KotlinPlatform
import org.jetbrains.kotlin.native.interop.gen.jvm.parseKeyValuePairs
import org.jetbrains.kotlin.native.interop.gen.jvm.prepareTool
import org.jetbrains.kotlin.native.interop.indexer.NativeIndex
import org.jetbrains.kotlin.native.interop.indexer.NativeLibrary
import org.jetbrains.kotlin.native.interop.indexer.buildNativeIndex
import org.jetbrains.kotlin.native.interop.tool.CInteropArguments
import org.jetbrains.kotlin.native.interop.tool.ToolConfig

/**
 * [cinteropArgs] parsed by cinterop, as its own entry point parses them, with the distribution the
 * plugin passes as the konan.home system property: from 2.4.20 cinterop reads it from -Xkonan-home.
 */
internal fun cinteropArguments(cinteropArgs: Array<String>): CInteropArguments {
    val home = checkNotNull(System.getProperty("konan.home")) { "konan.home is not set" }
    return CInteropArguments().apply { argParser.parse(arrayOf("-Xkonan-home", home) + cinteropArgs) }
}

/** cinterop's tool configuration for [arguments]: the target, its clang and sysroot. */
internal fun toolConfig(arguments: CInteropArguments): ToolConfig = prepareTool(
    arguments.target,
    KotlinPlatform.NATIVE,
    runFromDaemon = false,
    parseKeyValuePairs(arguments.overrideKonanProperties),
    konanDataDir = arguments.konanDataDir,
    konanHome = arguments.konanHome,
)

/** cinterop's index of [library]. */
internal fun nativeIndex(library: NativeLibrary, arguments: CInteropArguments): NativeIndex =
    buildNativeIndex(library, arguments.verbose, macroNamesCollectingMode = arguments.macroCollectionImpl).index
