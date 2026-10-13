package io.github.lemcoder.index

import io.github.lemcoder.kotlin.KonanDistribution
import io.github.lemcoder.testRunner
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs [GenerateFromNativeIndexTask] in a TestKit build: cinterop's own indexer in the default Kotlin/Native
 * distribution, then the `dump` generator over the index it built.
 *
 * Skipped when that distribution is not installed; any Kotlin Multiplatform build of
 * [KonanDistribution.defaultVersion] downloads it. Every other supported version installed under `~/.konan`
 * must generate the same JNI bindings.
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
        assertContains(kotlin, "private val nativeLibrary: Unit = loadNativeLibrary(\"samplestubs\")")
        assertContains(kotlin, "@JvmName(\"sample_sum\")\ninternal external fun sample_sum(values: IntArray?, count: Long): Int")
        assertContains(kotlin, "internal external fun sample_name(): String?")
        assertContains(kotlin, "internal external fun sample_buffer(handle: Long, c: Int, s: Int): ByteArray")
        assertContains(kotlin, "internal external fun sample_bits(f: ByteArray): ByteArray")
        assertContains(kotlin, "internal external fun sample_sort(base: java.nio.Buffer?, n: Long, cmp: Long): Unit")
        // A typedef'd void* is a handle, not memory.
        assertContains(kotlin, "internal external fun sample_release(handle: Long): Unit")
        assertContains(kotlin, "// - sample_log: variadic")
        assertContains(kotlin, "internal external fun sample_open(out: LongArray?): Int")
    }

    @Test
    fun `the jni generator turns enum and macro constants into const vals`() {
        val kotlin = jni().resolve("kotlin/sample/sample.kt").readText()
        assertContains(kotlin, "internal const val GREEN: Int = 5")
        assertContains(kotlin, "internal const val LARGE: Int = 100")
        assertContains(kotlin, "internal const val SAMPLE_VERSION: Int = 3")
        assertContains(kotlin, "internal const val SAMPLE_MASK: Int = 16")
        assertContains(kotlin, "internal const val SAMPLE_PI: Double = 3.14159")
        assertContains(kotlin, "internal const val SAMPLE_NAME: String = \"sample\"")
    }

    @Test
    fun `the jni stub copies pointer out-parameters through a pointer array`() {
        val c = jni().resolve("c/samplestubs.c").readText()
        assertContains(c, "for (jsize k = 0; k < n0; k++) t0[k] = (void*)(intptr_t)a0[k];")
        assertContains(c, "sample_open(p0 ? (struct opaque **)t0 : 0);")
        assertContains(c, "for (jsize k = 0; k < n0; k++) a0[k] = (jlong)(intptr_t)t0[k];")
        // A const pointee is read, not written back.
        assertContains(c, "if (p0) (*env)->ReleaseLongArrayElements(env, p0, a0, JNI_ABORT);")
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
        assertContains(c, "void* d0 = p0 ? (*env)->GetDirectBufferAddress(env, p0) : 0;")
        assertContains(c, "sample_sort((void *)d0, p1, (compare_fn)(intptr_t)p2);")
    }

    @Test
    fun `the jextract generator binds what cinterop indexed, typedef'd records included`() {
        val jextract = System.getenv("JEXTRACT")
        assumeTrue(jextract != null && File(jextract).canExecute(), "Set JEXTRACT to a jextract executable")
        assumeInstalled()
        val projectDir = project(
            exportTask(GeneratorRequest.Jextract(library = "sample", jextract = File(jextract)), "ffm_sample", sampleDef, sampleDef.parentFile),
        )
        runner(projectDir, "generate_ffm_sample").build()

        val java = projectDir.resolve("build/generated/ffm_sample/java/sample")
        // cinterop folds `typedef union { … } bits_t` into a record; jextract only binds it as a typedef.
        assertTrue(java.resolve("bits_t.java").isFile, "bits_t, returned by sample_bits, must be bound")
        val header = java.resolve("sample.java").readText()
        listOf("sample_sum", "sample_name", "sample_bits", "sample_log", "SAMPLE_VERSION", "RED", "sample_global")
            .forEach { assertContains(header, it) }
    }

    @TestFactory
    fun `every installed Kotlin-Native version generates the same JNI bindings`(): List<DynamicTest> =
        KonanDistribution.supportedVersions
            .filter { it != KonanDistribution.defaultVersion && KonanDistribution.installed(it) != null }
            .map { version ->
                dynamicTest(version) {
                    assumeInstalled()
                    val expected = generated().getValue("jni_sample")
                    val actual = generated(version).getValue("jni_sample")
                    assertEquals(files(expected).keys, files(actual).keys)
                    files(expected).forEach { (path, text) -> assertEquals(text, files(actual).getValue(path), "$path on $version") }

                    val dump = generated(version).getValue("sample").resolve("index.txt").readText()
                    assertContains(dump, "field next: ptr typedef node_t `node_t *` offset=64 size=8 align=8")
                    assertContains(dump, "const SAMPLE_MASK: int32u(unsigned int) = 16")
                }
            }

    private fun files(directory: File): Map<String, String> =
        directory.walk().filter { it.isFile }.associate { it.relativeTo(directory).invariantSeparatorsPath to it.readText() }

    private fun dump(name: String): String {
        assumeInstalled()
        return generated().getValue(name).resolve("index.txt").readText()
    }

    private fun jni(): File {
        assumeInstalled()
        return generated().getValue("jni_sample")
    }

    private fun assumeInstalled() =
        assumeTrue(KonanDistribution.installed() != null, "Kotlin/Native ${KonanDistribution.defaultVersion} is not installed")

    private companion object {
        val sampleDef = File(checkNotNull(GenerateFromNativeIndexTest::class.java.classLoader.getResource("nativeindex/sample.def")).toURI())
        val mymathDef = File("examples/jvm/src/main/nativeInterop/mymath.def").absoluteFile
        val mymathHeaders = File("examples/jvm/native").absoluteFile

        private val builds = mutableMapOf<String, Map<String, File>>()

        /** One build per version runs every generator the tests read: output directory by task name. */
        fun generated(version: String = KonanDistribution.defaultVersion): Map<String, File> = builds.getOrPut(version) {
            val projectDir = project(
                exportTask(GeneratorRequest.Dump, "mymath", mymathDef, mymathHeaders),
                exportTask(GeneratorRequest.Dump, "sample", sampleDef, sampleDef.parentFile),
                exportTask(GeneratorRequest.Jni(stubLibrary = "samplestubs"), "jni_sample", sampleDef, sampleDef.parentFile),
            )
            val names = listOf("mymath", "sample", "jni_sample")
            runner(projectDir, *names.map { "generate_$it" }.toTypedArray(), version = version).build()
            names.associateWith { projectDir.resolve("build/generated/$it") }
        }

        fun exportTask(request: GeneratorRequest, name: String, def: File, includeDir: File) = """
            tasks.register<GenerateFromNativeIndexTask>("generate_$name") {
                defFile.set(file("${path(def)}"))
                includeDirs.from("${path(includeDir)}")
                target.set(NativeTarget.host)
                this.request.set(${kotlinSource(request)})
                outputDirectory.set(layout.buildDirectory.dir("generated/$name"))
            }
        """.trimIndent()

        /** [request] as build-script source. */
        fun kotlinSource(request: GeneratorRequest): String = when (request) {
            GeneratorRequest.Dump -> "GeneratorRequest.Dump"
            is GeneratorRequest.Jni ->
                "GeneratorRequest.Jni(stubLibrary = \"${request.stubLibrary}\", visibility = BindingVisibility.${request.visibility})"
            is GeneratorRequest.Jextract ->
                "GeneratorRequest.Jextract(library = \"${request.library}\", jextract = file(\"${request.jextract?.let(::path)}\"))"
        }

        fun project(vararg tasks: String): File {
            val projectDir = createTempDirectory("native-index").toFile()
            projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "native-index"""")
            projectDir.resolve("build.gradle.kts").writeText(
                (listOf(
                    "import io.github.lemcoder.model.NativeTarget",
                    "import io.github.lemcoder.index.GenerateFromNativeIndexTask",
                    "import io.github.lemcoder.model.BindingVisibility",
                    "import io.github.lemcoder.index.GeneratorRequest",
                    "plugins { id(\"io.github.lemcoder.konanplugin\") }",
                ) + tasks).joinToString("\n")
            )
            return projectDir
        }

        fun runner(projectDir: File, vararg tasks: String, version: String = KonanDistribution.defaultVersion): GradleRunner =
            testRunner()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments(*tasks, "--stacktrace", "-Pkonan.config.konanHome=${KonanDistribution.installed(version)?.home?.absolutePath}")

        fun path(file: File) = file.absolutePath.replace("\\", "/")
    }
}
