package io.github.lemcoder.library

import io.github.lemcoder.dsl.KonanExtension
import io.github.lemcoder.dsl.NativeLibrarySpec
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/**
 * How a library's archives come to exist. An implementation owns its DSL block, its conventions and its
 * tasks, and must leave one archive per target at [NativeArtifacts.archivePath] under the library's
 * output directory — the only thing consumers know about it.
 */
internal interface LibraryProvider {
    /** Which provider this is; a library selects it with that kind's DSL block. */
    val kind: LibraryProviderKind

    /** Sets this provider's conventions on [library]; called for every library, before its configure block. */
    fun conventions(project: Project, library: NativeLibrarySpec) {}

    /** Registers the work that builds [library]'s archives. Called once the build script has run. */
    fun register(project: Project, konan: KonanExtension, library: NativeLibrarySpec): TaskProvider<*>
}

/** Every provider the plugin knows. A new build system is one more implementation here. */
internal val libraryProviders: List<LibraryProvider> = listOf(KonanClangProvider, ZigProvider)

/** The library providers there are. Each has one implementation in [libraryProviders]. */
internal enum class LibraryProviderKind {
    /** The default: the library's sources, compiled with Kotlin/Native's clang. */
    KONAN_CLANG,

    /** `zig { }`: the project's own `build.zig`. */
    ZIG,
}

/** The provider [library] selected: at most one block, else [LibraryProviderKind.KONAN_CLANG]. */
internal fun providerFor(library: NativeLibrarySpec): LibraryProvider {
    val selected = library.selectedProviders
    check(selected.size <= 1) {
        "konan.libraries.${library.name} selects ${selected.joinToString(" and ")}; a library is built one way."
    }
    val kind = selected.singleOrNull() ?: LibraryProviderKind.KONAN_CLANG
    return libraryProviders.single { it.kind == kind }
}
