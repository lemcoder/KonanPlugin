package io.github.lemcoder.index.exporter

import org.jetbrains.kotlin.native.interop.indexer.DirectAccess
import org.jetbrains.kotlin.native.interop.indexer.FunctionDecl
import org.jetbrains.kotlin.native.interop.indexer.GlobalDecl

/** How [function] is reached, for the dump: ` symbol=…`, or empty when cinterop does not say. */
internal fun access(function: FunctionDecl): String = render(function.directAccess)

/** How [global] is reached, for the dump: ` symbol=…`, or empty when cinterop does not say. */
internal fun access(global: GlobalDecl): String = render(global.directAccess)

private fun render(access: DirectAccess): String = when (access) {
    is DirectAccess.Symbol -> " symbol=${access.name}"
    is DirectAccess.Unavailable -> " unavailable(${access.reason})"
}
