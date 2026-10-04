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
 * [PinnedKotlinNative.version] downloads it.
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
    fun `the jni generator binds each C function under its own name`() {
        val kotlin = jni().resolve("kotlin/sample/sample.kt").readText()
        assertContains(kotlin, "private val nativeLibrary: Unit = System.loadLibrary(\"samplestubs\")")
        assertContains(kotlin, "@JvmName(\"sample_sum\")\ninternal external fun sample_sum(values: IntArray?, count: Long): Int")
        assertContains(kotlin, "internal external fun sample_name(): String?")
        assertContains(kotlin, "internal external fun sample_buffer(handle: Long, c: Int, s: Int): ByteArray")
        assertContains(kotlin, "internal external fun sample_bits(f: ByteArray): ByteArray")
        assertContains(kotlin, "internal external fun sample_sort(base: Long, n: Long, cmp: Long): Unit")
        assertContains(kotlin, "// - sample_log: variadic")
    }

    @Test
    fun `the jni stub marshals each parameter and escapes the JNI symbol`() {
        val c = jni().resolve("c/samplestubs.c").readText()
        assertContains(c, "#include <sample.h>")
        assertContains(c, "Java_sample_sample_sample_1sum(JNIEnv* env, jclass cls, jintArray p0, jlong p1)")
        // const int*: pinned for the call, not copied back.
        assertContains(c, "if (p0) (*env)->ReleaseIntArrayElements(env, p0, a0, JNI_ABORT);")
        assertContains(c, "jstring result = r ? (*env)->NewStringUTF(env, r) : 0;")
        assertContains(c, "if (!p0 || (*env)->GetArrayLength(env, p0) < (jsize)sizeof(struct flags)) {")
        assertContains(c, "sample_sort((void *)(intptr_t)p0, p1, (compare_fn)(intptr_t)p2);")
    }

    @Test
    fun `an unknown generator fails with the ones that exist`() {
        assumeInstalled()
        val projectDir = project(exportTask("bogus", "mymath", mymathDef, mymathHeaders))
        val result = runner(projectDir, "generate_mymath").buildAndFail()
        assertContains(result.output, "Unknown generator 'bogus'. Known: dump, jni")
    }

    private fun dump(name: String): String {
        assumeInstalled()
        return generated.getValue(name).resolve("index.txt").readText()
    }

    private fun jni(): File {
        assumeInstalled()
        return generated.getValue("jni_sample")
    }

    private fun assumeInstalled() =
        assumeTrue(PinnedKotlinNative.home() != null, "Kotlin/Native ${PinnedKotlinNative.version} is not installed")

    private companion object {
        val sampleDef = File(checkNotNull(GenerateFromNativeIndexTest::class.java.classLoader.getResource("nativeindex/sample.def")).toURI())
        val mymathDef = File("examples/jvm/src/main/nativeInterop/mymath.def").absoluteFile
        val mymathHeaders = File("examples/jvm/native").absoluteFile

        /** One build runs every generator the tests read: output directory by task name. */
        val generated: Map<String, File> by lazy {
            val projectDir = project(
                exportTask("dump", "mymath", mymathDef, mymathHeaders),
                exportTask("dump", "sample", sampleDef, sampleDef.parentFile),
                exportTask("jni", "jni_sample", sampleDef, sampleDef.parentFile, "stubLibrary" to "samplestubs"),
            )
            val names = listOf("mymath", "sample", "jni_sample")
            runner(projectDir, *names.map { "generate_$it" }.toTypedArray()).build()
            names.associateWith { projectDir.resolve("build/generated/$it") }
        }

        fun exportTask(generator: String, name: String, def: File, includeDir: File, vararg options: Pair<String, String>) = """
            tasks.register<GenerateFromNativeIndexTask>("generate_$name") {
                defFile.set(file("${path(def)}"))
                includeDirs.from("${path(includeDir)}")
                target.set(hostKonanTarget())
                this.generator.set("$generator")
                generatorOptions.set(mapOf(${options.joinToString { (k, v) -> "\"$k\" to \"$v\"" }}))
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
