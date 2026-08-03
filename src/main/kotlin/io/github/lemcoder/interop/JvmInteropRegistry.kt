package io.github.lemcoder.interop

import com.android.build.api.variant.AndroidComponentsExtension
import io.github.lemcoder.KonanPluginExtension
import io.github.lemcoder.abiDir
import io.github.lemcoder.hostKonanTarget
import io.github.lemcoder.isAndroid
import io.github.lemcoder.taskSuffix
import io.github.lemcoder.cleanTask
import io.github.lemcoder.jvm.GenerateJvmInteropTask
import io.github.lemcoder.jvm.JvmInteropSupport
import io.github.lemcoder.jvm.LinkJvmInteropTask
import org.gradle.api.Task
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.NamedDomainObjectFactory
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import java.io.File
import javax.inject.Inject

/**
 * Holds one `jvmInterops` container per declaration site and turns each entry into tasks.
 *
 * A project-scoped registry rather than state on the compilation: `KotlinCompilation` is not
 * `ExtensionAware`, so there is nowhere on it for a third-party plugin to hang a container. No
 * Kotlin Gradle plugin type appears in a signature here either — Gradle decorates this class as soon
 * as the plugin is applied, which would then fail in a project without KGP on the classpath. The
 * caller passes [SourceWiring] instead.
 */
