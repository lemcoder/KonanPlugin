package io.github.lemcoder.consumer

import io.github.lemcoder.cleanTask
import io.github.lemcoder.dsl.BindingVisibility
import io.github.lemcoder.hostKonanTarget
import io.github.lemcoder.index.GeneratedVisibility
import io.github.lemcoder.isAndroid
import io.github.lemcoder.jni.GenerateJniBindingsTask
import io.github.lemcoder.jni.JniStubInputs
import io.github.lemcoder.jni.stubBuildFor
import io.github.lemcoder.library.NativeArtifacts
import io.github.lemcoder.wiring.AgpWiring
import io.github.lemcoder.wiring.GradlePluginId
import io.github.lemcoder.wiring.KgpWiring
import io.github.lemcoder.wiring.withPlugin
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.konan.target.KonanTarget

/**
 * JVM and Android targets, through the JNI generator: Kotlin `external fun`s in the main sources, and a
 * JNI library per target from the binding's [io.github.lemcoder.jni.JniStubBuild].
 */
internal class JniConsumer(private val context: BindingContext) : BindingConsumer {
    private val project = context.project
    private val binding = context.binding
    private val bindingName = context.bindingName
    private val suffix = context.suffix

    private lateinit var generate: TaskProvider<GenerateJniBindingsTask>
    private lateinit var linkAll: TaskProvider<Task>

    override fun prepare() {
        binding.jni.visibility.convention(BindingVisibility.INTERNAL)
        generate = registerGeneration()
        binding.jni.stubDirectoryProperty.set(generate.flatMap { it.stubSourceDirectory })
        binding.jni.stubLibraryNameProperty.set(generate.flatMap { it.stubLibraryBaseName })
        binding.jni.jniLibsRoot.set(context.buildDirectory.dir("konan/bindings/$bindingName/jni/jniLibs"))
        binding.jni.hostLibRoot.set(context.buildDirectory.dir("konan/bindings/$bindingName/jni/lib"))
        linkAll = project.tasks.register("linkJni$suffix") {
            group = "konan"
            description = "Make the '$bindingName' JNI library exist for every target, however it is built."
        }
        val sources = generate.flatMap { it.kotlinSourceDirectory }

        // Eager: the variant API must be wired at configuration time.
        project.withPlugin(GradlePluginId.KOTLIN_MULTIPLATFORM) { KgpWiring.addKmpJvmSources(project, sources) }
        AgpWiring.addKotlinSources(project, sources, generate, linkAll)
        AgpWiring.addJniLibs(project, binding.jni.jniLibsRoot, linkAll)
        // AGP looks for a `baselineProfiles` directory beside every source root, including the generated
        // one, by plain path — no task dependency. Matched by name: inert without AGP.
        project.tasks.matching { it.name.contains("ArtProfile") }.configureEach { dependsOn(generate) }
    }

    /** In a `kotlin("jvm")` project FFM replaces JNI: both would define the same facade class. */
    private val isPlainJvmConsumer get() = context.hasPlugin(GradlePluginId.KOTLIN_JVM) && !binding.ffm.isEnabled

    override fun isUsed(): Boolean =
        isPlainJvmConsumer ||
            context.hasPlugin(GradlePluginId.ANDROID_BASE) || context.hasPlugin(GradlePluginId.ANDROID_KMP_LIBRARY) ||
            (context.hasPlugin(GradlePluginId.KOTLIN_MULTIPLATFORM) && KgpWiring.hasKmpJvmTarget(project))

    override fun register() {
        if (isPlainJvmConsumer) KgpWiring.addJvmSources(project, generate.flatMap { it.kotlinSourceDirectory })
        val artifacts = context.artifacts()
        val inputs = JniStubInputs(generate, artifacts, targets(artifacts))
        val builds = stubBuildFor(bindingName, binding.jni).register(project, context.konan, binding, inputs)
        linkAll.configure { dependsOn(builds) }
    }

    /** `jni.targets`, else the library's targets a JVM can load — the host and Android — else the host. */
    private fun targets(artifacts: NativeArtifacts?): List<KonanTarget> = binding.jni.targets.getOrElse(emptyList()).ifEmpty {
        artifacts?.targets?.getOrElse(emptyList())?.filter { it == hostKonanTarget() || it.isAndroid }
            ?.ifEmpty { null }
            ?: listOf(hostKonanTarget())
    }

    private fun registerGeneration(): TaskProvider<GenerateJniBindingsTask> =
        project.tasks.register("generateJni$suffix", GenerateJniBindingsTask::class.java) {
            group = "konan"
            description = "Generate the '$bindingName' JNI bindings and stub from its .def."
            mustRunAfter(project.cleanTask())
            defFile.set(binding.defFile)
            packageName.set(context.packageName)
            includeDirs.from(binding.includeDirs)
            hostTarget.set(hostKonanTarget())
            compilerOptions.set(binding.compilerOptions)
            visibility.set(
                binding.jni.visibility.map {
                    when (it) {
                        BindingVisibility.INTERNAL -> GeneratedVisibility.INTERNAL
                        BindingVisibility.PUBLIC -> GeneratedVisibility.PUBLIC
                    }
                }
            )
            outputDirectory.set(context.buildDirectory.dir("generated/konan/$bindingName/jni"))
        }
}
