// The index exporter (src/indexExporter): runs in its own JVM, started by GenerateFromNativeIndexTask
// with javaexec, whose classpath is only the Kotlin/Native distribution's compiler jar and the exporter.
// It indexes headers with cinterop's own code and runs a generator over the resulting NativeIndex, so
// only generated files leave that process. Its own source set because it compiles against the compiler
// jar and main must not; its own process because that jar bundles a Kotlin stdlib that must not meet
// Gradle's.
//
// kotlinNativeVersion in gradle.properties picks the compiler jar it compiles against, and so the
// distribution it must run in.

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val kotlinNativeVersion = providers.gradleProperty("kotlinNativeVersion")

pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
    val sourceSets = the<SourceSetContainer>()
    val indexExporter = sourceSets.create("indexExporter")

    dependencies {
        // compileOnly: at run time the distribution's own copy of this jar is the classpath.
        "indexExporterCompileOnly"(kotlinNativeVersion.map { "org.jetbrains.kotlin:kotlin-native-compiler-embeddable:$it" })
    }

    // The exporter calls cinterop's prepareTool and buildNativeLibrary, which are internal to the compiler
    // jar. Compiling against that jar as a friend module makes them visible, still type-checked: a
    // signature change in a Kotlin release fails this compilation at the kotlinNativeVersion bump.
    tasks.named<KotlinCompile>("compileIndexExporterKotlin") {
        friendPaths.from(
            configurations.named("indexExporterCompileClasspath").map { classpath ->
                classpath.filter { it.name.startsWith("kotlin-native-compiler-embeddable-") }
            }
        )
    }

    // The exporter ships inside the plugin jar as a nested jar, extracted when GenerateFromNativeIndexTask runs.
    val indexExporterJar = tasks.register<Jar>("indexExporterJar") {
        archiveFileName.set("native-index-exporter.jar")
        destinationDirectory.set(layout.buildDirectory.dir("indexExporter"))
        from(indexExporter.output)
    }

    tasks.named<ProcessResources>("processResources") {
        from(indexExporterJar) { into("io/github/lemcoder/index") }
        // The distribution the plugin looks for at run time is the one the exporter was compiled against.
        val version = kotlinNativeVersion
        inputs.property("kotlinNativeVersion", version)
        filesMatching("io/github/lemcoder/index/kotlin-native.properties") {
            expand("kotlinNativeVersion" to version.get())
        }
    }
}
