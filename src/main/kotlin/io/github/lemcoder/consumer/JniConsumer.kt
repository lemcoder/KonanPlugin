package io.github.lemcoder.consumer

import io.github.lemcoder.jni.GenerateJniBindingsTask
import io.github.lemcoder.jni.JniStubInputs
import io.github.lemcoder.jni.stubBuildFor
import io.github.lemcoder.kotlin.KotlinGradlePlugin
import io.github.lemcoder.model.NativeArtifacts
import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.model.taskName
import io.github.lemcoder.util.cleanTask
import io.github.lemcoder.wiring.AgpWiring
import io.github.lemcoder.wiring.GradlePluginId
import io.github.lemcoder.wiring.SourceKind
import io.github.lemcoder.wiring.withPlugin
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

/**
 * JVM and Android targets, through the JNI generator: Kotlin `external fun`s in the main sources, and a
 * JNI library per target from the binding's [io.github.lemcoder.jni.JniStubBuild].
 */
internal class JniConsumer(private val context: BindingContext) : BindingConsumer {
    private val project = context.project
    private val binding = context.binding
    private val bindingName = context.bindingName

    private lateinit var generate: TaskProvider<GenerateJniBindingsTask>
    private lateinit var linkAll: TaskProvider<Task>

    override fun prepare() {
        generate = registerGeneration()
        binding.jni.stubDirectoryProperty.set(generate.flatMap { it.stubSourceDirectory })
        binding.jni.stubLibraryNameProperty.set(generate.flatMap { it.stubLibraryBaseName })
        binding.jni.jniLibsRoot.set(context.buildDirectory.dir("konan/bindings/$bindingName/jni/jniLibs"))
        binding.jni.hostLibRoot.set(context.buildDirectory.dir("konan/bindings/$bindingName/jni/lib"))
        linkAll = project.tasks.register(taskName("linkJni", bindingName)) {
            group = "konan"
            description = "Make the '$bindingName' JNI library exist for every target, however it is built."
        }
        val sources = generate.flatMap { it.kotlinSourceDirectory }

        // Eager: the variant API must be wired at configuration time.
        project.withPlugin(GradlePluginId.KOTLIN_MULTIPLATFORM) { KotlinGradlePlugin.addKmpJvmSources(project, sources) }
        AgpWiring.addSources(project, SourceKind.KOTLIN, sources, generate, linkAll) { !replacedByFfm }
        AgpWiring.addJniLibs(project, binding.jni.jniLibsRoot, linkAll) { !replacedByFfm }
        // AGP looks for a `baselineProfiles` directory beside every source root, including the generated
        // one, by plain path — no task dependency. Matched by name: inert without AGP.
        project.tasks.matching { it.name.contains("ArtProfile") }.configureEach { dependsOn(generate) }
    }

    /**
     * In a `kotlin("jvm")` project or an Android module, `ffm()` replaces JNI: both would define the same
     * facade class. Read once the build script has run.
     */
    private val replacedByFfm get() =
        binding.ffm.isEnabled && (context.hasPlugin(GradlePluginId.KOTLIN_JVM) || context.hasPlugin(GradlePluginId.ANDROID_BASE))

    private val isPlainJvmConsumer get() = context.hasPlugin(GradlePluginId.KOTLIN_JVM) && !replacedByFfm

    override fun isUsed(): Boolean =
        isPlainJvmConsumer ||
            (context.hasPlugin(GradlePluginId.ANDROID_BASE) && !replacedByFfm) || context.hasPlugin(GradlePluginId.ANDROID_KMP_LIBRARY) ||
            (context.hasPlugin(GradlePluginId.KOTLIN_MULTIPLATFORM) && KotlinGradlePlugin.hasKmpJvmTarget(project))

    override fun register() {
        if (isPlainJvmConsumer) KotlinGradlePlugin.addJvmSources(project, generate.flatMap { it.kotlinSourceDirectory })
        val artifacts = context.artifacts()
        val inputs = JniStubInputs(generate, artifacts, targets(artifacts))
        val builds = stubBuildFor(bindingName, binding.jni).register(project, context.konan, binding, inputs)
        linkAll.configure { dependsOn(builds) }
    }

    /** `jni.targets`, else the library's targets a JVM can load — the host and Android — else the host. */
    private fun targets(artifacts: NativeArtifacts?): List<NativeTarget> = binding.jni.targets.getOrElse(emptyList()).ifEmpty {
        artifacts?.targets?.getOrElse(emptyList())?.filter { it == NativeTarget.host || it.isAndroid }
            ?.ifEmpty { null }
            ?: listOf(NativeTarget.host)
    }

    private fun registerGeneration(): TaskProvider<GenerateJniBindingsTask> =
        project.tasks.register(taskName("generateJni", bindingName), GenerateJniBindingsTask::class.java) {
            group = "konan"
            description = "Generate the '$bindingName' JNI bindings and stub from its .def."
            mustRunAfter(project.cleanTask())
            defFile.set(binding.defFile)
            packageName.set(context.packageName)
            includeDirs.from(binding.includeDirs)
            hostTarget.set(NativeTarget.host)
            compilerOptions.set(binding.compilerOptions)
            visibility.set(binding.jni.visibility)
            outputDirectory.set(context.buildDirectory.dir("generated/konan/$bindingName/jni"))
        }
}
