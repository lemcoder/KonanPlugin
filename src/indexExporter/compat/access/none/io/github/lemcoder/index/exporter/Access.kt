package io.github.lemcoder.index.exporter

import org.jetbrains.kotlin.native.interop.indexer.FunctionDecl
import org.jetbrains.kotlin.native.interop.indexer.GlobalDecl

// Before 2.3.20 cinterop does not record how a declaration is reached.

/** How [function] is reached, for the dump: ` symbol=…`, or empty when cinterop does not say. */
internal fun access(function: FunctionDecl): String = ""

/** How [global] is reached, for the dump: ` symbol=…`, or empty when cinterop does not say. */
internal fun access(global: GlobalDecl): String = ""
