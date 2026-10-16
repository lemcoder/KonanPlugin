package io.github.lemcoder.dsl.bindings

import io.github.lemcoder.dsl.builds.CMakeSettings
import io.github.lemcoder.dsl.builds.ExternalBuild
import io.github.lemcoder.dsl.builds.ExternalBuildOutput
import io.github.lemcoder.dsl.builds.ExternalBuildSettings
import io.github.lemcoder.dsl.builds.ExternalBuildTask
import io.github.lemcoder.dsl.builds.ExternalBuildVariable
import io.github.lemcoder.dsl.builds.PrebuiltSettings
import io.github.lemcoder.dsl.builds.blockName
import io.github.lemcoder.dsl.builds.selectOne
import io.github.lemcoder.kotlin.KotlinGradlePlugin
import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.model.capitalized
import io.github.lemcoder.model.taskName
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

/**
 * What JNI and FFM do alike with the library their bindings load ([spec]): its directories under
 * `build/konan/bindings/<name>/<side>/`, the `link<Side><Name>` task every build of it hangs off, the build
 * the script chose, and its copy in a JVM jar. Created when the binding is added, before its configure block.
 */
internal class BindingLibraryWiring(
    private val context: BindingContext,
    private val spec: BindingLibrarySpec,
    /** `jni` or `ffm`. */
    private val side: String,
) {
    private val project = context.project
    private val bindingName = context.bindingName
    private val owner = "konan.bindings.$bindingName.$side"
    private val label = side.uppercase()
    private val root: Provider<Directory> = context.buildDirectory.dir("konan/bindings/$bindingName/$side")

    /** `link<Side><Name>`: every build of the library, for every target, whichever built it. */
    val linkAll: TaskProvider<Task> = project.tasks.register(taskName("link${side.capitalized()}", bindingName)) {
        group = "konan"
        description = "Make the '$bindingName' $label library exist for every target, however it is built."
    }

    init {
        spec.jniLibsRoot.set(root.map { it.dir("jniLibs") })
        spec.hostLibRoot.set(root.map { it.dir("lib") })
    }

    /** `targets`, else those of `cmake { target(…) { } }` blocks; empty when the build script named none. */
    fun declaredTargets(): List<NativeTarget> = spec.targets.getOrElse(emptyList()).ifEmpty {
        (spec.selectedBuilds.singleOrNull() as? CMakeSettings)?.targetSettings?.keys?.toList().orEmpty()
    }

    /**
     * Registers the build the script chose for [targets] — [own], the plugin's link, when it chose none —
     * under [linkAll], and points `libraryDirectory` at the host's library, else the `jniLibs` root.
     */
    fun registerBuild(
        targets: List<NativeTarget>,
        libraryName: Provider<String>,
        own: () -> List<TaskProvider<*>>,
        prebuilt: () -> List<TaskProvider<*>> = { error("$owner has no prebuilt { } block") },
        extraVariables: (NativeTarget) -> Map<ExternalBuildVariable, Provider<String>> = { emptyMap() },
        configure: ExternalBuildTask.() -> Unit = {},
    ) {
        val builds = when (val settings = selectOne(owner, spec.selectedBuilds) { it.blockName }) {
            null -> own()
            is PrebuiltSettings -> prebuilt()
            is ExternalBuildSettings -> ExternalBuild.of(settings).register(
                project,
                ExternalBuildOutput(
                    owner = owner,
                    name = bindingName,
                    side = side.capitalized(),
                    targets = targets,
                    libraryName = libraryName,
                    libraryFileName = { target -> libraryName.map { target.sharedLibraryName(it) } },
                    outputDirectory = spec::libraryDirectoryFor,
                    workDirectory = root,
                    description = { target, tool -> "Build the '$bindingName' $label library for ${target.konanName} with $tool." },
                    extraVariables = extraVariables,
                    configure = configure,
                ),
            )
        }
        linkAll.configure { dependsOn(builds) }
        val host = NativeTarget.host
        spec.libraryDirectory.convention(if (host in targets) spec.libraryDirectoryFor(host) else spec.jniLibsRoot)
    }

    /**
     * The libraries of [jvmTargets] in the jar of a `kotlin("jvm")` project ([plainJvm]) or a multiplatform
     * `jvm()` target ([kmpJvm]), unless `bundleInJar` is off.
     */
    fun bundleInJar(jvmTargets: List<NativeTarget>, libraryName: Provider<String>, plainJvm: Boolean, kmpJvm: Boolean) {
        if (!spec.bundleInJar.get() || jvmTargets.isEmpty() || !(plainJvm || kmpJvm)) return
        val bundle = project.registerJarBundle(
            taskName = taskName("bundle${side.capitalized()}", bindingName),
            taskDescription = "Put the '$bindingName' $label libraries in the jar, for the bindings to load.",
            libraries = jvmTargets.associateWith { target ->
                spec.libraryDirectoryFor(target).zip(libraryName) { directory, name -> directory.file(target.sharedLibraryName(name)) }
            },
            destination = root.get().dir("resources").asFile,
            builtBy = linkAll,
        )
        val resources = bundle.map { it.destinationDir }
        if (plainJvm) KotlinGradlePlugin.addJvmResources(project, resources)
        if (kmpJvm) KotlinGradlePlugin.addKmpJvmResources(project, resources)
    }
}
