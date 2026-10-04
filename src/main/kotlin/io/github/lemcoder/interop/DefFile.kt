package io.github.lemcoder.interop

import java.io.File

/**
 * The few facts of a cinterop `.def` the plugin needs on its own side; everything else in it is read by
 * cinterop itself, in the index exporter.
 *
 * @property packageName the bindings' package when the interop does not override it.
 * @property staticLibraries archives to link into the JNI library. On the native side cinterop embeds
 *   these into the klib; here they decide whether there is anything to link at all — a def without
 *   them yields bindings and a `.c` stub for someone else's build to compile.
 */
data class DefFile(
    val packageName: String?,
    val staticLibraries: List<String>,
    val libraryPaths: List<String>,
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
            val properties = SEPARATOR.split(text, limit = 2)[0].lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
                .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

            fun tokens(key: String) = properties[key]?.split(' ')?.filter { it.isNotBlank() } ?: emptyList()

            return DefFile(
                packageName = properties["package"],
                staticLibraries = tokens("staticLibraries"),
                libraryPaths = tokens("libraryPaths"),
            )
        }
    }
}