abstract class JvmInteropRegistry @Inject constructor(
    private val project: Project,
    private val objects: ObjectFactory,
) {
    private val containers = mutableMapOf<String, NamedDomainObjectContainer<JvmInteropSettings>>()

    private companion object {
        const val PROJECT_KEY = "<project>"

        /**
         * Cache entries the plugin sets on an external CMake build — its side of the contract, the
         * way an AGP project reads ANDROID_ABI. A CMakeLists opts in by reading them.
         */
        const val STUB_DIR_VARIABLE = "KONAN_JNI_STUB_DIR"
        const val LIB_NAME_VARIABLE = "KONAN_JNI_LIB_NAME"
    }

    /** How a declaration site takes the generated Kotlin; keeps KGP types out of this class. */
    internal fun interface SourceWiring {
        fun addGeneratedSources(dir: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>)
    }

    internal fun containerFor(key: String, wiring: SourceWiring): NamedDomainObjectContainer<JvmInteropSettings> =
        containers.getOrPut(key) {
            objects.domainObjectContainer(
                JvmInteropSettings::class.java,
                NamedDomainObjectFactory { name -> objects.newInstance(JvmInteropSettings::class.java, name) },
            ).also { container ->
                // An object expression, not a lambda: the kotlin-dsl plugin puts a `T.() -> Unit`
                // overload of whenObjectAdded in scope, which makes the SAM form ambiguous.
                container.whenObjectAdded(object : Action<JvmInteropSettings> {
                    override fun execute(settings: JvmInteropSettings) = register(wiring, settings)
                })
            }
        }

    /**
     * Container for a module with no Kotlin compilation to hang interops on — an AGP application or
     * library, where the generated sources and `jniLibs` are wired into the variants instead.
     */
    internal fun projectContainer(): NamedDomainObjectContainer<JvmInteropSettings> =
        containers.getOrPut(PROJECT_KEY) {
            objects.domainObjectContainer(
                JvmInteropSettings::class.java,
                NamedDomainObjectFactory { name -> objects.newInstance(JvmInteropSettings::class.java, name) },
            ).also { container ->
                container.whenObjectAdded(object : Action<JvmInteropSettings> {
                    override fun execute(settings: JvmInteropSettings) = register(null, settings)
                })
            }
        }

    private fun register(wiring: SourceWiring?, settings: JvmInteropSettings) {
        val konanConfig = project.extensions.getByType(KonanPluginExtension::class.java)
        settings.targets.convention(defaultInteropTargets())
        settings.visibility.convention(BindingVisibility.INTERNAL)
        // Same default location cinterop uses, so one def serves both legs with nothing declared.
        settings.defFile.convention(
            project.layout.projectDirectory.file("src/nativeInterop/cinterop/${settings.name}.def")
        )

        val suffix = settings.name.replaceFirstChar { it.uppercase() }
        val generatedRoot = project.layout.buildDirectory.dir("generated/jvmInterop/${settings.name}")
        val jniLibsRoot = project.layout.buildDirectory.dir("jvmInterop/${settings.name}/jniLibs")

        // Falls back to the def's own `package`, so an interop that does not override it still has one.
        val packageName = settings.packageName.orElse(
            project.provider {
                settings.defFile.orNull?.asFile?.takeIf { it.isFile }?.let { DefFile.parse(it).packageName }
            }
        )

        val jniIncludeDirs = project.providers.provider {
            settings.jniHome.orNull
                ?.let { JvmInteropSupport.jniIncludeDirsOf(File(it)) ?: error("No include/jni.h under $it") }
                ?: JvmInteropSupport.detectJniIncludeDirs()
        }

        val generate = project.tasks.register(
            "generateJvmInterop$suffix",
            GenerateJvmInteropTask::class.java,
        ) {
            group = "interop"
            description = "Generate JNI bindings for '${settings.name}' from its .def."
            mustRunAfter(project.cleanTask())
            konanPath.set(konanConfig.konanPath)
            defFile.set(settings.defFile)
            this.packageName.set(packageName)
            includeDirs.from(settings.includeDirs)
            hostTarget.set(hostKonanTarget())
            this.jniIncludeDirs.set(jniIncludeDirs.map { dirs -> dirs.map { it.absolutePath } })
            additionalCompilerArgs.set(settings.additionalCompilerArgs)
            internalBindings.set(settings.visibility.map { it == BindingVisibility.INTERNAL })
            outputDirectory.set(generatedRoot)
        }

        if (wiring != null) {
            // Generated bindings are ordinary sources of whoever declared the interop; a srcDir
            // carrying the task dependency is all the wiring needed.
            wiring.addGeneratedSources(generate.flatMap { it.kotlinSourceDirectory })
        } else {
            wireIntoAndroid(settings, generate.get().kotlinSourceDirectory, jniLibsRoot)
        }

        // One link task per target. Whether it does anything depends on the def naming a library; a
        // def without staticLibraries produces bindings and a .c stub for another build to compile.
        val linkAll = project.tasks.register("linkJvmInterop$suffix") {
            group = "interop"
            description = "Link the JNI library for '${settings.name}' for every configured target."
        }

        // Deferred: the container fires whenObjectAdded before create()'s configure block runs, so
        // `targets` and externalNativeBuild are not readable yet. Everything above only needs values
        // Gradle resolves lazily.
        project.afterEvaluate {
            if (settings.externalNativeBuild.isConfigured) {
                registerExternalBuild(settings, generate, linkAll)
                return@afterEvaluate
            }

            settings.targets.get().forEach { target ->
                val link = project.tasks.register(
                    "linkJvmInterop$suffix${target.taskSuffix}",
                    LinkJvmInteropTask::class.java,
                ) {
                    group = "interop"
                    description = "Link the ${settings.name} JNI library for ${target.name}."
                    dependsOn(generate)
                    // The archive may be the one konanConfig produces, and it will not exist before then.
                    dependsOn(project.tasks.matching { it.name == "runKonanClang" })
                    mustRunAfter(project.cleanTask())
                    konanPath.set(konanConfig.konanPath)
                    this.target.set(target)
                    this.stubBaseName.set(generate.flatMap { it.stubLibraryBaseName })
                    stubCFile.set(generate.flatMap { it.stubSourceFile })
                    includeDirs.from(settings.defFile.map { it.asFile.parentFile }, settings.includeDirs)
                    staticLibrary.set(
                        project.layout.file(project.provider { resolveLibrary(settings, konanConfig, target) })
                    )
                    if (target.isAndroid) {
                        ndkResourceDir.set(
                            konanConfig.konanPath.map {
                                JvmInteropSupport.ndkResourceDir(File(it))?.absolutePath ?: ""
                            }
                        )
                    } else {
                        this.jniIncludeDirs.set(jniIncludeDirs.map { dirs -> dirs.map { it.absolutePath } })
                    }
                    additionalLinkerArgs.set(
                        settings.additionalLinkerArgs.zip(settings.targetLinkerArgs) { common, perTarget ->
                            common + perTarget[target].orEmpty()
                        }
                    )
                    outputDirectory.set(jniLibsRoot.map { it.dir(target.abiDir) })

                    onlyIf {
                        resolveLibrary(settings, konanConfig, target) != null || run {
                            logger.lifecycle(
                                "jvmInterops '${settings.name}': no staticLibraries in the def and no library() set, " +
                                    "so only the bindings and the .c stub were generated — link them from your own build."
                            )
                            false
                        }
                    }
                }
                linkAll.configure { dependsOn(link) }
                // Where a consumer looks for the library: the host's, since that is the one a JVM
                // running this build could load.
                if (target == hostKonanTarget()) {
                    settings.resolvedLibraryDirectory.set(jniLibsRoot.map { it.dir(target.abiDir) })
                }
            }
        }
    }

    /**
     * Drives the external build that compiles the stub, in place of the plugin's own link tasks. The
     * plugin supplies what only it knows — where the stub is, the library name the bindings will
     * load, and a JDK with jni.h — so the CMakeLists only reads two cache entries.
     */
    private fun registerExternalBuild(
        settings: JvmInteropSettings,
        generate: org.gradle.api.tasks.TaskProvider<GenerateJvmInteropTask>,
        linkAll: org.gradle.api.tasks.TaskProvider<Task>,
    ) {
        val cmake = settings.externalNativeBuild.cmake
        val suffix = settings.name.replaceFirstChar { it.uppercase() }
        val executable = cmake.executable.orElse(project.provider { CMakeSupport.detectExecutable() })

        // With a preset the binary directory is the preset's to choose, and `-B` cannot override it;
        // the conventional layout is <source>/build/<preset>, which stays overridable.
        val sourceDir = cmake.path.map { it.asFile.parentFile }
        val buildDir = cmake.buildDirectory.orElse(
            project.layout.dir(
                cmake.preset.map { preset -> sourceDir.get().resolve("build/$preset") }
                    .orElse(project.layout.buildDirectory.dir("jvmInterop/${settings.name}/cmake").map { it.asFile })
            )
        )

        val configure = project.tasks.register("cmakeConfigure$suffix", CMakeConfigureTask::class.java) {
            group = "interop"
            description = "Configure the external CMake build for '${settings.name}'."
            dependsOn(generate)
            this.executable.set(executable)
            preset.set(cmake.preset)
            sourceDirectory.set(sourceDir.map { it.absolutePath })
            arguments.set(cmake.arguments)
            stubSourceDirectory.set(generate.flatMap { it.stubSourceDirectory })
            cacheEntries.put(STUB_DIR_VARIABLE, generate.flatMap { it.stubSourceDirectory }.map { it.asFile.absolutePath })
            cacheEntries.put(LIB_NAME_VARIABLE, generate.flatMap { it.stubLibraryBaseName })
            javaHome.set(project.provider { JvmInteropSupport.detectJniHome().absolutePath })
            buildDirectory.set(buildDir)
        }

        val build = project.tasks.register("cmakeBuild$suffix", CMakeBuildTask::class.java) {
            group = "interop"
            description = "Build the external CMake target that links the '${settings.name}' stub."
            dependsOn(configure)
            this.executable.set(executable)
            targets.set(cmake.targets)
            stubSourceDirectory.set(generate.flatMap { it.stubSourceDirectory })
            buildDirectory.set(buildDir)
        }

        // The umbrella link task stands for "make the JNI library exist", however it gets made.
        linkAll.configure { dependsOn(build) }
        settings.resolvedLibraryDirectory.set(cmake.libraryDirectory.orElse(buildDir))
    }

    /**
     * AGP has no Kotlin compilation to attach to, so the generated sources and the per-ABI `jniLibs`
     * go in through the variant API. Registered at configuration time — the variant API rejects
     * callbacks added later — and the directories must exist by then.
     */
    private fun wireIntoAndroid(
        settings: JvmInteropSettings,
        kotlinSources: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
        jniLibsRoot: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
    ) {
        project.plugins.withId("com.android.base") {
            val kotlinDir = kotlinSources.get().asFile.apply { mkdirs() }
            val jniLibsDir = jniLibsRoot.get().asFile.apply { mkdirs() }
            val kotlinRel = kotlinDir.relativeTo(project.projectDir).path
            val jniRel = jniLibsDir.relativeTo(project.projectDir).path

            project.extensions.findByType(AndroidComponentsExtension::class.java)?.onVariants { variant ->
                variant.sources.kotlin?.addStaticSourceDirectory(kotlinRel)
                variant.sources.jniLibs?.addStaticSourceDirectory(jniRel)
            }

            val suffix = settings.name.replaceFirstChar { it.uppercase() }
            project.tasks.matching { it.name == "preBuild" }.configureEach {
                dependsOn("generateJvmInterop$suffix", "linkJvmInterop$suffix")
            }
            project.tasks
                .matching { (it.name.startsWith("compile") && it.name.contains("Kotlin")) || it.name == "compileJava" }
                .configureEach { dependsOn("generateJvmInterop$suffix") }
        }
    }

    /**
     * The archive to link: `library(...)` if set, else the def's `staticLibraries`, else whatever
     * `konanConfig` compiled for this target. The last one keeps a def portable — naming the archive
     * in it would hardcode a target directory, and the plugin already knows what it built.
     */
    private fun resolveLibrary(
        settings: JvmInteropSettings,
        konanConfig: KonanPluginExtension,
        target: KonanTarget,
    ): File? {
        settings.library.orNull?.asFile?.let { return it.takeIf { f -> f.isFile } }

        val def = settings.defFile.orNull?.asFile?.takeIf { it.isFile }
        def?.let { DefFile.parse(it).resolveStaticLibraries(it.parentFile).firstOrNull() }?.let { return it }

        if (!konanConfig.targets.getOrElse(emptyList()).contains(target)) return null
        val libName = konanConfig.libName.orNull ?: return null
        val outputDir = konanConfig.outputDir.orNull ?: return null
        return project.layout.projectDirectory
            .file("$outputDir/${target.name}/lib$libName.a").asFile
            .takeIf { it.isFile }
    }
}
