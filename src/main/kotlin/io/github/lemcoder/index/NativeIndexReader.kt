package io.github.lemcoder.index

import groovy.json.JsonSlurper
import org.jetbrains.kotlin.native.interop.indexer.*
import java.io.File

/**
 * Reads the file the index exporter writes back into upstream's [NativeIndex] model — the plugin's copy
 * of it, compiled from the pinned Kotlin sources.
 *
 * Structs are created first without definitions and filled in afterwards, because a definition can
 * refer back to its own struct. Enums and typedefs are built on first use: in C they cannot form a
 * cycle except through a struct, so recursion ends.
 */
object NativeIndexReader {
    fun read(file: File): NativeIndex = Decoder(JsonSlurper().parse(file).asMap()).index()
}

private class Decoder(private val root: Map<String, Any?>) {
    private val declarations = root.map("declarations")
    private val structTable = declarations.list("structs").map { it.asMap() }
    private val enumTable = declarations.list("enums").map { it.asMap() }
    private val typedefTable = declarations.list("typedefs").map { it.asMap() }

    private val structs = structTable.map { DecodedStructDecl(it.string("spelling"), it.bool("anonymous"), location(it)) }
    private val enums = arrayOfNulls<EnumDef>(enumTable.size)
    private val typedefs = arrayOfNulls<TypedefDef>(typedefTable.size)

    fun index(): NativeIndex {
        val format = root.int("format")
        check(format == FORMAT_VERSION) { "Index format $format; this plugin reads $FORMAT_VERSION." }
        structTable.forEachIndexed { i, s -> structs[i].def = s["def"]?.let { structDef(it.asMap()) } }
        return DecodedNativeIndex(
            structs = root.ids("structs").map { structs[it] },
            enums = root.ids("enums").map(::enum),
            typedefs = root.ids("typedefs").map(::typedef),
            functions = root.list("functions").map { function(it.asMap()) },
            globals = root.list("globals").map { global(it.asMap()) },
            macroConstants = root.list("macroConstants").map { constant(it.asMap()) },
            wrappedMacros = root.list("wrappedMacros").map { it.asMap().let { m -> WrappedMacroDef(m.string("name"), type(m.map("type"))) } },
            includedHeaders = root.list("includedHeaders").map { HeaderId(it as String) },
        )
    }

    private fun enum(id: Int): EnumDef = enums[id] ?: enumTable[id].let { e ->
        DecodedEnumDef(
            spelling = e.string("spelling"),
            baseType = type(e.map("baseType")),
            isAnonymous = e.bool("anonymous"),
            location = location(e),
            constants = e.list("constants").map {
                it.asMap().let { c -> EnumConstant(c.string("name"), c.long("value"), c.bool("explicit")) }
            },
        )
    }.also { enums[id] = it }

    private fun typedef(id: Int): TypedefDef = typedefs[id] ?: typedefTable[id].let { t ->
        TypedefDef(type(t.map("aliased")), t.string("name"), location(t))
    }.also { typedefs[id] = it }

    private fun structDef(d: Map<String, Any?>): StructDef = DecodedStructDef(
        size = d.long("size"),
        align = d.int("align"),
        kind = StructDef.Kind.valueOf(d.string("kind")),
        members = d.list("members").map { member(it.asMap()) },
        methods = d.list("methods").map { function(it.asMap()) },
        staticFields = d.list("staticFields").map { global(it.asMap()) },
    )

    private fun member(m: Map<String, Any?>): StructMember = when (val k = m.string("k")) {
        "field" -> Field(m.string("name"), type(m.map("type")), m.long("offset"), m.long("typeSize"), m.long("typeAlign"))
        "bitField" -> BitField(m.string("name"), type(m.map("type")), m.long("offset"), m.int("size"))
        "incomplete" -> IncompleteField(m.string("name"))
        "anonymous" -> AnonymousInnerRecord(structDef(m.map("def")))
        else -> error("Unknown struct member kind '$k'")
    }

    private fun function(f: Map<String, Any?>) = FunctionDecl(
        name = f.string("name"),
        parameters = f.list("params").map {
            it.asMap().let { p -> Parameter(p["name"] as String?, type(p.map("type")), p.bool("nsConsumed")) }
        },
        returnType = type(f.map("ret")),
        isVararg = f.bool("vararg"),
        directAccess = access(f.map("access")),
        parentName = f["parent"] as String?,
    )

