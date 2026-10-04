package io.github.lemcoder.index.exporter

import java.io.File
import java.nio.file.Files

/**
 * FFM (`java.lang.foreign`) bindings, written by jextract into `java/<package>/`.
 *
 * jextract parses the headers itself, with its own libclang, so this generator splits the work: cinterop's
 * index decides *what* is bound — every function, struct, union, typedef, constant and global that passed
 * the `.def`'s header filter becomes a jextract `--include-*` filter, so the FFM and JNI bindings cover the
 * same declarations — and jextract decides *how*.
 *
 * Only the user-level `-I`, `-D` and `-U` options reach jextract: it brings its own clang and builtin
 * headers, so the target flags cinterop parsed with do not apply.
 *
 * Options: `library` (required) is the shared library the bindings load with `System.loadLibrary`, so
 * from `java.library.path`; `jextract` is the jextract executable (default: `jextract` on the PATH);
 * `headerClass` names the class holding the functions (default: the package's last segment).
 */
object JextractGenerator : BindingGenerator {
    override val name = "jextract"

    override fun generate(context: GenerationContext, outputDirectory: File) {
        val library = requireNotNull(context.options["library"]) { "The jextract generator needs --option library=<name>" }
        val jextract = context.options["jextract"] ?: "jextract"
        val headerClass = context.options["headerClass"] ?: context.packageName.substringAfterLast('.')

        val scratch = Files.createTempDirectory("jextract").toFile()
        try {
            // jextract takes one header; this one sees exactly what cinterop's translation unit saw.
            val header = scratch.resolve("${headerClass}_bindings.h")
            header.writeText(
                (context.headers.map { "#include <$it>" } + context.preambleLines).joinToString("\n", postfix = "\n")
            )

            val command = buildList {
                add(jextract)
                add("--output"); add(outputDirectory.resolve("java").absolutePath)
                add("--target-package"); add(context.packageName)
                add("--header-class-name"); add(headerClass)
                add("--library"); add(library)
                add("--use-system-load-library")
                addAll(userLevelClangArgs(context.compilerArgs))
                includeFilters(context).forEach { (option, name) -> add(option); add(name) }
                add(header.absolutePath)
            }
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "jextract failed:\n${command.joinToString(" ")}\n$log" }
        } finally {
            scratch.deleteRecursively()
        }
    }

    /**
     * `--include-*` filters for everything cinterop's index holds, as (option, name).
     *
     * The two tools name typedef'd anonymous records differently: cinterop folds
     * `typedef union { … } bits_t` into a record spelled `bits_t`, jextract keeps it a typedef. A record
     * or enum whose spelling has no `struct`/`union`/`enum` keyword is therefore a typedef to jextract.
     */
    private fun includeFilters(context: GenerationContext): List<Pair<String, String>> = buildList {
        val index = context.index
        index.functions.filter { it.parentName == null }.forEach { add("--include-function" to it.name) }
        index.structs.filter { !it.isAnonymous && it.def != null }.forEach { add(tagFilter(it.spelling)) }
        index.enums.filter { !it.isAnonymous }.forEach { enum ->
            if (!enum.spelling.startsWith("enum ")) add("--include-typedef" to enum.spelling)
        }
        index.typedefs.forEach { add("--include-typedef" to it.name) }
        index.macroConstants.forEach { add("--include-constant" to it.name) }
        index.enums.flatMap { it.constants }.forEach { add("--include-constant" to it.name) }
        index.globals.filter { it.parentName == null }.forEach { add("--include-var" to it.name) }
    }

    private fun tagFilter(spelling: String): Pair<String, String> = when {
        spelling.startsWith("struct ") -> "--include-struct" to spelling.removePrefix("struct ")
        spelling.startsWith("union ") -> "--include-union" to spelling.removePrefix("union ")
        else -> "--include-typedef" to spelling
    }

    /** The `-I`, `-D` and `-U` options of [args], in either spelling (`-Ifoo` or `-I foo`). */
    private fun userLevelClangArgs(args: List<String>): List<String> = buildList {
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            val flag = USER_FLAGS.firstOrNull { arg.startsWith(it) }
            when {
                flag == null -> {}
                arg == flag && i + 1 < args.size -> { add(arg + args[i + 1]); i++ }
                else -> add(arg)
            }
            i++
        }
    }

    private val USER_FLAGS = listOf("-I", "-D", "-U")
}
