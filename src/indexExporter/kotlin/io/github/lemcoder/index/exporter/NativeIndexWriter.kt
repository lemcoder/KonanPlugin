package io.github.lemcoder.index.exporter

import org.jetbrains.kotlin.native.interop.indexer.*
import java.util.IdentityHashMap

/**
 * Writes a [NativeIndex] as JSON, for `NativeIndexReader` on the plugin side.
 *
 * The model is a graph, not a tree: a struct can point to itself, and one typedef is referenced from
 * many places. So structs, enums and typedefs are written once each into tables, every declaration a
 * type reaches — not only the ones the index lists — and types refer to them by table position.
 *
 * Objective-C is not supported yet: C and C++ headers never produce it.
 */
internal class NativeIndexWriter {
    private val structs = Table<StructDecl>()
    private val enums = Table<EnumDef>()
    private val typedefs = Table<TypedefDef>()

    fun write(index: NativeIndex): String {
        require(index.objCClasses.isEmpty() && index.objCProtocols.isEmpty() && index.objCCategories.isEmpty()) {
            "The index format does not carry Objective-C declarations yet."
        }
        val root = linkedMapOf<String, Any?>(
            "format" to FORMAT_VERSION,
            "includedHeaders" to index.includedHeaders.map { it.value },
            "structs" to index.structs.map { structs.id(it) },
            "enums" to index.enums.map { enums.id(it) },
            "typedefs" to index.typedefs.map { typedefs.id(it) },
            "functions" to index.functions.map(::function),
            "globals" to index.globals.map(::global),
            "macroConstants" to index.macroConstants.map(::constant),
            "wrappedMacros" to index.wrappedMacros.map { obj("name" to it.name, "type" to type(it.type)) },
        )
        // Encoding a declaration can reach further ones, in any of the three tables.
        while (structs.encodePending(::structDecl) or enums.encodePending(::enumDef) or typedefs.encodePending(::typedefDef)) {
            // until no table grows
        }
        root["declarations"] = obj(
            "structs" to structs.encoded,
            "enums" to enums.encoded,
            "typedefs" to typedefs.encoded,
        )
        return Json.write(root)
    }

    private fun structDecl(d: StructDecl) = obj(
        "spelling" to d.spelling,
        "anonymous" to d.isAnonymous,
        "header" to d.location.headerId.value,
        "def" to d.def?.let(::structDef),
    )

    private fun structDef(d: StructDef): Any = obj(
        "size" to d.size,
        "align" to d.align,
        "kind" to d.kind.name,
        "members" to d.members.map(::member),
        "methods" to d.methods.map(::function),
        "staticFields" to d.staticFields.map(::global),
    )

    private fun member(m: StructMember): Any = when (m) {
        is Field -> obj(
            "k" to "field", "name" to m.name, "type" to type(m.type),
            "offset" to m.offset, "typeSize" to m.typeSize, "typeAlign" to m.typeAlign,
        )
        is BitField -> obj("k" to "bitField", "name" to m.name, "type" to type(m.type), "offset" to m.offset, "size" to m.size)
        is IncompleteField -> obj("k" to "incomplete", "name" to m.name)
        is AnonymousInnerRecord -> obj("k" to "anonymous", "def" to structDef(m.def))
    }

    private fun enumDef(d: EnumDef) = obj(
        "spelling" to d.spelling,
        "baseType" to type(d.baseType),
        "anonymous" to d.isAnonymous,
        "header" to d.location.headerId.value,
        "constants" to d.constants.map {
            obj("name" to it.name, "value" to it.value, "explicit" to it.isExplicitlyDefined)
        },
    )

    private fun typedefDef(d: TypedefDef) = obj(
        "name" to d.name,
        "aliased" to type(d.aliased),
        "header" to d.location.headerId.value,
    )

    private fun function(f: FunctionDecl) = obj(
        "name" to f.name,
        "params" to f.parameters.map { obj("name" to it.name, "type" to type(it.type), "nsConsumed" to it.nsConsumed) },
        "ret" to type(f.returnType),
        "vararg" to f.isVararg,
        "access" to access(f.directAccess),
        "parent" to f.parentName,
    )

    private fun global(g: GlobalDecl) = obj(
        "name" to g.name,
        "type" to type(g.type),
        "const" to g.isConst,
        "access" to access(g.directAccess),
        "parent" to g.parentName,
    )

    private fun access(a: DirectAccess) = when (a) {
        is DirectAccess.Symbol -> obj("symbol" to a.name)
        is DirectAccess.Unavailable -> obj("unavailable" to a.reason)
    }

