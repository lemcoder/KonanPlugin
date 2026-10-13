package io.github.lemcoder.index.exporter

import org.jetbrains.kotlin.native.interop.indexer.EnumDef
import org.jetbrains.kotlin.native.interop.indexer.StructDecl

// Before 2.0.20 cinterop has no isAnonymous; clang spells an unnamed tag "struct (unnamed at …)" or
// "(anonymous struct at …)". From 2.0.20 the members of the same name win over these.

internal val StructDecl.isAnonymous: Boolean get() = isAnonymousSpelling(spelling)

internal val EnumDef.isAnonymous: Boolean get() = isAnonymousSpelling(spelling)

private fun isAnonymousSpelling(spelling: String) = "(unnamed" in spelling || "(anonymous" in spelling
