package io.github.lemcoder

import com.android.build.api.dsl.CommonExtension
import io.github.lemcoder.jvm.JvmInteropSupport
import io.github.lemcoder.jvm.registerJvmInterop
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

/**
 * Default clang arguments for [RunKonanClangTask]; `konanConfig.additionalCompilerArgs` is appended.
 *
 * No `-std=` here: sources are compiled in one invocation, so a C standard would also reach any `.cpp`
 * in the source dir, which clang rejects outright ("invalid argument '-std=c99' not allowed with
 * 'C++'"). Builds that need a specific standard set it via `additionalCompilerArgs`.
 */
private val DEFAULT_COMPILER_ARGS = listOf("-fno-sanitize=undefined")

/**
 * The `clean` task as a lazy collection — empty when the base plugin isn't applied, so it is safe to
 * pass to `mustRunAfter` unconditionally.
 */
internal fun Project.cleanTask() = tasks.matching { it.name == "clean" }

abstract class KonanPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Single entry point: C/C++ -> static .a, with JNI binding generation as a nested block.
        val extension = project.extensions.create("konanConfig", KonanPluginExtension::class.java)

        project.applyConventions(extension)
        project.registerRunKonanClang(extension)
        project.registerJvmInterop(extension)
    }
}

/**
 * Conventions for everything that can be inferred, so a build only has to declare what is genuinely
 * project-specific. The nested `jvmInterop` block defaults to the enclosing values, which is what
 * makes it collapse to a package name in practice.
 */
private fun Project.applyConventions(ext: KonanPluginExtension) {
    ext.sourceDir.convention("native")
    ext.headerDir.convention(ext.sourceDir)
    ext.libName.convention(name)
    ext.outputDir.convention("build/native")
    // Auto-detected: $KONAN_HOME, else the newest ~/.konan/kotlin-native-prebuilt-*. Lazy, so a build
    // that never runs a konan task doesn't require a distribution to be installed.
    ext.konanPath.convention(providers.provider { JvmInteropSupport.detectKonanHome().absolutePath })

    val interop = ext.jvmInterop
    // Enabled when the build declares the block, or whenever an Android target is configured — the
    // Android leg is pointless without the JNI bridges. `enabled.set(false)` opts back out.
    interop.enabled.convention(
        providers.provider { ext.jvmInteropDeclared || ext.targets.getOrElse(emptyList()).any { it.isAndroid } }
    )
    interop.headerDir.convention(ext.headerDir)
    interop.targets.convention(ext.targets)
    interop.staticLibraryDir.convention(ext.outputDir)
    interop.staticLibraryName.convention(ext.libName)
    interop.konanPath.convention(ext.konanPath)
    // Bind every header in the include root; a build with private headers can narrow this.
    interop.headers.convention(
        interop.headerDir.map { dir ->
            layout.projectDirectory.dir(dir).asFile
                .walkTopDown().filter { it.extension == "h" }
                .map { it.name }.sorted().toList()
        }
    )
    // On Android the app's own namespace is the natural package for the bridges.
    plugins.withId("com.android.base") {
        val namespace = providers.provider {
            extensions.findByType(CommonExtension::class.java)?.namespace
        }
        interop.packageName.convention(namespace)
    }
}

private fun Project.registerRunKonanClang(ext: KonanPluginExtension) {
    tasks.register("runKonanClang", RunKonanClangTask::class.java) {
        group = "interop"
        description = "Cross-compile the C/C++ sources to a static library per target."

        // `clean` and this task are otherwise unordered, and the scratch dir this one writes to lives
        // under build/tmp — so `gradle clean assembleDebug` can delete it mid-compile.
        mustRunAfter(cleanTask())

        // All wiring is lazy: the task can be registered even when konanConfig is left empty (e.g. a
        // project that only consumes the JNI leg), and only fails if it is actually asked to run.
        targets.set(ext.targets)
        outputDir.set(ext.outputDir.map { layout.projectDirectory.dir(it) })
        sourceFiles.from(ext.sourceDir.map { layout.projectDirectory.dir(it).asFileTree })
        includeDirs.from(ext.headerDir.map { layout.projectDirectory.dir(it) })
        libName.set(ext.libName)

        arguments.addAll(DEFAULT_COMPILER_ARGS)
        arguments.addAll(ext.additionalCompilerArgs)

        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val scriptPath = if (isWindows) "bin/run_konan.bat" else "bin/run_konan"
        runKonan.fileProvider(ext.konanPath.map { File(it).resolve(scriptPath) })

        onlyIf { targets.get().isNotEmpty() }
    }
}
