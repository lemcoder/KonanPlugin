package io.github.lemcoder.kotlin

import io.github.lemcoder.dsl.KonanExtension
import io.github.lemcoder.model.Architecture
import io.github.lemcoder.model.HostOs
import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.model.TargetFamily
import io.github.lemcoder.model.ToolchainFiles
import io.github.lemcoder.model.taskName
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.File
import javax.inject.Inject

/**
 * Kotlin/Native's toolchain for one target, as files an external build can be handed: `cc` and `c++`
 * wrappers around the clang `run_konan` would run, llvm-ar, and a CMake toolchain file naming them.
 */
internal class KonanToolchain(val task: TaskProvider<KonanToolchainTask>, directory: Provider<Directory>) {
    val cc: Provider<File> = directory.map { it.file("bin/${script("cc")}").asFile }
    val cxx: Provider<File> = directory.map { it.file("bin/${script("c++")}").asFile }
    val ar: Provider<File> = directory.map { it.file("bin/${script("ar")}").asFile }
    val cmakeToolchainFile: Provider<File> = directory.map { it.file("toolchain.cmake").asFile }

    val files: ToolchainFiles = ToolchainFiles(cc, cxx, ar, cmakeToolchainFile)

    companion object {
        /** [target]'s toolchain, whose task is registered by the first build that asks for it. */
        fun of(project: Project, target: NativeTarget): KonanToolchain {
            val name = taskName("konanToolchain", "", target)
            val directory = project.layout.buildDirectory.dir("konan/toolchains/${target.konanName}")
            val task = if (name in project.tasks.names) {
                project.tasks.named(name, KonanToolchainTask::class.java)
            } else {
                val konan = project.extensions.getByType(KonanExtension::class.java)
                project.tasks.register(name, KonanToolchainTask::class.java) {
                    group = "konan"
                    description = "Write Kotlin/Native's compilers for ${target.konanName} as files external builds can use."
                    konanHome.set(konan.konanHome)
                    // An input so another distribution regenerates the wrappers; resolved again when the task runs.
                    distribution.set(konan.konanHome.map { it.asFile.absolutePath }.orElse(project.provider { KonanDistribution.findHome()?.absolutePath ?: "" }))
                    this.target.set(target)
                    outputDirectory.set(directory)
                }
            }
            return KonanToolchain(task, directory)
        }

        private fun script(name: String) = if (HostOs.current == HostOs.WINDOWS) "$name.bat" else name
    }
}

/** Writes [KonanToolchain]'s files for [target]. */
@DisableCachingByDefault(because = "The files name absolute paths into the local Kotlin/Native distribution")
internal abstract class KonanToolchainTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Internal abstract val konanHome: DirectoryProperty
    @get:Input abstract val distribution: Property<String>
    @get:Input abstract val target: Property<NativeTarget>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun write() {
        val konan = KonanDistribution.resolve(konanHome.orNull?.asFile)
        val target = target.get()
        konan.ensureDependencies(target, exec, temporaryDir.resolve("dependencies"))
        val cc = konan.clangCommand(target, ClangDriver.C, exec)
        val cxx = konan.clangCommand(target, ClangDriver.CXX, exec)
        val ar = File(cc.first()).resolveSibling("llvm-ar").absolutePath
        val link = konan.linkArguments(target, exec)

        val directory = outputDirectory.get().asFile.apply { deleteRecursively() }
        val bin = directory.resolve("bin").apply { mkdirs() }
        val windows = HostOs.current == HostOs.WINDOWS
        fun script(name: String, text: String) = bin.resolve(if (windows) "$name.bat" else name).apply {
            writeText(text)
            setExecutable(true)
        }
        val ccFile = script("cc", driver(target, cc, link, windows))
        val cxxFile = script("c++", driver(target, cxx, link, windows))
        val arFile = script("ar", if (windows) "@\"$ar\" %*\r\n" else "#!/bin/sh\nexec ${quote(ar)} \"$@\"\n")
        directory.resolve("toolchain.cmake").writeText(cmakeToolchain(target, cc, ccFile, cxxFile, arFile))
    }

    /**
     * A compiler driver: [command] plus the caller's arguments, plus [link] when linking — a compile-only
     * call (`-c`, `-S`, `-E`, `-M…`, `--version`) gets none, so no unused-argument warning meets `-Werror`.
     */
    private fun driver(target: NativeTarget, command: List<String>, link: List<String>, windows: Boolean): String =
        if (windows) {
            "@REM Generated by the Konan plugin: Kotlin/Native's ${File(command.first()).name} for ${target.konanName}.\r\n" +
                "@${command.joinToString(" ") { "\"$it\"" }} %* ${link.joinToString(" ") { "\"$it\"" }}\r\n"
        } else {
            """
            |#!/bin/sh
            |# Generated by the Konan plugin: Kotlin/Native's ${File(command.first()).name} for ${target.konanName}.
            |for arg in "$@"; do
            |  case "${'$'}arg" in
            |    -c|-S|-E|-M|-MM|--version|-v|-dumpversion|-dumpmachine|-print-*) exec ${command.joinToString(" ", transform = ::quote)} "$@" ;;
            |  esac
            |done
            |exec ${command.joinToString(" ", transform = ::quote)} "$@" ${link.joinToString(" ", transform = ::quote)}
            |""".trimMargin()
        }

    /**
     * A CMake toolchain file for [target]. Android is described as Linux: CMake's own Android platform
     * looks for an NDK, and the compilers already target Android. Apple targets name the SDK clang uses,
     * or CMake would add the host's after it.
     */
    private fun cmakeToolchain(target: NativeTarget, cc: List<String>, ccFile: File, cxxFile: File, arFile: File): String {
        val system = when (target.family) {
            TargetFamily.LINUX, TargetFamily.ANDROID -> "Linux"
            TargetFamily.MINGW -> "Windows"
            TargetFamily.OSX, TargetFamily.IOS, TargetFamily.TVOS, TargetFamily.WATCHOS -> "Darwin"
        }
        val processor = when (target.architecture) {
            Architecture.ARM64 -> if (target.family.isApple) "arm64" else "aarch64"
            Architecture.ARM32 -> "arm"
            Architecture.X64 -> "x86_64"
            Architecture.X86 -> "i686"
        }
        val sysroot = cc.indexOf("-isysroot").takeIf { it >= 0 }?.let { cc[it + 1] }
        fun path(file: File) = file.absolutePath.replace('\\', '/')
        return buildString {
            appendLine("# Generated by the Konan plugin: Kotlin/Native's toolchain for ${target.konanName}.")
            appendLine("set(CMAKE_SYSTEM_NAME $system)")
            appendLine("set(CMAKE_SYSTEM_PROCESSOR $processor)")
            appendLine("set(CMAKE_C_COMPILER \"${path(ccFile)}\")")
            appendLine("set(CMAKE_CXX_COMPILER \"${path(cxxFile)}\")")
            appendLine("set(CMAKE_AR \"${path(arFile)}\" CACHE FILEPATH \"Archiver\")")
            sysroot?.let { appendLine("set(CMAKE_OSX_SYSROOT \"$it\" CACHE PATH \"SDK\")") }
            // A cross toolchain may not link executables for the build machine to run; libraries are what we build.
            appendLine("set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)")
        }
    }

    private fun quote(argument: String) = "'" + argument.replace("'", "'\\''") + "'"
}
