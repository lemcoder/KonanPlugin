package io.github.lemcoder.index.exporter

import io.github.lemcoder.model.BindingVisibility
import io.github.lemcoder.index.GeneratorRequest
import org.jetbrains.kotlin.native.interop.indexer.*
import java.io.File

/**
 * JNI bindings for the plain JVM and Android: a Kotlin file of `external fun`s named after the C
 * functions, and the C stub that implements them. Writes `kotlin/<package>/<facade>.kt` and
 * `c/<stubLibrary>.c`, where the facade class is the package's last segment.
 *
 * How values cross:
 *  - integers, `char`, `bool`, floating point and enums: the Kotlin primitive of the same size;
 *  - `const char*`: `String?` in both directions, through `GetStringUTFChars` / `NewStringUTF`;
 *  - other pointers to integers or floating point (`int*`, `uint8_t*`, `float*`, …): the matching
 *    primitive array, pinned for the call and written back afterwards unless the pointee is `const`;
 *  - structs by value: a `ByteArray` holding the struct's bytes;
 *  - every other pointer (opaque handles, `void*`, structs, function pointers): its address as a `Long`.
 *
 * Functions it cannot bind (variadic, `long double`, an anonymous struct by value, …) are listed with the
 * reason at the end of the Kotlin file instead.
 *
 * The request names the library `System.loadLibrary` loads — the plugin owns that name — and the
 * bindings' visibility.
 */
object JniGenerator : BindingGenerator<GeneratorRequest.Jni> {
    override fun generate(request: GeneratorRequest.Jni, context: GenerationContext, outputDirectory: File) {
        val stubLibrary = request.stubLibrary
        val visibility = request.visibility
        val packageName = context.packageName
        val facade = packageName.substringAfterLast('.').ifEmpty { "bindings" }

        val bound = mutableListOf<Binding>()
        val skipped = mutableListOf<Pair<String, String>>()
        context.index.functions.sortedBy { it.name }.forEach { function ->
            try {
                bound += bind(function)
            } catch (e: Unsupported) {
                skipped += function.name to e.message.orEmpty()
            }
        }

        val kotlinFile = outputDirectory.resolve("kotlin/${packageName.replace('.', '/')}/$facade.kt")
        kotlinFile.parentFile.mkdirs()
        kotlinFile.writeText(kotlinSource(packageName, facade, stubLibrary, visibility, bound, skipped))

        val cFile = outputDirectory.resolve("c/$stubLibrary.c")
        cFile.parentFile.mkdirs()
        cFile.writeText(cSource(context, jniClassPrefix(packageName, facade), bound))
    }

    private class Unsupported(reason: String) : Exception(reason)

    /** One C function, decided: how each parameter and the result cross. */
    private class Binding(val function: FunctionDecl, val params: List<Pair<String, Crossing>>, val result: Crossing)

    /** How one value crosses the JNI boundary. */
    /** The JVM primitives a C value can cross as, with how Kotlin and JNI spell them. */
    private enum class JvmPrimitive(val kotlinName: String, val jniName: String) {
        BOOLEAN("Boolean", "jboolean"),
        BYTE("Byte", "jbyte"),
        SHORT("Short", "jshort"),
        INT("Int", "jint"),
        LONG("Long", "jlong"),
        FLOAT("Float", "jfloat"),
        DOUBLE("Double", "jdouble"),
        ;

        companion object {
            fun integer(size: Int): JvmPrimitive? = when (size) {
                1 -> BYTE; 2 -> SHORT; 4 -> INT; 8 -> LONG; else -> null
            }

            fun floating(size: Int): JvmPrimitive? = when (size) {
                4 -> FLOAT; 8 -> DOUBLE; else -> null
            }
        }
    }

    private sealed class Crossing(val kotlinType: String, val jniType: String) {
        /** Passed as is; the C compiler converts between the JNI and C types of the same size. */
        class Primitive(val primitive: JvmPrimitive) : Crossing(primitive.kotlinName, primitive.jniName)

