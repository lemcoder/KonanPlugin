package io.github.lemcoder.bindings.ffm

import io.github.lemcoder.bindings.BindingConsumer
import io.github.lemcoder.bindings.BindingContext
import io.github.lemcoder.bindings.BindingLibraryWiring
import io.github.lemcoder.index.FfmApi
import io.github.lemcoder.index.GenerateFromNativeIndexTask
import io.github.lemcoder.index.GeneratorRequest
import io.github.lemcoder.kotlin.KotlinGradlePlugin
import io.github.lemcoder.model.Architecture
import io.github.lemcoder.model.ConfigField
import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.model.taskName
import io.github.lemcoder.wiring.AgpWiring
import io.github.lemcoder.wiring.GradlePluginId
import io.github.lemcoder.wiring.SourceKind
import io.github.lemcoder.wiring.withPlugin
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

/**
 * FFM bindings: jextract over the same `.def`, and the C library itself as a shared library — FFM loads it
 * directly, with no stub — linked by the plugin or built as the binding's `ffm` says. Opted into with
 * `ffm()`, which then replaces JNI wherever JNI would go.
 *
 * Generated once per API the project needs: the JDK's `java.lang.foreign` for `kotlin("jvm")` and
 * multiplatform `jvm()` targets, the library provided for the host and bundled in the jar; PanamaPort's
 * backport for Android modules and multiplatform Android targets, added as a dependency, the library
 * provided for each 64-bit ABI under `jniLibs/`.
 */
internal class FfmConsumer(private val context: BindingContext) : BindingConsumer {
    private val project = context.project
    private val binding = context.binding
    private val bindingName = context.bindingName

    private lateinit var library: BindingLibraryWiring

    private val isMultiplatform get() = context.hasPlugin(GradlePluginId.KOTLIN_MULTIPLATFORM)

    /** `kotlin("jvm")`, or a multiplatform `jvm()` target: the JDK's FFM. */
    private val servesJvm get() =
        context.hasPlugin(GradlePluginId.KOTLIN_JVM) || (isMultiplatform && KotlinGradlePlugin.hasKmpPlainJvmTarget(project))

    /** An Android module, or a multiplatform Android target: PanamaPort. */
    private val servesAndroid get() =
        (context.hasPlugin(GradlePluginId.ANDROID_BASE) && (!isMultiplatform || KotlinGradlePlugin.hasKmpAndroidTarget(project))) ||
            context.hasPlugin(GradlePluginId.ANDROID_KMP_LIBRARY)

    private fun generatedRoot(api: FfmApi): Provider<Directory> = context.buildDirectory.dir(
        when (api) {
            FfmApi.JAVA -> "generated/konan/$bindingName/ffm"
            FfmApi.PANAMA_PORT -> "generated/konan/$bindingName/ffm-android"
        }
    )

    override fun prepare() {
        val ffm = binding.ffm
        ffm.libraryName.convention(binding.library.map { it.name }.orElse(bindingName))
        library = BindingLibraryWiring(context, ffm, "ffm")
        val linkAll = library.linkAll
        // Eager, as the variant API needs; whether FFM is on is only known in the callbacks. linkFfm<Name>
        // depends on generation, so it orders both the compile and the packaging.
        val enabled = { ffm.isEnabled }
        AgpWiring.addSources(project, SourceKind.JAVA, generatedRoot(FfmApi.PANAMA_PORT).map { it.dir("java") }, linkAll, linkAll, enabled)
        AgpWiring.addJniLibs(project, ffm.jniLibsRoot, linkAll, enabled)
        project.withPlugin(GradlePluginId.KOTLIN_MULTIPLATFORM) { KotlinGradlePlugin.dependOnForKmpAndroid(project, linkAll, enabled) }
    }

    override fun isUsed(): Boolean = binding.ffm.isEnabled

