package io.github.lemcoder.util

/**
 * Marshalling for the generated JNI bridges.
 *
 * cinterop's JVM flavor passes every pointer as a raw address, which assumes a caller that can
 * allocate native memory — true for Kotlin/Native, not for plain JVM or Android. The transforms here
 * recover the information cinterop already knows (it is visible in the generated wrappers) and
 * rewrite both sides of the bridge so that:
 *
 *  - `const char*` inputs take a `String?` and are marshalled with `GetStringUTFChars`,
 *  - pointers to primitives (`char*`, `float*`, …) take the matching Kotlin array and are marshalled
 *    with `Get<Type>ArrayElements` / `Release<Type>ArrayElements` (mode 0, so writes are copied back),
 *  - struct-by-value arguments and struct returns take a `ByteArray?` holding the raw struct bytes.
 *
 * Everything else — opaque handles, pointers to structs, primitives — is left as a raw address, which
 * is what a handle-style C API wants anyway.
 */

/** How one bridge parameter crosses the JNI boundary. */
enum class ParamKind(
    /** Kotlin type of the parameter, or `null` to keep the address-typed original. */
    val kotlinType: String?,
    /** JNI type of the parameter, or `null` to keep `jlong`. */
    val jniType: String?,
    /** C type of the local the body sees. */
    val cType: String?,
    /** `Get<Type>ArrayElements` infix, for array kinds. */
    val arrayAccessor: String?,
) {
    RAW(null, null, null, null),
    STRING("String?", "jstring", "const char*", null),
    BYTE_ARRAY("ByteArray?", "jbyteArray", "jbyte*", "Byte"),
    SHORT_ARRAY("ShortArray?", "jshortArray", "jshort*", "Short"),
    INT_ARRAY("IntArray?", "jintArray", "jint*", "Int"),
    LONG_ARRAY("LongArray?", "jlongArray", "jlong*", "Long"),
    FLOAT_ARRAY("FloatArray?", "jfloatArray", "jfloat*", "Float"),
    DOUBLE_ARRAY("DoubleArray?", "jdoubleArray", "jdouble*", "Double"),
    ;

    val isArray: Boolean get() = arrayAccessor != null
}

/** The name of the generated helper that reads a NUL-terminated C string at a raw address. */
const val C_STRING_HELPER = "kniCString"

private val FUN_HEADER = Regex("""(?m)^fun\s+(\w+)\s*\(([^)]*)\)\s*:""")
private val CSTR_ARG = Regex("""^(\w+)\??\.cstr\??\.getPointer\(memScope\)\.rawValue$""")
private val PTR_ARG = Regex("""^(\w+)\??\.getPointer\(memScope\)\.rawValue$""")
private val STRUCT_RETURN_ARG = Regex("""^kniRetVal\.rawPtr$""")
private val VAR_ELEMENT = Regex("""^CValuesRef<(\w+)Var>\??$""")
private val STRUCT_VALUE = Regex("""^CValue<\w+>$""")

/**
 * Derives, from the *unstripped* cinterop output, how each bridge parameter should be marshalled.
 * The wrapper bodies spell the mapping out — `path?.cstr?.getPointer(memScope).rawValue` is a string,
 * `out_buf?.getPointer(memScope).rawValue` with `out_buf: CValuesRef<FloatVar>?` is a float buffer —
 * so the classification reads them rather than re-parsing the C headers.
 *
 * @return bridge index -> parameter kinds, positional. Bridges with nothing to marshal are omitted.
 */
fun parseBridgeKinds(rawKotlin: String): Map<Int, List<ParamKind>> {
    val headers = FUN_HEADER.findAll(rawKotlin).toList()
    val result = mutableMapOf<Int, List<ParamKind>>()

    var searchFrom = 0
    while (true) {
        val callStart = rawKotlin.indexOf("kniBridge", searchFrom).takeIf { it >= 0 } ?: break
        val open = rawKotlin.indexOf('(', callStart)
        val bridgeIndex = rawKotlin.substring(callStart + "kniBridge".length, open).toIntOrNull()
        if (open < 0 || bridgeIndex == null) { searchFrom = callStart + 1; continue }
        val close = matchingParen(rawKotlin, open)
        if (close < 0) { searchFrom = callStart + 1; continue }
        searchFrom = close + 1

        // The declarations at the bottom of the file are calls to nothing; only wrapper bodies count.
        val header = headers.lastOrNull { it.range.first < callStart } ?: continue
        if (rawKotlin.startsWith("private external fun ", rawKotlin.lastIndexOf('\n', callStart) + 1)) continue

        val paramTypes = splitTopLevel(header.groupValues[2])
            .mapNotNull { p -> p.substringBefore(':').trim().takeIf { it.isNotEmpty() }?.to(p.substringAfter(':').trim()) }
            .toMap()

        val kinds = splitTopLevel(rawKotlin.substring(open + 1, close)).map { arg -> classify(arg.trim(), paramTypes) }
        if (kinds.any { it != ParamKind.RAW }) result[bridgeIndex] = kinds
    }
    return result
}

