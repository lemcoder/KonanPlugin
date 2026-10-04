package io.github.lemcoder.index

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains

/**
 * Runs [GenerateFromNativeIndexTask] in a TestKit build: cinterop's own indexer in the pinned Kotlin/Native
 * distribution, then the `dump` generator over the index it built.
 *
 * Skipped when that distribution is not installed; any Kotlin Multiplatform build of
 * [NativeIndexer.kotlinNativeVersion] downloads it.
 */
class GenerateFromNativeIndexTest {

    @Test
    fun `mymath functions come out with their C signatures`() {
        val dump = dump("mymath")
        assertContains(dump, "function my_add(a: int32(int), b: int32(int)): int32(int)")
        assertContains(dump, "function my_scale(x: float64(double)): float64(double)")
    }

    @Test
    fun `a struct that points to itself is indexed through both paths`() {
        val dump = dump("sample")
        assertContains(dump, "typedef node_t = record struct node")
        assertContains(dump, "field next: ptr typedef node_t `node_t *` offset=64 size=8 align=8")
        assertContains(dump, "field prev: ptr record struct node `struct node *` offset=128 size=8 align=8")
    }

    @Test
    fun `macro constants are evaluated`() {
        val dump = dump("sample")
        assertContains(dump, "const SAMPLE_VERSION: int32(int) = 3")
        assertContains(dump, "const SAMPLE_MASK: int32u(unsigned int) = 16")
        assertContains(dump, "const SAMPLE_PI: float64(double) = 3.14159")
        assertContains(dump, "const SAMPLE_NAME: ptr char `char *` = \"sample\"")
    }

    @Test
    fun `an unknown generator fails with the ones that exist`() {
        assumeInstalled()
        val projectDir = project(exportTask("bogus", "mymath", mymathDef, mymathHeaders))
        val result = runner(projectDir, "generate_mymath").buildAndFail()
        assertContains(result.output, "Unknown generator 'bogus'. Known: dump")
    }

    private fun dump(name: String): String {
        assumeInstalled()
        return generated.getValue(name).resolve("index.txt").readText()
    }

    private fun assumeInstalled() =
        assumeTrue(NativeIndexer.pinnedKonanHome() != null, "Kotlin/Native ${NativeIndexer.kotlinNativeVersion} is not installed")

    private companion object {
        val sampleDef = File(checkNotNull(GenerateFromNativeIndexTest::class.java.classLoader.getResource("nativeindex/sample.def")).toURI())
        val mymathDef = File("examples/jvm/src/main/nativeInterop/mymath.def").absoluteFile
        val mymathHeaders = File("examples/jvm/native").absoluteFile

        /** One build runs the dump generator for every header the tests read: output directory by name. */
        val generated: Map<String, File> by lazy {
            val projectDir = project(
                exportTask("dump", "mymath", mymathDef, mymathHeaders),
                exportTask("dump", "sample", sampleDef, sampleDef.parentFile),
            )
            runner(projectDir, "generate_mymath", "generate_sample").build()
            listOf("mymath", "sample").associateWith { projectDir.resolve("build/generated/$it") }
        }

        fun exportTask(generator: String, name: String, def: File, includeDir: File) = """
            tasks.register<GenerateFromNativeIndexTask>("generate_$name") {
                defFile.set(file("${path(def)}"))
                includeDirs.from("${path(includeDir)}")
                target.set(hostKonanTarget())
                this.generator.set("$generator")
                outputDirectory.set(layout.buildDirectory.dir("generated/$name"))
            }
        """.trimIndent()

        fun project(vararg tasks: String): File {
            val projectDir = createTempDirectory("native-index").toFile()
            projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "native-index"""")
            projectDir.resolve("build.gradle.kts").writeText(
                (listOf(
                    "import io.github.lemcoder.hostKonanTarget",
                    "import io.github.lemcoder.index.GenerateFromNativeIndexTask",
                    "plugins { id(\"io.github.lemcoder.konanplugin\") }",
                ) + tasks).joinToString("\n")
            )
            return projectDir
        }

        fun runner(projectDir: File, vararg tasks: String): GradleRunner = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*tasks, "--stacktrace")

        fun path(file: File) = file.absolutePath.replace("\\", "/")
    }
}
