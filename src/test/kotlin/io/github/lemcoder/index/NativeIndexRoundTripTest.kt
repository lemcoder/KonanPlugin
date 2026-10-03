package io.github.lemcoder.index

import org.gradle.testkit.runner.GradleRunner
import org.jetbrains.kotlin.native.interop.indexer.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Indexes headers with cinterop's own indexer, through [ExportNativeIndexTask] in a TestKit build, then
 * reads the file back into the plugin's copy of the model.
 *
 * Skipped when the pinned Kotlin/Native distribution is not installed; any Kotlin Multiplatform build of
 * [NativeIndexer.kotlinNativeVersion] downloads it.
 */
class NativeIndexRoundTripTest {

    @Test
    fun `mymath functions come back with their signatures`() {
        val index = index(example.resolve("src/main/nativeInterop/mymath.def")).first

        val functions = index.functions.associateBy { it.name }
        assertEquals(setOf("my_add", "my_scale"), functions.keys)

        val add = functions.getValue("my_add")
        assertEquals(listOf("a", "b"), add.parameters.map { it.name })
        add.parameters.forEach { assertInt(it.type, bits = 32) }
        assertInt(add.returnType, bits = 32)

        val scale = functions.getValue("my_scale")
        assertEquals(FloatingType(8, "double"), scale.parameters.single().type)
        assertEquals(FloatingType(8, "double"), scale.returnType)
    }

    @Test
    fun `the index read back renders exactly as the one the exporter built`() {
        val (index, exporterDump) = index(sampleDef)
        assertEquals(exporterDump, dumpNativeIndex(index))
    }

    @Test
    fun `a struct that points to itself resolves to the same declaration`() {
        val index = index(sampleDef).first
        val node = index.structs.single { it.spelling == "struct node" }
        val fields = node.def!!.fields.associateBy { it.name }

        val prev = assertIs<PointerType>(fields.getValue("prev").type)
        assertSame(node, assertIs<RecordType>(prev.pointeeType).decl)

        val next = assertIs<PointerType>(fields.getValue("next").type)
        val nodeT = assertIs<Typedef>(next.pointeeType).def
        assertEquals("node_t", nodeT.name)
        assertSame(node, assertIs<RecordType>(nodeT.aliased).decl)
        assertSame(nodeT, index.typedefs.single { it.name == "node_t" })
    }

    @Test
    fun `macro constants keep their values`() {
        val constants = index(sampleDef).first.macroConstants.associateBy { it.name }
        assertEquals(3L, assertIs<IntegerConstantDef>(constants.getValue("SAMPLE_VERSION")).value)
        assertEquals(16L, assertIs<IntegerConstantDef>(constants.getValue("SAMPLE_MASK")).value)
        assertEquals(3.14159, assertIs<FloatingConstantDef>(constants.getValue("SAMPLE_PI")).value)
        assertEquals("sample", assertIs<StringConstantDef>(constants.getValue("SAMPLE_NAME")).value)
    }

    /** Indexes [def], returning the index read back and the exporter's own dump of what it built. */
    private fun index(def: File): Pair<NativeIndex, String> {
        assumeTrue(NativeIndexer.pinnedKonanHome() != null, "Kotlin/Native ${NativeIndexer.kotlinNativeVersion} is not installed")
        val (json, dump) = exported.getValue(def.nameWithoutExtension)
        return NativeIndexReader.read(json) to dump.readText()
    }

    private companion object {
        private val sampleDef = File(checkNotNull(NativeIndexRoundTripTest::class.java.classLoader.getResource("nativeindex/sample.def")).toURI())
        private val example = File("examples/jvm").absoluteFile

        /** One build exports every header the tests use: (json, dump) by .def name. */
        val exported: Map<String, Pair<File, File>> by lazy {
            val projectDir = createTempDirectory("native-index").toFile()
            fun path(f: File) = f.absolutePath.replace("\\", "/")
            fun exportTask(name: String, def: File, includeDir: File) = """
                tasks.register<ExportNativeIndexTask>("export_$name") {
                    defFile.set(file("${path(def)}"))
                    includeDirs.from("${path(includeDir)}")
                    target.set(hostKonanTarget())
                    outputFile.set(layout.buildDirectory.file("$name.json"))
                    dumpFile.set(layout.buildDirectory.file("$name.txt"))
                }
            """.trimIndent()
            projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "native-index"""")
            projectDir.resolve("build.gradle.kts").writeText(
                listOf(
                    "import io.github.lemcoder.hostKonanTarget",
                    "import io.github.lemcoder.index.ExportNativeIndexTask",
                    "plugins { id(\"io.github.lemcoder.konanplugin\") }",
                    exportTask("mymath", example.resolve("src/main/nativeInterop/mymath.def"), example.resolve("native")),
                    exportTask("sample", sampleDef, sampleDef.parentFile),
                ).joinToString("\n")
            )
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("export_mymath", "export_sample", "--stacktrace")
                .build()
            listOf("mymath", "sample").associateWith {
                projectDir.resolve("build/$it.json") to projectDir.resolve("build/$it.txt")
            }
        }
    }

    private fun assertInt(type: Type, bits: Int) {
        val int = assertIs<IntegerType>(type)
        assertEquals(bits, int.size * 8)
        assertEquals(true, int.isSigned)
    }

}
