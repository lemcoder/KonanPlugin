package io.github.lemcoder.wiring

import io.github.lemcoder.abiDir
import io.github.lemcoder.cleanTask
import io.github.lemcoder.dsl.BindingSpec
import io.github.lemcoder.dsl.BindingVisibility
import io.github.lemcoder.dsl.KonanExtension
import io.github.lemcoder.library.NativeArtifacts
import io.github.lemcoder.hostKonanTarget
import io.github.lemcoder.index.GenerateFromNativeIndexTask
import io.github.lemcoder.index.GeneratedVisibility
import io.github.lemcoder.index.GeneratorRequest
import io.github.lemcoder.isAndroid
import io.github.lemcoder.jni.CMakeBuildTask
import io.github.lemcoder.jni.CMakeConfigureTask
import io.github.lemcoder.jni.CMakeSupport
import io.github.lemcoder.jni.GenerateJniBindingsTask
import io.github.lemcoder.link.LinkSharedLibraryTask
import io.github.lemcoder.taskSuffix
import io.github.lemcoder.toolchain.NativeToolchain
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File

/**
 * Cache entries the plugin sets on an external CMake build — its side of the contract, the way an AGP
 * project reads ANDROID_ABI. A CMakeLists opts in by reading them.
 */
private const val STUB_DIR_VARIABLE = "KONAN_JNI_STUB_DIR"
private const val LIB_NAME_VARIABLE = "KONAN_JNI_LIB_NAME"


/** Turns every binding into the tasks and wiring each of the project's consumers needs. */
internal fun Project.registerBindings(konan: KonanExtension) {
    konan.bindings.all(object : Action<BindingSpec> {
        override fun execute(binding: BindingSpec) = BindingWiring(this@registerBindings, konan, binding).register()
    })
}