private fun classify(arg: String, paramTypes: Map<String, String>): ParamKind {
    CSTR_ARG.find(arg)?.let { return ParamKind.STRING }
    // A struct return is written through an out pointer the wrapper allocates for the caller.
    if (STRUCT_RETURN_ARG.matches(arg)) return ParamKind.BYTE_ARRAY
    val pointee = PTR_ARG.find(arg)?.groupValues?.get(1) ?: return ParamKind.RAW
    val type = paramTypes[pointee] ?: return ParamKind.RAW
    if (STRUCT_VALUE.matches(type)) return ParamKind.BYTE_ARRAY
    val element = VAR_ELEMENT.find(type)?.groupValues?.get(1) ?: return ParamKind.RAW
    return when (element) {
        "Byte" -> ParamKind.BYTE_ARRAY
        "Short" -> ParamKind.SHORT_ARRAY
        "Int" -> ParamKind.INT_ARRAY
        "Long" -> ParamKind.LONG_ARRAY
        "Float" -> ParamKind.FLOAT_ARRAY
        "Double" -> ParamKind.DOUBLE_ARRAY
        // Pointers to opaque or struct types stay raw addresses; there is no JVM shape for them.
        else -> ParamKind.RAW
    }
}

private val C_FUNCTION = Regex(
    """(?s)JNIEXPORT\s+(\w+)\s+JNICALL\s+(Java_\w+_kniBridge(\d+))\s*\(([^)]*)\)\s*\{(.*?)\n\}"""
)
private val C_RETURN = Regex("""(?s)^(.*?)\breturn\s+(.*?);\s*$""")

/**
 * Rewrites the generated JNI `.c` so the marshalled parameters arrive as `jstring` / `j<type>Array`,
 * and appends the [C_STRING_HELPER] used to read `const char*` returns.
 *
 * The incoming object is renamed to `j<n>` and the marshalled pointer keeps the original `p<n>` name,
 * so the generated call expression — casts and all — is reused verbatim.
 */
fun marshalStub(cSource: String, kinds: Map<Int, List<ParamKind>>): String {
    val rewritten = C_FUNCTION.replace(cSource) { m ->
        val (returnType, jniName, indexText, paramText, body) = m.destructured
        val bridgeKinds = kinds[indexText.toInt()] ?: return@replace m.value

        val params = splitTopLevel(paramText).map { it.trim() }
        val fixed = params.take(2) // JNIEnv*, jclass
        val bridgeParams = params.drop(2)

        // Only the uniform `return expr;` / no-return shapes are safe to wrap; anything else keeps the
        // address-typed signature rather than risking a use-after-release.
        val returns = Regex("""\breturn\b""").findAll(body).count()
        if (returns > 1 || bridgeParams.size != bridgeKinds.size) return@replace m.value

        val declarations = bridgeParams.mapIndexed { i, p ->
            val kind = bridgeKinds[i]
            if (kind == ParamKind.RAW) p else "${kind.jniType} j$i"
        }
        val prologue = bridgeParams.indices.mapNotNull { i ->
            val kind = bridgeKinds[i]
            when {
                kind == ParamKind.STRING ->
                    "    ${kind.cType} p$i = j$i ? (*jniEnv)->GetStringUTFChars(jniEnv, j$i, 0) : 0;"
                kind.isArray ->
                    "    ${kind.cType} p$i = j$i ? (*jniEnv)->Get${kind.arrayAccessor}ArrayElements(jniEnv, j$i, 0) : 0;"
                else -> null
            }
        }
        // Arrays are released first: they are acquired last, and a released string must outlive nothing.
        val epilogue = bridgeParams.indices.reversed().mapNotNull { i ->
            val kind = bridgeKinds[i]
            when {
                kind == ParamKind.STRING ->
                    "    if (j$i) (*jniEnv)->ReleaseStringUTFChars(jniEnv, j$i, p$i);"
                kind.isArray ->
                    "    if (j$i) (*jniEnv)->Release${kind.arrayAccessor}ArrayElements(jniEnv, j$i, p$i, 0);"
                else -> null
            }
        }

        val (callBody, tail) = C_RETURN.find(body)?.destructured?.let { (before, expression) ->
            before.trimEnd('\n') to listOf("    $returnType kniResult = $expression;")
        } ?: (body to emptyList())

        buildString {
            append("JNIEXPORT $returnType JNICALL $jniName (")
            append((fixed + declarations).joinToString(", "))
            appendLine(") {")
            prologue.forEach { appendLine(it) }
            if (callBody.isNotBlank()) appendLine(callBody.trimEnd())
            tail.forEach { appendLine(it) }
            epilogue.forEach { appendLine(it) }
            if (tail.isNotEmpty()) appendLine("    return kniResult;")
            append("}")
        }
    }

    val jniPrefix = C_FUNCTION.find(cSource)?.groupValues?.get(2)?.substringBeforeLast("_kniBridge")
        ?: return rewritten
    return buildString {
        appendLine(rewritten.trimEnd())
        appendLine()
        appendLine("// Reads a NUL-terminated C string at a raw address, for functions returning const char*.")
        appendLine("JNIEXPORT jstring JNICALL ${jniPrefix}_$C_STRING_HELPER (JNIEnv* jniEnv, jclass jclss, jlong p0) {")
        appendLine("    return p0 ? (*jniEnv)->NewStringUTF(jniEnv, (const char*)p0) : 0;")
        appendLine("}")
    }
}

/** Index of the `)` matching the `(` at [open], or -1. */
private fun matchingParen(text: String, open: Int): Int {
    var depth = 0
    for (i in open until text.length) {
        when (text[i]) {
            '(' -> depth++
            ')' -> if (--depth == 0) return i
        }
    }
    return -1
}

/** Splits on commas that are not nested in brackets or parentheses. */
internal fun splitTopLevel(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val parts = mutableListOf<String>()
    var depth = 0
    var start = 0
    text.forEachIndexed { i, c ->
        when (c) {
            '(', '<', '[' -> depth++
            ')', '>', ']' -> depth--
            ',' -> if (depth == 0) { parts += text.substring(start, i); start = i + 1 }
        }
    }
    parts += text.substring(start)
    return parts.map { it.trim() }.filter { it.isNotEmpty() }
}