        /** A pointer as an address; [cType] is how C spells the pointer, for the cast. */
        class Address(val cType: String) : Crossing(JvmPrimitive.LONG.kotlinName, JvmPrimitive.LONG.jniName)

        /** `const char*` as a `String?`. */
        object CString : Crossing("String?", "jstring")

        /**
         * A pointer to [element] values as a pinned primitive array; [cType] spells the pointer. A [readOnly]
         * one (`const` pointee) is not copied back.
         */
        class PrimitiveArray(val element: JvmPrimitive, val cType: String, val readOnly: Boolean) :
            Crossing("${element.kotlinName}Array?", "${element.jniName}Array")

        /** A struct by value as its bytes; [cType] spells the struct. */
        class StructBytes(val cType: String) :
            Crossing("${JvmPrimitive.BYTE.kotlinName}Array", "${JvmPrimitive.BYTE.jniName}Array")

        object Void : Crossing("Unit", "void")
    }

    private fun bind(function: FunctionDecl): Binding {
        if (function.isVararg) throw Unsupported("variadic")
        if (function.parentName != null) throw Unsupported("C++ member")
        val params = function.parameters.mapIndexed { i, p -> kotlinName(p.name, i) to parameter(p.type) }
        if (params.map { it.first }.toSet().size != params.size) throw Unsupported("duplicate parameter names")
        return Binding(function, params, result(function.returnType))
    }

    private fun parameter(type: Type): Crossing = when (val t = resolved(type)) {
        is PointerType, is ArrayType -> pointer(type, t)
        is RecordType -> Crossing.StructBytes(spelling(type))
        else -> primitive(t)
    }

    private fun result(type: Type): Crossing = when (val t = resolved(type)) {
        VoidType -> Crossing.Void
        is PointerType ->
            if (resolved(t.pointeeType) == CharType && t.pointeeIsConst) Crossing.CString else Crossing.Address(spelling(type))
        is RecordType -> Crossing.StructBytes(spelling(type))
        else -> primitive(t)
    }

    private fun pointer(declared: Type, pointer: Type): Crossing {
        val (pointee, isConst) = when (pointer) {
            is PointerType -> resolved(pointer.pointeeType) to pointer.pointeeIsConst
            is ArrayType -> resolved(pointer.elemType) to false
            else -> error("not a pointer")
        }
        if (pointee == CharType && isConst) return Crossing.CString
        val cType = spelling(declared)
        val element = when (pointee) {
            CharType -> JvmPrimitive.BYTE
            is IntegerType -> JvmPrimitive.integer(pointee.size)
            is FloatingType -> JvmPrimitive.floating(pointee.size)
            else -> null
        }
        return if (element != null) Crossing.PrimitiveArray(element, cType, isConst) else Crossing.Address(cType)
    }

    private fun primitive(type: Type): Crossing.Primitive = when (type) {
        CharType -> Crossing.Primitive(JvmPrimitive.BYTE)
        is BoolType -> Crossing.Primitive(JvmPrimitive.BOOLEAN)
        is IntegerType -> Crossing.Primitive(JvmPrimitive.integer(type.size) ?: throw Unsupported("${type.size * 8}-bit integer"))
        is FloatingType -> Crossing.Primitive(JvmPrimitive.floating(type.size) ?: throw Unsupported(type.spelling))
        is EnumType -> primitive(resolved(type.def.baseType))
        else -> throw Unsupported("type ${type::class.simpleName}")
    }

    /** [type] with typedefs looked through. */
    private fun resolved(type: Type): Type = if (type is Typedef) resolved(type.def.aliased) else type

    /** How C spells [type], for casts and declarations in the stub. */
    private fun spelling(type: Type): String = when (type) {
        is Typedef -> type.def.name
        is RecordType -> if (type.decl.isAnonymous) throw Unsupported("anonymous struct by value") else type.decl.spelling
        is EnumType -> type.def.spelling
        is PointerType -> type.spelling ?: "${if (type.pointeeIsConst) "const " else ""}${spelling(type.pointeeType)}*"
        is ArrayType -> "${spelling(type.elemType)}*"
        is IntegerType -> type.spelling
        is FloatingType -> type.spelling
        CharType -> "char"
        CBoolType -> "_Bool"
        is BoolType -> "bool"
        VoidType -> "void"
        else -> throw Unsupported("type ${type::class.simpleName}")
    }

