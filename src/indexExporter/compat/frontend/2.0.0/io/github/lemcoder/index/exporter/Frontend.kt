package io.github.lemcoder.index.exporter

import org.jetbrains.kotlin.native.interop.gen.jvm.KotlinPlatform
import org.jetbrains.kotlin.native.interop.gen.jvm.parseKeyValuePairs
import org.jetbrains.kotlin.native.interop.gen.jvm.prepareTool
import org.jetbrains.kotlin.native.interop.indexer.NativeIndex
import org.jetbrains.kotlin.native.interop.indexer.NativeLibrary
import org.jetbrains.kotlin.native.interop.indexer.buildNativeIndex
import org.jetbrains.kotlin.native.interop.tool.CInteropArguments
import org.jetbrains.kotlin.native.interop.tool.ToolConfig

// Before 2.4.20 cinterop takes the distribution from the konan.home system property, which the plugin
// sets, and has no -Xkonan-home.

/** [cinteropArgs] parsed by cinterop, as its own entry point parses them. */
internal fun cinteropArguments(cinteropArgs: Array<String>): CInteropArguments =
    CInteropArguments().apply { argParser.parse(cinteropArgs) }

/** cinterop's tool configuration for [arguments]: the target, its clang and sysroot. */
internal fun toolConfig(arguments: CInteropArguments): ToolConfig = prepareTool(
    arguments.target,
    KotlinPlatform.NATIVE,
    runFromDaemon = false,
    parseKeyValuePairs(arguments.overrideKonanProperties),
    konanDataDir = arguments.konanDataDir,
)

/** cinterop's index of [library]. */
internal fun nativeIndex(library: NativeLibrary, arguments: CInteropArguments): NativeIndex =
    buildNativeIndex(library, arguments.verbose).index