private class BindingWiring(
    private val project: Project,
    private val konan: KonanExtension,
    private val binding: BindingSpec,
) {
    private val bindingName = binding.name
    private val suffix = bindingName.capitalized()
    private val build = project.layout.buildDirectory

    // Only per-ABI libraries may live under jniLibs: AGP reads each subdirectory name as an ABI, and a
    // host library sitting in the root fails its merge with "… is not an ABI".
    private val jniLibsRoot = build.dir("konan/bindings/$bindingName/jni/jniLibs")
    private val jniHostLibRoot = build.dir("konan/bindings/$bindingName/jni/lib")

    /** The archives of the library the binding names, once the build script has been evaluated. */
    private fun artifacts(): NativeArtifacts? = binding.library.orNull?.let { NativeArtifacts.of(project, it) }

    fun register() {
        // The container fires before create()'s configure block runs: conventions and lazily configured
        // tasks here; anything that needs the declared values waits for afterEvaluate.
        binding.defFile.convention(project.layout.projectDirectory.file("src/nativeInterop/cinterop/$bindingName.def"))
        binding.jni.visibility.convention(BindingVisibility.INTERNAL)

        val generateJni = registerJniGeneration()
        val jniSources = generateJni.flatMap { it.kotlinSourceDirectory }
        val linkJni = project.tasks.register("linkJni$suffix") {
            group = "konan"
            description = "Make the '$bindingName' JNI library exist for every target, however it is built."
        }

        // Eager: the variant API must be wired at configuration time.
        project.withPlugin(GradlePluginId.KOTLIN_MULTIPLATFORM) { KgpWiring.addKmpJvmSources(project, jniSources) }
        AgpWiring.addKotlinSources(project, jniSources, generateJni, linkJni)
        AgpWiring.addJniLibs(project, jniLibsRoot, linkJni)
        // AGP looks for a `baselineProfiles` directory beside every source root, including the generated
        // one, by plain path — no task dependency. Matched by name: inert without AGP.
        project.tasks.matching { it.name.contains("ArtProfile") }.configureEach { dependsOn(generateJni) }

        project.afterEvaluate {
            // In a kotlin("jvm") project FFM replaces JNI: both would define the same facade class.
            if (project.hasPlugin(GradlePluginId.KOTLIN_JVM) && !binding.ffm.isEnabled) KgpWiring.addJvmSources(project, jniSources)
            if (hasJniConsumer()) {
                if (binding.jni.usesCMake) registerCMakeBuild(generateJni, linkJni) else registerJniLinks(generateJni, linkJni)
            }
            if (project.hasPlugin(GradlePluginId.KOTLIN_MULTIPLATFORM)) {
                KgpWiring.declareCinterops(project, binding, artifacts())
            }
            if (binding.ffm.isEnabled) registerFfm()
        }
    }

    private fun hasJniConsumer(): Boolean =
        (project.hasPlugin(GradlePluginId.KOTLIN_JVM) && !binding.ffm.isEnabled) ||
            project.hasPlugin(GradlePluginId.ANDROID_BASE) || project.hasPlugin(GradlePluginId.ANDROID_KMP_LIBRARY) ||
            (project.hasPlugin(GradlePluginId.KOTLIN_MULTIPLATFORM) && KgpWiring.hasKmpJvmTarget(project))

    /** The package the bindings go in: the binding's, else the `.def`'s own. */
    private val packageName: Provider<String> = binding.packageName.orElse(
        project.provider { binding.defFile.orNull?.asFile?.takeIf { it.isFile }?.let { DefFile.parse(it).packageName } }
    )

    private fun registerJniGeneration(): TaskProvider<GenerateJniBindingsTask> =
        project.tasks.register("generateJni$suffix", GenerateJniBindingsTask::class.java) {
            group = "konan"
            description = "Generate the '$bindingName' JNI bindings and stub from its .def."
            mustRunAfter(project.cleanTask())
            defFile.set(binding.defFile)
            packageName.set(this@BindingWiring.packageName)
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
            outputDirectory.set(build.dir("generated/konan/$bindingName/jni"))
        }

    /** Default JNI targets: the library's that a JVM can load — the host and Android — else the host. */
    private fun jniTargets(): List<KonanTarget> = binding.jni.targets.getOrElse(emptyList()).ifEmpty {
        artifacts()?.targets?.getOrElse(emptyList())?.filter { it == hostKonanTarget() || it.isAndroid }
            ?.ifEmpty { null }
            ?: listOf(hostKonanTarget())
    }

    private fun registerJniLinks(generate: TaskProvider<GenerateJniBindingsTask>, linkAll: TaskProvider<Task>) {
        val artifacts = artifacts()
        val jniIncludeDirs = project.providers.provider {
            binding.jni.jdkHome.orNull?.asFile
                ?.let { NativeToolchain.jniIncludeDirsOf(it) ?: error("No include/jni.h under $it") }
                ?: NativeToolchain.detectJniIncludeDirs()
        }
        jniTargets().forEach { target ->
            val link = project.tasks.register("linkJni$suffix${target.taskSuffix}", LinkSharedLibraryTask::class.java) {
                group = "konan"
                description = "Link the '$bindingName' JNI library for ${target.name}."
                dependsOn(generate)
                artifacts?.let { dependsOn(it.builtBy) }
                mustRunAfter(project.cleanTask())
                konanPath.set(konan.konanHome.map { it.asFile.absolutePath })
                this.target.set(target)
                libraryBaseName.set(generate.flatMap { it.stubLibraryBaseName })
                stubCFile.set(generate.flatMap { it.stubSourceFile })
                includeDirs.from(binding.includeDirs)
                // A plain path, not a tracked directory: a .def at the project root would make the whole
                // project, build/ included, an input of this task.
                includePaths.add(binding.defFile.map { it.asFile.parentFile.absolutePath })
                staticLibrary.set(project.layout.file(project.provider { archive(artifacts, target) }))
                if (target.isAndroid) {
                    ndkResourceDir.set(konan.konanHome.map { NativeToolchain.ndkResourceDir(it.asFile)?.absolutePath ?: "" })
                } else {
                    this.jniIncludeDirs.set(jniIncludeDirs.map { dirs -> dirs.map { it.absolutePath } })
                }
                additionalLinkerArgs.set(
                    binding.jni.linkerOptions.zip(binding.jni.targetLinkerOptions) { common, perTarget ->
                        common + perTarget[target].orEmpty()
                    }
                )
                outputDirectory.set(
                    if (target.isAndroid) jniLibsRoot.map { it.dir(target.abiDir) } else jniHostLibRoot.map { it.dir(target.name) }
                )
                onlyIf {
                    archive(artifacts, target) != null || run {
                        logger.lifecycle(
                            "konan.bindings.$bindingName: no library(), jni.archive or staticLibraries in the .def, so only " +
                                "the JNI bindings and the .c stub were generated — link them from your own build."
                        )
                        false
                    }
                }
            }
            linkAll.configure { dependsOn(link) }
            // Where a consumer looks for the library: the host's, the one a JVM running this build loads.
            if (target == hostKonanTarget()) binding.jni.libraryDirectory.convention(jniHostLibRoot.map { it.dir(target.name) })
        }
    }

    /**
     * The archive to link: `jni.archive` if set, else the library's for [target], else the `.def`'s
     * `staticLibraries`.
     */
    private fun archive(artifacts: NativeArtifacts?, target: KonanTarget): File? {
        binding.jni.archive.orNull?.asFile?.let { return it.takeIf { f -> f.isFile } }
        artifacts?.let {
            return it.archive(target).get().asFile.takeIf { _ -> target in it.targets.getOrElse(emptyList()) }
        }
        val def = binding.defFile.orNull?.asFile?.takeIf { it.isFile } ?: return null
        return DefFile.parse(def).resolveStaticLibraries(def.parentFile).firstOrNull()
    }

    /**
     * Drives the external build that compiles the stub, in place of the plugin's own link tasks. The
     * plugin supplies what only it knows — where the stub is, the library name the bindings will load,
     * and a JDK with jni.h — so the CMakeLists only reads two cache entries.
     */
    private fun registerCMakeBuild(generate: TaskProvider<GenerateJniBindingsTask>, linkAll: TaskProvider<Task>) {
        val cmake = binding.jni.cmake
        val executable = cmake.executable.map { it.asFile.absolutePath }.orElse(project.provider { CMakeSupport.detectExecutable() })
        val sourceDir = cmake.path.map { it.asFile.parentFile }
        // No ABIs means one build for the host; with them, one per ABI, each into jniLibs/<abi>/.
        val abis = cmake.abis.toList()

        (if (abis.isEmpty()) listOf(null) else abis).forEach { abi ->
            val abiSuffix = abi?.name.orEmpty().split('-', '_').joinToString("") { it.capitalized() }
            val preset = abi?.preset?.orElse(cmake.preset) ?: cmake.preset
            val outputDir = if (abi == null) jniHostLibRoot else jniLibsRoot.map { it.dir(abi.name) }
            // With a preset the binary directory is the preset's to choose, and `-B` cannot override it;
            // the conventional layout is <source>/build/<preset>, which stays overridable.
            val buildDir = (abi?.buildDirectory ?: cmake.buildDirectory).orElse(
                project.layout.dir(
                    preset.map { presetName -> sourceDir.get().resolve("build/$presetName") }
                        .orElse(build.dir("konan/bindings/$bindingName/jni/cmake$abiSuffix").map { it.asFile })
                )
            )
            val configure = project.tasks.register("cmakeConfigure$suffix$abiSuffix", CMakeConfigureTask::class.java) {
                group = "konan"
                description = "Configure the external CMake build for '$bindingName'" + (abi?.let { " (${it.name})" } ?: "") + "."
                dependsOn(generate)
                this.executable.set(executable)
                this.preset.set(preset)
                sourceDirectory.set(sourceDir.map { it.absolutePath })
                arguments.set(cmake.arguments.zip(abi?.arguments ?: cmake.arguments.map { emptyList() }) { a, b -> a + b })
                stubSourceDirectory.set(generate.flatMap { it.stubSourceDirectory })
                cacheEntries.put(STUB_DIR_VARIABLE, generate.flatMap { it.stubSourceDirectory }.map { it.asFile.absolutePath })
                cacheEntries.put(LIB_NAME_VARIABLE, generate.flatMap { it.stubLibraryBaseName })
                // Land the library where the plugin says, so a consumer never guesses the build layout and
                // Android gets the jniLibs/<abi>/ shape AGP packages.
                cacheEntries.put("CMAKE_LIBRARY_OUTPUT_DIRECTORY", outputDir.map { it.asFile.absolutePath })
                javaHome.set(project.provider { NativeToolchain.detectJniHome().absolutePath })
                buildDirectory.set(buildDir)
            }
            val cmakeBuild = project.tasks.register("cmakeBuild$suffix$abiSuffix", CMakeBuildTask::class.java) {
                group = "konan"
                description = "Build the '$bindingName' JNI library" + (abi?.let { " for ${it.name}" } ?: "") + "."
                dependsOn(configure)
                this.executable.set(executable)
                targets.set(cmake.targets)
                stubSourceDirectory.set(generate.flatMap { it.stubSourceDirectory })
                buildDirectory.set(buildDir)
                cmakeCache.set(buildDir.map { it.file("CMakeCache.txt") })
                libraryDirectory.set(outputDir)
            }
            linkAll.configure { dependsOn(cmakeBuild) }
        }
        binding.jni.libraryDirectory.convention(cmake.libraryDirectory.orElse(if (abis.isEmpty()) jniHostLibRoot else jniLibsRoot))
    }

    /**
     * FFM bindings: jextract over the same `.def`, and the library itself as a shared library for the
     * host — FFM loads it directly, with no stub.
     */
    private fun registerFfm() {
        val artifacts = artifacts()
        val libraryName = artifacts?.name ?: bindingName
        val host = hostKonanTarget()
        val generate = project.tasks.register("generateFfm$suffix", GenerateFromNativeIndexTask::class.java) {
            group = "konan"
            description = "Generate the '$bindingName' FFM bindings with jextract."
            defFile.set(binding.defFile)
            includeDirs.from(binding.includeDirs)
            compilerOptions.set(binding.compilerOptions)
            target.set(host)
            request.set(project.provider { GeneratorRequest.Jextract(library = libraryName, jextract = binding.ffm.jextract.orNull?.asFile) })
            packageName.orNull?.let { extraCinteropArgs.addAll("-pkg", it) }
            outputDirectory.set(build.dir("generated/konan/$bindingName/ffm"))
        }
        val ffmLib = build.dir("konan/bindings/$bindingName/ffm/lib/${host.name}")
        binding.ffm.libraryDirectory.convention(ffmLib)
        if (artifacts != null && host in artifacts.targets.getOrElse(emptyList())) {
            project.tasks.register("linkFfm$suffix", LinkSharedLibraryTask::class.java) {
                group = "konan"
                description = "Link the '$libraryName' library as a shared library for the FFM bindings."
                dependsOn(artifacts.builtBy)
                konanPath.set(konan.konanHome.map { it.asFile.absolutePath })
                target.set(host)
                libraryBaseName.set(libraryName)
                staticLibrary.set(artifacts.archive(host))
                additionalLinkerArgs.set(binding.jni.linkerOptions)
                outputDirectory.set(ffmLib)
            }
        }
        if (project.hasPlugin(GradlePluginId.KOTLIN_JVM)) {
            // Plain Gradle source sets: Java is compiled by the java plugin, which kotlin("jvm") applies.
            project.extensions.getByType(SourceSetContainer::class.java).getByName("main")
                .java.srcDir(generate.map { it.outputDirectory.dir("java") })
        } else {
            project.logger.info("konan.bindings.$bindingName: FFM bindings are only wired into kotlin(\"jvm\") projects; add build/generated/konan/$bindingName/ffm/java yourself.")
        }
    }
}
