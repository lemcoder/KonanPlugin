package io.github.lemcoder.bindings.jni

import io.github.lemcoder.bindings.BindingConsumer
import io.github.lemcoder.bindings.BindingContext
import io.github.lemcoder.bindings.BindingLibraryWiring
import io.github.lemcoder.index.GenerateFromNativeIndexTask
import io.github.lemcoder.index.GeneratorRequest
import io.github.lemcoder.kotlin.KotlinGradlePlugin
import io.github.lemcoder.model.ExternalVariable
import io.github.lemcoder.model.NativeArtifacts
import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.model.stubLibraryBaseName
import io.github.lemcoder.model.taskName
import io.github.lemcoder.util.cleanTask
import io.github.lemcoder.wiring.AgpWiring
import io.github.lemcoder.wiring.GradlePluginId
import io.github.lemcoder.wiring.SourceKind
import io.github.lemcoder.wiring.withPlugin
import org.gradle.api.tasks.TaskProvider

/**
 * JVM and Android targets, through the JNI generator: Kotlin `external fun`s in the main sources, and a
 * JNI library per target — the generated stub linked with the C library, by the plugin or the project's
 * own build.
 */
internal class JniConsumer(private val context: BindingContext) : BindingConsumer {
    private val project = context.project
    private val binding = context.binding
    private val bindingName = context.bindingName
    private val jni = binding.jni

    private lateinit var library: BindingLibraryWiring
    private lateinit var generate: TaskProvider<GenerateFromNativeIndexTask>

    override fun prepare() {
        library = BindingLibraryWiring(context, jni, "jni")
        generate = registerGeneration()
        jni.stubDirectoryProperty.set(generate.flatMap { it.outputDirectory.dir(GeneratorRequest.Jni.C_DIR) })
        jni.stubLibraryNameProperty.set(context.packageName.map(::stubLibraryBaseName))
        val sources = generate.flatMap { it.outputDirectory.dir(GeneratorRequest.Jni.KOTLIN_DIR) }

        // Eager: the variant API must be wired at configuration time.
        project.withPlugin(GradlePluginId.KOTLIN_MULTIPLATFORM) { KotlinGradlePlugin.addKmpJvmSources(project, sources, generate) { !binding.ffm.isEnabled } }
        AgpWiring.addSources(project, SourceKind.KOTLIN, sources, generate, library.linkAll) { !replacedByFfm }
        AgpWiring.addJniLibs(project, jni.jniLibsRoot, library.linkAll) { !replacedByFfm }
        // AGP looks for a `baselineProfiles` directory beside every source root, including the generated
        // one, by plain path — no task dependency. Matched by name: inert without AGP.
        project.tasks.matching { it.name.contains("ArtProfile") }.configureEach { dependsOn(generate) }
    }

    /**
     * `ffm()` replaces JNI wherever JNI would go — JVM, multiplatform and Android alike — since both would
     * define the same facade class. Read once the build script has run.
     */
    private val replacedByFfm get() = binding.ffm.isEnabled

    private val isPlainJvmConsumer get() = context.hasPlugin(GradlePluginId.KOTLIN_JVM) && !replacedByFfm

    private val isKmpJvmConsumer get() =
        context.hasPlugin(GradlePluginId.KOTLIN_MULTIPLATFORM) && KotlinGradlePlugin.hasKmpJvmTarget(project) && !replacedByFfm

    override fun isUsed(): Boolean = !replacedByFfm && (
        isPlainJvmConsumer || isKmpJvmConsumer ||
            context.hasPlugin(GradlePluginId.ANDROID_BASE) || context.hasPlugin(GradlePluginId.ANDROID_KMP_LIBRARY))

    override fun register() {
        if (isPlainJvmConsumer) KotlinGradlePlugin.addJvmSources(project, generate.flatMap { it.outputDirectory.dir(GeneratorRequest.Jni.KOTLIN_DIR) })
        val artifacts = context.artifacts()
        val targets = targets(artifacts)
        library.registerBuild(
            targets,
            jni.stubLibraryName,
            own = { registerKonanLink(project, context.konan, binding, generate, artifacts, targets) },
            extraVariables = {
                mapOf(
                    ExternalVariable.JNI_STUB_DIR to jni.stubDirectory.map { it.asFile.absolutePath },
                    ExternalVariable.JNI_INCLUDE_DIR to generate.flatMap { it.outputDirectory.dir(GeneratorRequest.Jni.INCLUDE_DIR) }
                        .map { it.asFile.absolutePath },
                )
            },
            configure = { generatedSources.from(jni.stubDirectory) },
        )
        library.bundleInJar(targets.filter { !it.isAndroid }, jni.stubLibraryName, isPlainJvmConsumer, isKmpJvmConsumer)
    }

    /** The declared targets, else the library's targets a JVM can load — the host and Android — else the host. */
    private fun targets(artifacts: NativeArtifacts?): List<NativeTarget> = library.declaredTargets().ifEmpty {
        artifacts?.targets?.getOrElse(emptyList())?.filter { it == NativeTarget.host || it.isAndroid }
            ?.ifEmpty { null }
            ?: listOf(NativeTarget.host)
    }

    private fun registerGeneration(): TaskProvider<GenerateFromNativeIndexTask> {
        val packageName = context.packageName
        return project.tasks.register(taskName("generateJni", bindingName), GenerateFromNativeIndexTask::class.java) {
            group = "konan"
            description = "Generate the '$bindingName' JNI bindings and stub from its .def."
            mustRunAfter(project.cleanTask())
            defFile.set(binding.defFile)
            includeDirs.from(binding.includeDirs)
            compilerOptions.set(binding.compilerOptions)
            // The headers are indexed once, for the host; the bridges are the same on every target.
            target.set(NativeTarget.host)
            request.set(packageName.zip(jni.visibility) { name, visibility -> GeneratorRequest.Jni(stubLibraryBaseName(name), visibility) })
            extraCinteropArgs.addAll(packageName.map { listOf("-pkg", it) })
            outputDirectory.set(context.buildDirectory.dir("generated/konan/$bindingName/jni"))
        }
    }
}
