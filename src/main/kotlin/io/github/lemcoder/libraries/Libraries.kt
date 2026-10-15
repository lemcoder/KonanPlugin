package io.github.lemcoder.libraries

import io.github.lemcoder.dsl.KonanExtension
import io.github.lemcoder.dsl.libraries.NativeLibrarySpec
import io.github.lemcoder.model.NativeArtifacts
import org.gradle.api.Action
import org.gradle.api.Project

/**
 * Every library gets its lifecycle task now — the name bindings depend on — and its provider's tasks once
 * the build script has said which provider it uses.
 */
internal fun Project.registerLibraries(konan: KonanExtension) {
    // An object expression, not a lambda: the kotlin-dsl plugin puts a `T.() -> Unit` overload of
    // all/whenObjectAdded in scope, which makes the SAM form ambiguous.
    konan.libraries.all(object : Action<NativeLibrarySpec> {
        override fun execute(library: NativeLibrarySpec) = registerLibrary(konan, library)
    })
}

private fun Project.registerLibrary(konan: KonanExtension, library: NativeLibrarySpec) {
    library.sourceDirectory.convention(layout.projectDirectory.dir("native"))
    library.outputDirectory.convention(layout.buildDirectory.dir("konan/libraries/${library.name}"))

    val lifecycle = tasks.register(NativeArtifacts.taskName(library.name)) {
        group = "konan"
        description = "Build the '${library.name}' library: a static archive per target."
    }
    afterEvaluate {
        val work = registerLibraryBuild(konan, library)
        lifecycle.configure { dependsOn(work) }
    }
}