    private fun constant(c: ConstantDef) = when (c) {
        is IntegerConstantDef -> obj("k" to "int", "name" to c.name, "type" to type(c.type), "value" to c.value)
        // Raw bits: exact, and JSON has no NaN or infinity.
        is FloatingConstantDef -> obj(
            "k" to "float", "name" to c.name, "type" to type(c.type),
            "bits" to java.lang.Double.doubleToRawLongBits(c.value),
        )
        is StringConstantDef -> obj("k" to "string", "name" to c.name, "type" to type(c.type), "value" to c.value)
        else -> error("Unknown macro constant ${c::class.qualifiedName}")
    }

    private fun type(t: Type): Any = when (t) {
        CharType -> kind("char")
        CBoolType -> kind("cbool")
        ObjCBoolType -> kind("objcbool")
        VoidType -> kind("void")
        UnsupportedType -> kind("unsupported")
        is BoolType -> kind("bool")
        is IntegerType -> obj("k" to "int", "size" to t.size, "signed" to t.isSigned, "spelling" to t.spelling)
        is FloatingType -> obj("k" to "float", "size" to t.size, "spelling" to t.spelling)
        is VectorType -> obj("k" to "vector", "elem" to type(t.elementType), "count" to t.elementCount, "spelling" to t.spelling)
        is RecordType -> obj("k" to "record", "ref" to structs.id(t.decl))
        is EnumType -> obj("k" to "enum", "ref" to enums.id(t.def))
        is Typedef -> obj("k" to "typedef", "ref" to typedefs.id(t.def))
        is PointerType -> obj(
            "k" to "pointer", "to" to type(t.pointeeType), "const" to t.pointeeIsConst,
            "lvref" to t.isLVReference, "spelling" to t.spelling,
        )
        is FunctionType -> obj("k" to "function", "params" to t.parameterTypes.map(::type), "ret" to type(t.returnType))
        is ConstArrayType -> obj("k" to "array", "elem" to type(t.elemType), "length" to t.length)
        is IncompleteArrayType -> obj("k" to "incompleteArray", "elem" to type(t.elemType))
        is VariableArrayType -> obj("k" to "variableArray", "elem" to type(t.elemType))
        is ObjCPointer -> error("The index format does not carry Objective-C types yet: ${t::class.simpleName}")
        else -> error("Unknown type ${t::class.qualifiedName}")
    }

    private fun kind(k: String) = obj("k" to k)

    private fun obj(vararg entries: Pair<String, Any?>): Map<String, Any?> = linkedMapOf(*entries)

    /** Declarations by identity, numbered in the order first reached. */
    private class Table<T : Any> {
        private val ids = IdentityHashMap<T, Int>()
        private val items = mutableListOf<T>()
        val encoded = mutableListOf<Any>()

        fun id(item: T): Int = ids.getOrPut(item) { items.add(item); items.size - 1 }

        /** Encodes what was reached since the last call; true if there was anything. */
        fun encodePending(encode: (T) -> Any): Boolean {
            val before = encoded.size
            while (encoded.size < items.size) encoded.add(encode(items[encoded.size]))
            return encoded.size > before
        }
    }

    companion object {
        const val FORMAT_VERSION = 1
    }
}

/** Just enough JSON writing for the index: maps, lists, strings, numbers, booleans and null. */
internal object Json {
    fun write(value: Any?): String = StringBuilder().also { write(it, value, "") }.toString()

    private fun write(out: StringBuilder, value: Any?, indent: String) {
        when (value) {
            null -> out.append("null")
            is String -> string(out, value)
            is Number, is Boolean -> out.append(value.toString())
            is Map<*, *> -> {
                if (value.isEmpty()) return run { out.append("{}") }
                out.append("{\n")
                value.entries.forEachIndexed { i, (k, v) ->
                    out.append("$indent  ")
                    string(out, k as String)
                    out.append(": ")
                    write(out, v, "$indent  ")
                    out.append(if (i < value.size - 1) ",\n" else "\n")
                }
                out.append("$indent}")
            }
            is List<*> -> {
                if (value.isEmpty()) return run { out.append("[]") }
                out.append("[\n")
                value.forEachIndexed { i, v ->
                    out.append("$indent  ")
                    write(out, v, "$indent  ")
                    out.append(if (i < value.size - 1) ",\n" else "\n")
                }
                out.append("$indent]")
            }
            else -> error("Cannot write ${value::class.qualifiedName} as JSON")
        }
    }

    private fun string(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c < ' ' -> out.append("\\u%04x".format(c.code))
            else -> out.append(c)
        }
        out.append('"')
    }
}
