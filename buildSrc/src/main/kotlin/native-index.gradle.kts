// cinterop's NativeIndex, carried out of the Kotlin/Native distribution.
//
// The exporter (src/indexExporter) runs in its own JVM whose classpath is only the distribution's
// compiler jar and the exporter: it builds the index with cinterop's own indexer and writes it to a file.
// The plugin reads that file back into the same model classes, vendored from JetBrains/kotlin into
// src/main. Its own source set because it compiles against the compiler jar and main must not; its own
// process because that jar bundles a Kotlin stdlib and the real model classes, which must not meet
// Gradle's stdlib or the vendored copy. The text dump in src/indexShared is compiled into both the
// exporter and the tests.
//
// Everything follows kotlinNativeVersion in gradle.properties; after changing it, run
// updateNativeIndexModel.

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val kotlinNativeVersion = providers.gradleProperty("kotlinNativeVersion")
val vendoredModel = layout.projectDirectory.dir("src/main/kotlin/org/jetbrains/kotlin/native/interop/indexer")

pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
    val sourceSets = the<SourceSetContainer>()
    fun SourceSet.addKotlinSources(dir: String) =
        (extensions.getByName("kotlin") as SourceDirectorySet).srcDir(dir)

    val indexExporter = sourceSets.create("indexExporter") { addKotlinSources("src/indexShared/kotlin") }
    sourceSets.named("test") { addKotlinSources("src/indexShared/kotlin") }

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

    // The exporter ships inside the plugin jar as a nested jar, extracted when ExportNativeIndexTask runs.
    val indexExporterJar = tasks.register<Jar>("indexExporterJar") {
        archiveFileName.set("native-index-exporter.jar")
        destinationDirectory.set(layout.buildDirectory.dir("indexExporter"))
        from(indexExporter.output)
    }

    tasks.named<ProcessResources>("processResources") {
        from(indexExporterJar) { into("io/github/lemcoder/index") }
        // The version the plugin looks for at run time is the one the model was vendored from.
        val version = kotlinNativeVersion
        inputs.property("kotlinNativeVersion", version)
        filesMatching("io/github/lemcoder/index/native-index.properties") {
            expand("kotlinNativeVersion" to version.get())
        }
    }
}

tasks.register<UpdateNativeIndexModel>("updateNativeIndexModel") {
    version.set(kotlinNativeVersion)
    directory.set(vendoredModel)
}

val verifyNativeIndexModel = tasks.register<VerifyNativeIndexModel>("verifyNativeIndexModel") {
    version.set(kotlinNativeVersion)
    sources.from(NativeIndexModel.files.map { vendoredModel.file(it) })
}

tasks.named("check") {
    dependsOn(verifyNativeIndexModel)
}
