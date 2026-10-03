// The three symbols upstream's NativeIndex.kt and HeaderToIdMapper.kt reference from the half of the
// indexer that is bound to libclang. The model only needs them to compile: indexing itself runs in
// the Kotlin/Native distribution's JVM, never against these.
package org.jetbrains.kotlin.native.interop.indexer

/** Copied from upstream `ModuleSupport.kt`. */
data class IncludeInfo(val headerPath: String, val moduleName: String?)

fun headerContentsHash(filePath: String): String =
    error("Header hashing belongs to the indexer, which runs in the Kotlin/Native distribution.")

internal fun buildNativeIndexImpl(
    library: NativeLibrary,
    verbose: Boolean,
    allowPrecompiledHeaders: Boolean,
    macroNamesCollectingMode: MacroNamesCollectingMode,
): IndexerResult = error("Indexing runs in the Kotlin/Native distribution; read its output with NativeIndexReader.")
