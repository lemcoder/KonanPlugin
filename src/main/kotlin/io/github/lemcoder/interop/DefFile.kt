package io.github.lemcoder.interop

import java.io.File

/**
 * The subset of a cinterop `.def` this plugin reads.
 *
 * The same file drives both interop legs: Kotlin/Native binds it with cinterop, and `jvmInterops`
 * binds it for the JVM. Fields the JVM leg has no meaning for are carried through untouched, and the
 * body after the `---` separator is preserved so a def with inline C still works.
 *
 * @property staticLibraries archives to link into the JNI library. On the native side cinterop embeds
 *   these into the klib; here they decide whether there is anything to link at all — a def without
 *   them yields bindings and a `.c` stub for someone else's build to compile.
 */
data class DefFile(
    val headers: List<String>,
    val headerFilter: String?,
    val packageName: String?,
    val staticLibraries: List<String>,
    val libraryPaths: List<String>,
    val compilerOpts: List<String>,
    val linkerOpts: List<String>,
    val properties: Map<String, String>,
    val body: String?,
) {
    /** Resolves [staticLibraries] against [libraryPaths], relative to [base] for relative paths. */
    fun resolveStaticLibraries(base: File): List<File> = staticLibraries.mapNotNull { name ->
        val roots = libraryPaths.map { p -> File(p).takeIf { it.isAbsolute } ?: base.resolve(p) }
        (roots + base).map { it.resolve(name) }.firstOrNull { it.isFile }
    }

    companion object {
        private val SEPARATOR = Regex("""(?m)^-{3,}\s*$""")

        fun parse(file: File): DefFile = parse(file.readText())

        fun parse(text: String): DefFile {
            val split = SEPARATOR.split(text, limit = 2)
            val properties = LinkedHashMap<String, String>()
            split[0].lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
                .forEach { line ->
                    val key = line.substringBefore('=').trim()
                    if (key.isNotEmpty()) properties[key] = line.substringAfter('=').trim()
                }

            fun tokens(key: String) = properties[key]?.split(' ')?.filter { it.isNotBlank() } ?: emptyList()

            return DefFile(
                headers = tokens("headers"),
                headerFilter = properties["headerFilter"],
                packageName = properties["package"],
                staticLibraries = tokens("staticLibraries"),
                libraryPaths = tokens("libraryPaths"),
                compilerOpts = tokens("compilerOpts"),
                linkerOpts = tokens("linkerOpts"),
                properties = properties,
                body = split.getOrNull(1)?.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * Renders this def back out, overriding `package`. The JVM stub generator needs a package, and the
     * native leg's def may legitimately omit it or use a different one.
     */
    fun render(packageOverride: String): String = buildString {
        properties.forEach { (key, value) -> if (key != "package") appendLine("$key = $value") }
        appendLine("package = $packageOverride")
        body?.let { appendLine("---"); append(it) }
    }
}