    private fun global(g: Map<String, Any?>) = GlobalDecl(
        name = g.string("name"),
        type = type(g.map("type")),
        isConst = g.bool("const"),
        directAccess = access(g.map("access")),
        parentName = g["parent"] as String?,
    )

    private fun access(a: Map<String, Any?>): DirectAccess =
        (a["symbol"] as String?)?.let { DirectAccess.Symbol(it) } ?: DirectAccess.Unavailable(a.string("unavailable"))

    private fun constant(c: Map<String, Any?>): ConstantDef {
        val name = c.string("name")
        val type = type(c.map("type"))
        return when (val k = c.string("k")) {
            "int" -> IntegerConstantDef(name, type, c.long("value"))
            "float" -> FloatingConstantDef(name, type, java.lang.Double.longBitsToDouble(c.long("bits")))
            "string" -> StringConstantDef(name, type, c.string("value"))
            else -> error("Unknown macro constant kind '$k'")
        }
    }

    private fun type(t: Map<String, Any?>): Type = when (val k = t.string("k")) {
        "char" -> CharType
        "cbool" -> CBoolType
        "objcbool" -> ObjCBoolType
        "void" -> VoidType
        "unsupported" -> UnsupportedType
        "bool" -> BoolType()
        "int" -> IntegerType(t.int("size"), t.bool("signed"), t.string("spelling"))
        "float" -> FloatingType(t.int("size"), t.string("spelling"))
        "vector" -> VectorType(type(t.map("elem")), t.int("count"), t.string("spelling"))
        "record" -> RecordType(structs[t.int("ref")])
        "enum" -> EnumType(enum(t.int("ref")))
        "typedef" -> Typedef(typedef(t.int("ref")))
        "pointer" -> PointerType(type(t.map("to")), t.bool("const"), t.bool("lvref"), t["spelling"] as String?)
        "function" -> FunctionType(t.list("params").map { type(it.asMap()) }, type(t.map("ret")))
        "array" -> ConstArrayType(type(t.map("elem")), t.long("length"))
        "incompleteArray" -> IncompleteArrayType(type(t.map("elem")))
        "variableArray" -> VariableArrayType(type(t.map("elem")))
        else -> error("Unknown type kind '$k'")
    }

    private fun location(m: Map<String, Any?>) = Location(HeaderId(m.string("header")))

    private companion object {
        /** Must match `NativeIndexWriter.FORMAT_VERSION` in the index exporter. */
        const val FORMAT_VERSION = 1
    }
}

private class DecodedNativeIndex(
    override val structs: List<StructDecl>,
    override val enums: List<EnumDef>,
    override val typedefs: List<TypedefDef>,
    override val functions: List<FunctionDecl>,
    override val globals: List<GlobalDecl>,
    override val macroConstants: List<ConstantDef>,
    override val wrappedMacros: List<WrappedMacroDef>,
    override val includedHeaders: List<HeaderId>,
) : NativeIndex() {
    override val objCClasses: List<ObjCClass> get() = emptyList()
    override val objCProtocols: List<ObjCProtocol> get() = emptyList()
    override val objCCategories: List<ObjCCategory> get() = emptyList()
}

private class DecodedStructDecl(
    spelling: String,
    override val isAnonymous: Boolean,
    override val location: Location,
) : StructDecl(spelling) {
    /** Set once every struct exists, since a definition can refer to its own struct. */
    override var def: StructDef? = null
}

private class DecodedStructDef(
    size: Long,
    align: Int,
    override val kind: Kind,
    override val members: List<StructMember>,
    override val methods: List<FunctionDecl>,
    override val staticFields: List<GlobalDecl>,
) : StructDef(size, align)

private class DecodedEnumDef(
    spelling: String,
    baseType: Type,
    override val isAnonymous: Boolean,
    override val location: Location,
    override val constants: List<EnumConstant>,
) : EnumDef(spelling, baseType)

@Suppress("UNCHECKED_CAST")
private fun Any?.asMap() = this as Map<String, Any?>
private fun Map<String, Any?>.map(key: String) = getValue(key).asMap()
private fun Map<String, Any?>.list(key: String) = getValue(key) as List<Any?>
private fun Map<String, Any?>.ids(key: String) = list(key).map { (it as Number).toInt() }
private fun Map<String, Any?>.string(key: String) = getValue(key) as String
private fun Map<String, Any?>.bool(key: String) = getValue(key) as Boolean
private fun Map<String, Any?>.int(key: String) = (getValue(key) as Number).toInt()
private fun Map<String, Any?>.long(key: String) = (getValue(key) as Number).toLong()