    override fun register() {
        val ffm = binding.ffm
        val artifacts = context.artifacts()
        val libraryName = ffm.libraryName.get()
        val jvm = servesJvm
        val android = servesAndroid
        if (!jvm && !android) {
            project.logger.info(
                "konan.bindings.$bindingName: FFM bindings are wired into kotlin(\"jvm\"), multiplatform and Android projects; " +
                    "generate them with generateFfm$bindingName and add them yourself."
            )
        }
        // The JDK's bindings also when nothing is detected, for a build to wire itself.
        val generateJvm = if (jvm || !android) registerGeneration(FfmApi.JAVA, libraryName) else null
        if (android) registerGeneration(FfmApi.PANAMA_PORT, libraryName)

        val loadable = buildList {
            if (jvm || !android) add(NativeTarget.host)
            if (android) addAll(NativeTarget.android.filter { it.architecture in LP64 })
        }
        val targets = targets(artifacts?.targets?.getOrElse(emptyList()).orEmpty(), loadable, warnAboutAndroid = android)
        library.registerBuild(
            targets,
            ffm.libraryName,
            own = { registerFfmLink(project, context.konan, binding, artifacts, libraryName, targets) },
            prebuilt = { registerFfmPrebuilt(project, binding, libraryName, targets) },
        )
        if (android) {
            val panamaPort = ffm.panamaPortVersion.map { "$PANAMA_PORT:$it" }
            if (isMultiplatform) KotlinGradlePlugin.addKmpAndroidDependency(project, panamaPort)
            else project.dependencies.add("implementation", panamaPort)
        }
        if (jvm && generateJvm != null) wireJvm(generateJvm, targets.filter { !it.isAndroid })
    }

    private fun registerGeneration(api: FfmApi, libraryName: String): TaskProvider<GenerateFromNativeIndexTask> {
        val ffm = binding.ffm
        val packageName = context.packageName
        val config = context.konan.config
        val task = project.tasks.register(
            taskName(if (api == FfmApi.JAVA) "generateFfm" else "generateFfmAndroid", bindingName),
            GenerateFromNativeIndexTask::class.java,
        ) {
            group = "konan"
            description = "Generate the '$bindingName' FFM bindings with jextract" + (if (api == FfmApi.PANAMA_PORT) ", for PanamaPort." else ".")
            defFile.set(binding.defFile)
            includeDirs.from(binding.includeDirs)
            compilerOptions.set(binding.compilerOptions)
            // One index, for the host: its type layouts are those of every LP64 target, which is why Android
            // gets only its 64-bit ABIs.
            target.set(NativeTarget.host)
            request.set(GeneratorRequest.Jextract(library = libraryName, api = api))
            jextract.set(config.readConfigField(ConfigField.JEXTRACT, path))
            extraCinteropArgs.addAll(packageName.map { listOf("-pkg", it) })
            outputDirectory.set(generatedRoot(api))
        }
        library.linkAll.configure { dependsOn(task) }
        return task
    }

    /** The JDK's bindings into the JVM compilation, and the host libraries into the jar. */
    private fun wireJvm(generate: TaskProvider<GenerateFromNativeIndexTask>, jvmTargets: List<NativeTarget>) {
        val javaSources = generate.flatMap { it.outputDirectory.dir("java") }
        val plainJvm = context.hasPlugin(GradlePluginId.KOTLIN_JVM)
        // kotlin("jvm") applies the java plugin, which compiles the main source set's Java.
        if (plainJvm) KotlinGradlePlugin.addJvmJava(project, javaSources) else KotlinGradlePlugin.addKmpJvmJava(project, javaSources)
        library.bundleInJar(jvmTargets, binding.ffm.libraryName, plainJvm = plainJvm, kmpJvm = !plainJvm)
    }

    /**
     * `ffm.targets`, else the library's targets among [loadable], else all of [loadable]: the host for the
     * JDK's bindings, 64-bit ABIs for PanamaPort's — jextract writes LP64 layouts.
     */
    private fun targets(libraryTargets: List<NativeTarget>, loadable: List<NativeTarget>, warnAboutAndroid: Boolean): List<NativeTarget> {
        library.declaredTargets().takeIf { it.isNotEmpty() }?.let { declared ->
            val unsupported = declared - loadable.toSet()
            check(unsupported.isEmpty()) {
                "konan.bindings.$bindingName.ffm.targets: ${unsupported.joinToString { it.konanName }} cannot load these bindings here; " +
                    "they are generated for ${loadable.joinToString { it.konanName }}."
            }
            return declared
        }
        val skipped = libraryTargets.filter { it.isAndroid && it.architecture !in LP64 }
        if (warnAboutAndroid && skipped.isNotEmpty()) {
            project.logger.warn(
                "konan.bindings.$bindingName: FFM bindings are generated for 64-bit layouts, so no library is provided for " +
                    "${skipped.joinToString { it.androidAbi ?: it.konanName }}; leave those ABIs out of the app."
            )
        }
        return libraryTargets.filter { it in loadable }.ifEmpty { loadable }
    }

    private companion object {
        const val PANAMA_PORT = "io.github.vova7878.panama:Core"
        val LP64 = setOf(Architecture.ARM64, Architecture.X64)
    }
}