    private fun kotlinSource(
        packageName: String,
        facade: String,
        stubLibrary: String,
        visibility: BindingVisibility,
        bound: List<Binding>,
        skipped: List<Pair<String, String>>,
    ) = buildString {
        appendLine("""@file:JvmName("$facade")""")
        appendLine("""@file:Suppress("FunctionName", "LocalVariableName", "unused")""")
        if (packageName.isNotEmpty()) appendLine("package $packageName")
        appendLine()
        appendLine("// Generated JNI bindings; do not edit. Strings and primitive buffers are marshalled,")
        appendLine("// structs by value cross as their bytes, and other pointers as addresses (Long).")
        appendLine()
        appendLine("""private val nativeLibrary: Unit = System.loadLibrary("$stubLibrary")""")
        bound.forEach { b ->
            val params = b.params.joinToString { (name, crossing) -> "${kotlinIdentifier(name)}: ${crossing.kotlinType}" }
            appendLine()
            appendLine("/** C: `${cSignature(b.function)}` */")
            // JNI resolves the C symbol from the JVM method name; without @JvmName an internal function
            // could be mangled.
            appendLine("""@JvmName("${b.function.name}")""")
            appendLine("${visibility.name.lowercase()} external fun ${kotlinIdentifier(b.function.name)}($params): ${b.result.kotlinType}")
        }
        if (skipped.isNotEmpty()) {
            appendLine()
            appendLine("// Not bound:")
            skipped.forEach { (name, reason) -> appendLine("// - $name: $reason") }
        }
    }

    private fun cSignature(function: FunctionDecl): String {
        val params = function.parameters.joinToString { p ->
            runCatching { spelling(p.type) }.getOrDefault("?") + (p.name?.let { " $it" } ?: "")
        }
        return "${runCatching { spelling(function.returnType) }.getOrDefault("?")} ${function.name}($params)"
    }

    private fun cSource(context: GenerationContext, jniClassPrefix: String, bound: List<Binding>) = buildString {
        appendLine("// Generated JNI stub; do not edit.")
        appendLine("#include <stdint.h>")
        appendLine("#include <string.h>")
        appendLine("#include <jni.h>")
        context.headers.forEach { appendLine("#include <$it>") }
        context.preambleLines.forEach { appendLine(it) }
        if (bound.any { b -> b.params.any { it.second is Crossing.StructBytes } }) {
            appendLine()
            appendLine("static void kniThrowIllegalArgument(JNIEnv* env, const char* message) {")
            appendLine("    jclass type = (*env)->FindClass(env, \"java/lang/IllegalArgumentException\");")
            appendLine("    if (type) (*env)->ThrowNew(env, type, message);")
            appendLine("}")
        }
        bound.forEach { appendLine(); append(cFunction(it, jniClassPrefix)) }
    }

