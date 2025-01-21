package io.github.lemcoder.commandBuilder

import io.github.lemcoder.COMPILE_DIR
import io.github.lemcoder.HEADERS_DIR
import io.github.lemcoder.LibraryType
import io.github.lemcoder.SOURCES_DIR
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File

typealias Command = () -> String

open class CommandBuilder(private val workingDir: File) {

    private var target: KonanTarget? = null
    private var libraryName: String? = null
    private var libraryType: LibraryType? = null

    protected val sourcesDir: File = workingDir.resolve(SOURCES_DIR)
    protected val headersDir: File = workingDir.resolve(HEADERS_DIR)
    protected val compileDir: File = workingDir.resolve(COMPILE_DIR)

    fun setTarget(target: KonanTarget) {
        this.target = target
    }

    fun setLibraryName(libFileName: String) {
        this.libraryName = libFileName
    }

    fun setLibraryType(libraryType: LibraryType) {
        this.libraryType = libraryType
    }

    open fun build(): List<Command> {
        requireNotNull(target) { "Target must be set" }
        requireNotNull(libraryName) { "Library name must be set" }
        requireNotNull(libraryType) { "Library type must be set" }

        val builder = when (libraryType!!) {
            LibraryType.SHARED -> SharedLibraryCommandBuilder(target!!, workingDir, libraryName!!)
            LibraryType.STATIC -> StaticLibraryCommandBuilder(target!!, workingDir, libraryName!!)
        }

        return builder.build()
    }
}