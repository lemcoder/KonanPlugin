package io.github.lemcoder.jvm

import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Project
import java.io.File

internal fun Project.registerJvmInterop(ext: JvmInteropExtension) {
    val konanHome = providers.provider {
        ext.konanPath.orNull?.let { File(it) } ?: JvmInteropSupport.detectKonanHome()
    }
    val jniIncludeDirs = providers.provider {
        ext.jniHome.orNull?.let { JvmInteropSupport.jniIncludeDirsOf(File(it)) ?: error("No include/jni.h under ${ext.jniHome.get()}") }
            ?: JvmInteropSupport.detectJniIncludeDirs()
    }

    val generatedRoot = layout.buildDirectory.dir("generated/jvmInterop")
    val jniLibsRoot = layout.buildDirectory.dir("jvmInterop/jniLibs")

    val generate = tasks.register("generateJvmInterop", GenerateJvmInteropTask::class.java) {
        group = "interop"
        description = "Generate runtime-free JNI Kotlin bindings + .c stubs from C headers."
        this.konanPath.set(konanHome.map { it.absolutePath })
        this.headers.set(ext.headers)
        this.packageName.set(ext.packageName)
        this.headerFilter.set(ext.headerFilter)
        this.headerDir.set(ext.headerDir.map { layout.projectDirectory.dir(it).asFile.absolutePath })
        // The generator indexes headers once on the host; the produced bridges are platform-independent.
        this.hostTarget.set(JvmInteropSupport.hostTarget())
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
    // AGP 9 (which rejects Provider-based sourceSet entries).
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
        // jvmInterop is optional — skip all wiring when the project doesn't configure it.
        if (!ext.packageName.isPresent) return@afterEvaluate

        val cFile = generatedRoot.map { it.dir("c").file("${JvmInteropSupport.stubBaseName(ext.packageName.get())}.c") }
        val stubBase = JvmInteropSupport.stubBaseName(ext.packageName.get())
        ext.targets.get().forEach { target ->
            val linkTarget = tasks.register("linkJvmInterop${target.replaceFirstChar { it.uppercase() }}", LinkJvmInteropTask::class.java) {
                group = "interop"
                description = "Link lib$stubBase for $target."
                dependsOn(generate)
                this.konanPath.set(konanHome.map { it.absolutePath })
                this.target.set(target)
                this.stubBaseName.set(stubBase)
                this.stubCFile.set(cFile)
                this.headerDir.set(ext.headerDir.map { layout.projectDirectory.dir(it).asFile.absolutePath })
                this.staticLibrary.set(
                    ext.staticLibraryDir.flatMap { dir ->
                        ext.staticLibraryName.map { name ->
                            layout.projectDirectory.file("$dir/$target/lib$name.a")
                        }
                    }
                )
                if (target.startsWith("android")) {
                    this.ndkResourceDir.set(JvmInteropSupport.ndkResourceDir(konanHome.get())?.absolutePath ?: "")
                } else {
                    this.jniIncludeDirs.set(jniIncludeDirs.map { dirs -> dirs.map { it.absolutePath } })
                }
                this.additionalLinkerArgs.set(ext.additionalLinkerArgs)
                this.outputDirectory.set(jniLibsRoot.map { it.dir(JvmInteropSupport.abiDir(target)) })
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
