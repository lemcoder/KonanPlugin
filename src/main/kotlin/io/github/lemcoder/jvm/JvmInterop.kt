package io.github.lemcoder.jvm

import com.android.build.api.variant.AndroidComponentsExtension
import io.github.lemcoder.KonanPluginExtension
import io.github.lemcoder.KonanTarget
import io.github.lemcoder.cleanTask
import org.gradle.api.Project
import java.io.File

internal fun Project.registerJvmInterop(konanConfig: KonanPluginExtension) {
    val ext = konanConfig.jvmInterop

    val konanHome = ext.konanPath.map { File(it) }
    val jniIncludeDirs = providers.provider {
        ext.jniHome.orNull?.let { JvmInteropSupport.jniIncludeDirsOf(File(it)) ?: error("No include/jni.h under $it") }
            ?: JvmInteropSupport.detectJniIncludeDirs()
    }

    val generatedRoot = layout.buildDirectory.dir("generated/jvmInterop")
    val jniLibsRoot = layout.buildDirectory.dir("jvmInterop/jniLibs")

    val generate = tasks.register("generateJvmInterop", GenerateJvmInteropTask::class.java) {
        group = "interop"
        description = "Generate runtime-free JNI Kotlin bindings + .c stubs from C headers."
        // Both tasks write under build/, and `clean` is otherwise unordered against them.
        mustRunAfter(cleanTask())
        this.konanPath.set(konanHome.map { it.absolutePath })
        this.headers.set(ext.headers)
        this.packageName.set(ext.packageName)
        this.headerFilter.set(ext.headerFilter)
        this.headerDir.set(ext.headerDir.map { layout.projectDirectory.dir(it).asFile.absolutePath })
        // The generator indexes headers once on the host; the produced bridges are platform-independent.
        this.hostTarget.set(KonanTarget.host())
        this.jniIncludeDirs.set(jniIncludeDirs.map { dirs -> dirs.map { it.absolutePath } })
        this.additionalCompilerArgs.set(ext.additionalCompilerArgs)
        this.outputDirectory.set(generatedRoot)
    }

    val link = tasks.register("linkJvmInterop") {
        group = "interop"
        description = "Compile + link the JNI stub shared library for every configured target."
    }

    // Auto-wire sources into an Android project (AGP), if present: the generated Kotlin bridges as a
    // source dir and the per-ABI jniLibs/ as native libraries. Registered at configuration time (the
    // variant API rejects callbacks added from afterEvaluate), via the variant API so it works with
    // AGP 9 (which rejects Provider-based sourceSet entries). This runs before the build script's
    // konanConfig block is evaluated, so it can't consult `enabled`; wiring empty directories when the
    // JNI leg is off is harmless.
    plugins.withId("com.android.base") {
        val generatedKotlinDir = layout.buildDirectory.dir("generated/jvmInterop/kotlin").get().asFile
        val jniLibsDir = layout.buildDirectory.dir("jvmInterop/jniLibs").get().asFile
        // addStaticSourceDirectory requires the directories to exist at configuration time.
        generatedKotlinDir.mkdirs(); jniLibsDir.mkdirs()
        val kotlinRel = generatedKotlinDir.relativeTo(projectDir).path
        val jniRel = jniLibsDir.relativeTo(projectDir).path
        extensions.findByType(AndroidComponentsExtension::class.java)
            ?.onVariants { variant ->
                variant.sources.kotlin?.addStaticSourceDirectory(kotlinRel)
                variant.sources.jniLibs?.addStaticSourceDirectory(jniRel)
            }
    }

    // One link task per target; the umbrella `linkJvmInterop` depends on all of them.
    afterEvaluate {
        if (!ext.enabled.get()) return@afterEvaluate

        val packageName = ext.packageName.orNull ?: error(
            "konanConfig.jvmInterop.packageName is not set and could not be inferred " +
                "(no Android namespace). Set it, or disable the JNI leg with jvmInterop { enabled.set(false) }."
        )
        val stubBase = JvmInteropSupport.stubBaseName(packageName)
        val cFile = generatedRoot.map { it.dir("c").file("$stubBase.c") }

        ext.targets.get().forEach { target ->
            val linkTarget = tasks.register("linkJvmInterop${target.taskSuffix}", LinkJvmInteropTask::class.java) {
                group = "interop"
                description = "Link lib$stubBase for ${target.konanName}."
                dependsOn(generate)
                mustRunAfter(cleanTask())
                this.konanPath.set(konanHome.map { it.absolutePath })
                this.target.set(target)
                this.stubBaseName.set(stubBase)
                this.stubCFile.set(cFile)
                this.headerDir.set(ext.headerDir.map { layout.projectDirectory.dir(it).asFile.absolutePath })
                this.staticLibrary.set(
                    ext.staticLibraryDir.flatMap { dir ->
                        ext.staticLibraryName.map { name ->
                            layout.projectDirectory.file("$dir/${target.konanName}/lib$name.a")
                        }
                    }
                )
                if (target.isAndroid) {
                    this.ndkResourceDir.set(JvmInteropSupport.ndkResourceDir(konanHome.get())?.absolutePath ?: "")
                    this.ndkSysrootLibDir.set(
                        JvmInteropSupport.ndkSysrootLibDir(konanHome.get(), target)?.absolutePath ?: ""
                    )
                } else {
                    this.jniIncludeDirs.set(jniIncludeDirs.map { dirs -> dirs.map { it.absolutePath } })
                }
                this.additionalLinkerArgs.set(
                    ext.additionalLinkerArgs.zip(ext.targetLinkerArgs) { common, perTarget ->
                        common + perTarget[target].orEmpty()
                    }
                )
                this.outputDirectory.set(jniLibsRoot.map { it.dir(target.abiDir) })
            }
            // The JNI stub statically links the .a produced by `runKonanClang`.
            tasks.findByName("runKonanClang")?.let { rk -> linkTarget.configure { dependsOn(rk) } }
            link.configure { dependsOn(linkTarget) }
        }

        // Auto-wire task ordering: generate bridges before any Kotlin/Java compile, and build the
        // stub libraries before AGP packages them (preBuild).
        tasks.matching { (it.name.startsWith("compile") && it.name.contains("Kotlin")) || it.name == "compileJava" }
            .configureEach { dependsOn(generate) }
        tasks.matching { it.name == "preBuild" }
            .configureEach { dependsOn(generate, link) }
    }
}
