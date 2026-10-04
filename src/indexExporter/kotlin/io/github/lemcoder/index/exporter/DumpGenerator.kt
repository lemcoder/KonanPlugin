package io.github.lemcoder.index.exporter

import org.jetbrains.kotlin.native.interop.indexer.*
import java.io.File

/**
 * Renders the [NativeIndex] as deterministic text into `index.txt`: what cinterop sees in the headers,
 * for reading, diffing across Kotlin versions, and tests.
 *
 * Declarations referenced from a type are rendered by name, not expanded, so cycles terminate.
 */
object DumpGenerator : BindingGenerator {
    override val name = "dump"

    override fun generate(context: GenerationContext, outputDirectory: File) {
        outputDirectory.resolve("index.txt").writeText(dumpNativeIndex(context.index))
    }
}

internal fun dumpNativeIndex(index: NativeIndex): String = buildString {
    index.includedHeaders.map { it.value }.sorted().forEach { appendLine("header $it") }
    index.structs.sortedBy { it.spelling }.forEach { appendStruct(it) }
    index.enums.sortedBy { it.spelling }.forEach { appendEnum(it) }
    index.typedefs.sortedBy { it.name }.forEach {
        appendLine("typedef ${it.name} = ${render(it.aliased)} @${it.location.headerId.value}")
    }
    index.functions.sortedBy { it.fullName }.forEach { appendLine(renderFunction(it)) }
    index.globals.sortedBy { it.fullName }.forEach { appendLine(renderGlobal(it)) }
    index.macroConstants.sortedBy { it.name }.forEach { appendLine(renderConstant(it)) }
    index.wrappedMacros.sortedBy { it.name }.forEach { appendLine("macro ${it.name}: ${render(it.type)}") }
}

private fun StringBuilder.appendStruct(decl: StructDecl) {
    val anonymous = if (decl.isAnonymous) " anonymous" else ""
    appendLine("struct ${decl.spelling}$anonymous @${decl.location.headerId.value}")
    val def = decl.def
    if (def == null) appendLine("  (forward declaration)") else appendStructDef(def, indent = "  ")
}

private fun StringBuilder.appendStructDef(def: StructDef, indent: String) {
    appendLine("$indent${def.kind} size=${def.size} align=${def.align}")
    def.members.forEach { member ->
        when (member) {
            is Field -> appendLine(
                "$indent  field ${member.name}: ${render(member.type)} " +
                    "offset=${member.offset} size=${member.typeSize} align=${member.typeAlign}"
            )
            is BitField -> appendLine(
                "$indent  bitfield ${member.name}: ${render(member.type)} offset=${member.offset} bits=${member.size}"
            )
            is IncompleteField -> appendLine("$indent  incomplete ${member.name}")
            is AnonymousInnerRecord -> {
                appendLine("$indent  anonymous record")
                appendStructDef(member.def, "$indent    ")
            }
        }
    }
    def.methods.forEach { appendLine("$indent  method ${renderFunction(it)}") }
    def.staticFields.forEach { appendLine("$indent  static ${renderGlobal(it)}") }
}

private fun StringBuilder.appendEnum(def: EnumDef) {
    val anonymous = if (def.isAnonymous) " anonymous" else ""
    appendLine("enum ${def.spelling}$anonymous: ${render(def.baseType)} @${def.location.headerId.value}")
    def.constants.forEach {
        val explicit = if (it.isExplicitlyDefined) " explicit" else ""
        appendLine("  ${it.name} = ${it.value}$explicit")
    }
}

private fun renderFunction(f: FunctionDecl): String {
    val params = f.parameters.joinToString { p ->
        "${p.name ?: "_"}: ${render(p.type)}" + if (p.nsConsumed) " nsConsumed" else ""
    }
    val vararg = if (f.isVararg) ", ..." else ""
    return "function ${f.fullName}($params$vararg): ${render(f.returnType)} ${renderAccess(f.directAccess)}"
}

private fun renderGlobal(g: GlobalDecl): String {
    val const = if (g.isConst) "const " else ""
    return "global $const${g.fullName}: ${render(g.type)} ${renderAccess(g.directAccess)}"
}

private fun renderAccess(access: DirectAccess): String = when (access) {
    is DirectAccess.Symbol -> "symbol=${access.name}"
    is DirectAccess.Unavailable -> "unavailable(${access.reason})"
}

private fun renderConstant(c: ConstantDef): String {
    val value = when (c) {
        is IntegerConstantDef -> c.value.toString()
        is FloatingConstantDef -> "${c.value} (bits ${java.lang.Double.doubleToRawLongBits(c.value)})"
        is StringConstantDef -> "\"${c.value}\""
        else -> error("Unknown constant ${c::class.simpleName}")
    }
    return "const ${c.name}: ${render(c.type)} = $value"
}

private fun render(type: Type): String = when (type) {
    CharType -> "char"
    CBoolType -> "_Bool"
    ObjCBoolType -> "BOOL"
    VoidType -> "void"
    UnsupportedType -> "<unsupported>"
    is BoolType -> "bool"
    is IntegerType -> "int${type.size * 8}${if (type.isSigned) "" else "u"}(${type.spelling})"
    is FloatingType -> "float${type.size * 8}(${type.spelling})"
    is VectorType -> "vector<${render(type.elementType)} x ${type.elementCount}>(${type.spelling})"
    is RecordType -> "record ${type.decl.spelling}"
    is EnumType -> "enum ${type.def.spelling}"
    is Typedef -> "typedef ${type.def.name}"
    is PointerType -> buildString {
        append(if (type.isLVReference) "ref " else "ptr ")
        if (type.pointeeIsConst) append("const ")
        append(render(type.pointeeType))
        type.spelling?.let { append(" `$it`") }
    }
    is FunctionType -> "fn(${type.parameterTypes.joinToString { render(it) }}): ${render(type.returnType)}"
    is ConstArrayType -> "${render(type.elemType)}[${type.length}]"
    is IncompleteArrayType -> "${render(type.elemType)}[]"
    is VariableArrayType -> "${render(type.elemType)}[*]"
    is ObjCPointer -> "<objc ${type::class.simpleName}>"
    else -> error("Unknown type ${type::class.qualifiedName}")
}
