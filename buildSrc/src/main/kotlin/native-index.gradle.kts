// The index exporter (src/indexExporter): runs in its own JVM, started by GenerateFromNativeIndexTask
// with javaexec, whose classpath is only the Kotlin/Native distribution's compiler jar and the exporter.
// It indexes headers with cinterop's own code and runs a generator over the resulting NativeIndex, so
// only generated files leave that process. Its own source set because it compiles against the compiler
// jar and main must not; its own process because that jar bundles a Kotlin stdlib that must not meet
// Gradle's.
//
// It calls cinterop's internals, which change between Kotlin releases, and must run in the distribution
// it was compiled against. So it is compiled once per version in kotlinNativeVersions, each against that
// version's compiler jar, and the plugin picks the jar matching konanHome's version. What differs between
// versions is in src/indexExporter/compat, chosen by the thresholds below.
//
// kotlinNativeVersion is the default: the `indexExporter` source set the IDE sees, and the version
// kotlinNativeDistribution() names when the project has no Kotlin plugin.

import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val kotlinNativeVersion = providers.gradleProperty("kotlinNativeVersion").get()
val kotlinNativeVersions = providers.gradleProperty("kotlinNativeVersions").get().split(',').map { it.trim() }
check(kotlinNativeVersion in kotlinNativeVersions) { "kotlinNativeVersion $kotlinNativeVersion is not in kotlinNativeVersions" }

/** `2.3.20` as comparable parts. */
fun parts(version: String): List<Int> = version.split('.').map { it.takeWhile(Char::isDigit).toInt() }
fun atLeast(version: String, threshold: String): Boolean {
    val a = parts(version)
    val b = parts(threshold)
    for (i in 0 until maxOf(a.size, b.size)) {
        val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return true
}

/** The compat directories (src/indexExporter/compat/<facet>/<variant>) compiled into [version]'s exporter. */
fun compat(version: String): List<String> = listOfNotNull(
    // isAnonymous on StructDecl and EnumDef; from 2.0.20 cinterop's own members are used.
    "anonymous/spelling".takeUnless { atLeast(version, "2.0.20") },
    if (atLeast(version, "2.3.20")) "access/direct" else "access/none",
    if (atLeast(version, "2.4.20")) "frontend/2.4.20" else "frontend/2.0.0",
)

/** Lets few exporter compilations run at once: each holds a whole compiler jar in memory. */
abstract class ExporterCompilations : BuildService<BuildServiceParameters.None>

pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
    val sourceSets = the<SourceSetContainer>()
    val compilations = gradle.sharedServices.registerIfAbsent("exporterCompilations", ExporterCompilations::class) {
        maxParallelUsages.set(2)
    }

    // The request types both sides of the process boundary speak: one source, compiled into each.
    val protocol = "src/exporterProtocol/kotlin"
    (sourceSets.getByName("main").extensions.getByName("kotlin") as SourceDirectorySet).srcDir(protocol)

    val exporterJars = kotlinNativeVersions.map { version ->
        val suffix = if (version == kotlinNativeVersion) "" else version.replace('.', '_')
        val sourceSet = sourceSets.create("indexExporter$suffix")
        (sourceSet.extensions.getByName("kotlin") as SourceDirectorySet).apply {
            setSrcDirs(listOf(protocol, "src/indexExporter/kotlin") + compat(version).map { "src/indexExporter/compat/$it" })
        }
        sourceSet.resources.setSrcDirs(emptyList<Any>())

        dependencies {
            // compileOnly: at run time the distribution's own copy of this jar is the classpath.
            sourceSet.compileOnlyConfigurationName("org.jetbrains.kotlin:kotlin-native-compiler-embeddable:$version")
        }

        // The exporter calls cinterop's prepareTool and buildNativeLibrary, which are internal to the compiler
        // jar. Compiling against that jar as a friend module makes them visible, still type-checked: a
        // signature change in a Kotlin release fails this compilation, for that version.
        tasks.named<KotlinCompile>(sourceSet.getCompileTaskName("kotlin")) {
            usesService(compilations)
            // Incremental compilation snapshots the classpath, the whole compiler jar, for a few files.
            incremental = false
            friendPaths.from(
                configurations.named(sourceSet.compileClasspathConfigurationName).map { classpath ->
                    classpath.filter { it.name.startsWith("kotlin-native-compiler-embeddable-") }
                }
            )
            // It runs on the distribution's own stdlib, so it may not call anything newer.
            val minor = KotlinVersion.fromVersion(parts(version).take(2).joinToString("."))
            compilerOptions {
                apiVersion.set(minor)
                languageVersion.set(minor)
            }
        }

        tasks.register<Jar>("${sourceSet.name}Jar") {
            archiveFileName.set("native-index-exporter-$version.jar")
            destinationDirectory.set(layout.buildDirectory.dir("indexExporter"))
            from(sourceSet.output)
        }
    }

    // The exporters ship inside the plugin jar as nested jars, one per version; GenerateFromNativeIndexTask
    // extracts the one matching konanHome.
    tasks.named<ProcessResources>("processResources") {
        from(exporterJars) { into("io/github/lemcoder/index/exporters") }
        inputs.property("kotlinNativeVersion", kotlinNativeVersion)
        inputs.property("kotlinNativeVersions", kotlinNativeVersions)
        val properties = mapOf("kotlinNativeVersion" to kotlinNativeVersion, "kotlinNativeVersions" to kotlinNativeVersions.joinToString(","))
        filesMatching("io/github/lemcoder/index/kotlin-native.properties") { expand(properties) }
    }
}