    private fun cFunction(b: Binding, jniClassPrefix: String) = buildString {
        val name = b.function.name
        val params = b.params.mapIndexed { i, (_, c) -> "${c.jniType} p$i" }
        val returnType = b.result.jniType
        val failure = if (b.result is Crossing.Void) "return;" else "return 0;"
        appendLine("JNIEXPORT $returnType JNICALL ${jniClassPrefix}_${jniMangle(name)}(${(listOf("JNIEnv* env", "jclass cls") + params).joinToString(", ")}) {")

        // Checks first, so a failing one leaves nothing acquired.
        b.params.forEachIndexed { i, (_, c) ->
            if (c is Crossing.StructBytes) {
                appendLine("    if (!p$i || (*env)->GetArrayLength(env, p$i) < (jsize)sizeof(${c.cType})) {")
                appendLine("        kniThrowIllegalArgument(env, \"$name: argument $i must hold sizeof(${c.cType}) bytes\");")
                appendLine("        $failure")
                appendLine("    }")
            }
        }
        val arguments = b.params.mapIndexed { i, (_, c) ->
            when (c) {
                is Crossing.Primitive -> "p$i"
                is Crossing.Address -> "(${c.cType})(intptr_t)p$i"
                Crossing.CString -> {
                    appendLine("    const char* s$i = p$i ? (*env)->GetStringUTFChars(env, p$i, 0) : 0;")
                    "s$i"
                }
                is Crossing.PrimitiveArray -> {
                    appendLine("    ${c.element.jniName}* a$i = p$i ? (*env)->Get${c.element.kotlinName}ArrayElements(env, p$i, 0) : 0;")
                    "(${c.cType})a$i"
                }
                is Crossing.StructBytes -> {
                    appendLine("    jbyte* b$i = (*env)->GetByteArrayElements(env, p$i, 0);")
                    "*(${c.cType}*)b$i"
                }
                Crossing.Void -> error("void parameter")
            }
        }
        val call = "$name(${arguments.joinToString(", ")})"
        when (val r = b.result) {
            Crossing.Void -> appendLine("    $call;")
            is Crossing.Primitive -> appendLine("    $returnType result = ($returnType)$call;")
            is Crossing.Address -> appendLine("    jlong result = (jlong)(intptr_t)$call;")
            Crossing.CString -> {
                appendLine("    const char* r = $call;")
                appendLine("    jstring result = r ? (*env)->NewStringUTF(env, r) : 0;")
            }
            is Crossing.StructBytes -> {
                appendLine("    ${r.cType} r = $call;")
                appendLine("    jbyteArray result = (*env)->NewByteArray(env, (jsize)sizeof r);")
                appendLine("    if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)sizeof r, (const jbyte*)&r);")
            }
            is Crossing.PrimitiveArray -> error("array result")
        }
        // Released in reverse acquisition order; writable arrays with mode 0, so the callee's writes are
        // copied back.
        b.params.indices.reversed().forEach { i ->
            when (val c = b.params[i].second) {
                Crossing.CString -> appendLine("    if (p$i) (*env)->ReleaseStringUTFChars(env, p$i, s$i);")
                is Crossing.PrimitiveArray -> {
                    val mode = if (c.readOnly) "JNI_ABORT" else "0"
                    appendLine("    if (p$i) (*env)->Release${c.element.kotlinName}ArrayElements(env, p$i, a$i, $mode);")
                }
                is Crossing.StructBytes -> appendLine("    (*env)->ReleaseByteArrayElements(env, p$i, b$i, JNI_ABORT);")
                else -> {}
            }
        }
        if (b.result !is Crossing.Void) appendLine("    return result;")
        appendLine("}")
    }

    /** `Java_<mangled class>`: the prefix of every JNI symbol for methods of the facade class. */
    private fun jniClassPrefix(packageName: String, facade: String): String =
        "Java_" + (packageName.split('.').filter { it.isNotEmpty() } + facade).joinToString("_") { jniMangle(it) }

    /** JNI's escaping of one name segment. */
    private fun jniMangle(segment: String): String = buildString {
        segment.forEach { c ->
            when {
                c == '_' -> append("_1")
                c == ';' -> append("_2")
                c == '[' -> append("_3")
                c.code < 128 && c.isLetterOrDigit() -> append(c)
                else -> append("_0%04x".format(c.code))
            }
        }
    }

    private fun kotlinName(cName: String?, index: Int): String =
        cName?.takeIf { it.isNotEmpty() && it.all { c -> c == '_' || c.isLetterOrDigit() } && !it.first().isDigit() } ?: "p$index"

    private fun kotlinIdentifier(name: String) = if (name in KOTLIN_KEYWORDS) "`$name`" else name

    private val KOTLIN_KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is",
        "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof",
        "val", "var", "when", "while",
    )
}
