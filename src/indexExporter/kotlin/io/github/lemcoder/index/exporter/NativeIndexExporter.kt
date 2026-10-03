package io.github.lemcoder.index.exporter

import io.github.lemcoder.index.dumpNativeIndex
import kotlinx.cinterop.JvmCInteropCallbacks
import org.jetbrains.kotlin.konan.util.DefFile
import org.jetbrains.kotlin.native.interop.gen.HeaderExclusionPolicyImpl
import org.jetbrains.kotlin.native.interop.gen.HeaderInclusionPolicyImpl
import org.jetbrains.kotlin.native.interop.gen.ImportsImpl
import org.jetbrains.kotlin.native.interop.gen.jvm.KotlinPlatform
import org.jetbrains.kotlin.native.interop.indexer.HeaderToIdMapper
import org.jetbrains.kotlin.native.interop.indexer.IncludeInfo
import org.jetbrains.kotlin.native.interop.indexer.Language
import org.jetbrains.kotlin.native.interop.indexer.NativeLibrary
import org.jetbrains.kotlin.native.interop.indexer.NativeLibraryHeaderFilter
import org.jetbrains.kotlin.native.interop.indexer.buildNativeIndex
import org.jetbrains.kotlin.native.interop.indexer.defaultCompilerArgs
import org.jetbrains.kotlin.native.interop.indexer.predefinedMacros
import org.jetbrains.kotlin.native.interop.tool.ToolConfig
import org.jetbrains.kotlin.utils.NativeMemoryAllocator
import java.io.File

/**
 * Entry point run in the Kotlin/Native distribution's JVM: indexes the headers a `.def` names, the way
 * cinterop does, and writes the resulting `NativeIndex`.
 *
 * ```
 * --konan-home <dir> --def <file> --target <konan target> --out <json>
 *     [--dump <txt>] [--compiler-option <opt>]...
 * ```
 */
fun main(args: Array<String>) {
    val options = parseOptions(args)
    fun single(name: String) = options[name]?.singleOrNull() ?: error("Expected exactly one --$name")

    val konanHome = single("konan-home")
    NativeMemoryAllocator.init()
    JvmCInteropCallbacks.init()
    try {
        val tool = ToolConfig(single("target"), KotlinPlatform.NATIVE, emptyMap(), null, konanHome)
        tool.prepare()
        val def = DefFile(File(single("def")), tool.target)
        val library = nativeLibrary(tool, def, options["compiler-option"].orEmpty())
        val index = buildNativeIndex(library, verbose = false).index

        File(single("out")).writeText(NativeIndexWriter().write(index))
        options["dump"]?.single()?.let { File(it).writeText(dumpNativeIndex(index)) }
    } finally {
        JvmCInteropCallbacks.dispose()
        NativeMemoryAllocator.dispose()
    }
}

/**
 * The [NativeLibrary] cinterop builds for a `.def` (`buildNativeLibrary` in upstream `main.kt`, which is
 * internal), for header-based libraries. Clang modules — Objective-C only — are not supported.
 */
private fun nativeLibrary(tool: ToolConfig, def: DefFile, extraCompilerOptions: List<String>): NativeLibrary {
    val config = def.config
    require(config.modules.isEmpty()) { "Clang modules are not supported by the index exporter yet." }
    val language = when (config.language) {
        null, "C" -> Language.C
        "C++" -> Language.CPP
        "Objective-C" -> Language.OBJECTIVE_C
        else -> error("Unexpected language '${config.language}'")
    }
    val compilerOptions = config.compilerOpts +
        tool.getDefaultCompilerOptsForLanguage(language) +
        extraCompilerOptions +
        "-Wno-builtin-macro-redefined"
    val includes = config.headers.map { IncludeInfo(it, null) }
    return NativeLibrary(
        includes = includes,
        // Keep generated names and timestamps out of the index, as cinterop does (KT-54284).
        additionalPreambleLines = def.defHeaderLines + predefinedMacros.map { "#define $it \"$it\"" },
        compilerArgs = defaultCompilerArgs(language) + compilerOptions + tool.platformCompilerOpts,
        headerToIdMapper = HeaderToIdMapper(sysRoot = tool.sysRoot),
        language = language,
        excludeSystemLibs = config.excludeSystemLibs,
        // No dependency klibs, so nothing is imported from elsewhere.
        headerExclusionPolicy = HeaderExclusionPolicyImpl(ImportsImpl(emptyMap())),
        headerFilter = NativeLibraryHeaderFilter.NameBased(
            HeaderInclusionPolicyImpl(config.headerFilter, config.excludeFilter),
            config.excludeDependentModules,
        ),
        objCClassesIncludingCategories = config.objcClassesIncludingCategories.toSet(),
        allowIncludingObjCCategoriesFromDefFile = config.allowIncludingObjCCategoriesFromDefFile,
    )
}

private fun parseOptions(args: Array<String>): Map<String, List<String>> {
    require(args.size % 2 == 0) { "Options come in --name value pairs: ${args.joinToString(" ")}" }
    return args.toList().chunked(2).groupBy(
        { (name, _) -> name.removePrefix("--") },
        { (_, value) -> value },
    )
}
