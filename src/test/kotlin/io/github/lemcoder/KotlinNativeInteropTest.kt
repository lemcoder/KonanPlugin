package io.github.lemcoder

import io.github.lemcoder.index.PinnedKotlinNative
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [io.github.lemcoder.interop.kotlinNativeInterop]: the adapter over KGP's own cinterop. */
class KotlinNativeInteropTest {
    private val projectDir = createTempDirectory("kotlin-native-interop").toFile()

    private fun runner(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args, "--stacktrace")

    /**
     * As [runner], with KGP on the plugin-under-test classpath: TestKit loads the plugin under test where
     * a plugin the test build applies on its own cannot be seen, which is not how a real build loads them.
     */
    private fun runnerWithKgp(vararg args: String): GradleRunner {
        val metadata = java.util.Properties().apply {
            KotlinNativeInteropTest::class.java.classLoader.getResourceAsStream("plugin-under-test-metadata.properties").use { load(it) }
        }
        val pluginClasspath = metadata.getProperty("implementation-classpath").split(File.pathSeparator)
        val kgp = System.getProperty("kgp.classpath").split(File.pathSeparator)
        return runner(*args).withPluginClasspath((pluginClasspath + kgp).filter { it.isNotEmpty() }.map(::File))
    }

    @Test
    fun `without the Kotlin plugin it declares nothing and loads no KGP type`() {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "no-kgp"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            import io.github.lemcoder.interop.kotlinNativeInterop

            plugins { id("io.github.lemcoder.konanplugin") }

            kotlinNativeInterop("mymath") {
                defFile.set(file("mymath.def"))
            }
            """.trimIndent()
        )
        val result = runner("tasks", "--all").build()
        assertTrue("cinterop" !in result.output, "no cinterop tasks without KGP:\n${result.output}")
    }

    @Test
    fun `KGP binds the def for the host target, after konanConfig built its archive`() {
        assumeTrue(PinnedKotlinNative.home() != null, "Kotlin/Native ${PinnedKotlinNative.version} is not installed")
        val host = hostKonanTarget()
        // KMP's DSL names targets in camelCase: macos_arm64 -> macosArm64.
        val dslName = host.name.split('_').mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar { it.uppercase() } }.joinToString("")

        projectDir.resolve("native").mkdirs()
        projectDir.resolve("native/mymath.h").writeText("int my_add(int a, int b);\n")
        projectDir.resolve("native/mymath.c").writeText("#include \"mymath.h\"\nint my_add(int a, int b) { return a + b; }\n")
        projectDir.resolve("mymath.def").writeText(
            "headers = mymath.h\nheaderFilter = mymath.h\npackage = mymath\nstaticLibraries = libmymath.a\n"
        )
        projectDir.resolve("src/${dslName}Main/kotlin").mkdirs()
        projectDir.resolve("src/${dslName}Main/kotlin/Use.kt").writeText(
            "@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)\n\nfun sum(): Int = mymath.my_add(2, 3)\n"
        )
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "kmp"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            import io.github.lemcoder.hostKonanTarget
            import io.github.lemcoder.interop.kotlinNativeInterop

            plugins {
                kotlin("multiplatform")
                id("io.github.lemcoder.konanplugin")
            }

            repositories { mavenCentral() }

            konanConfig {
                targets(hostKonanTarget())
                libName.set("mymath")
            }

            kotlin { $dslName() }

            kotlinNativeInterop("mymath") {
                defFile.set(file("mymath.def"))
                includeDirs.from("native")
            }
            """.trimIndent()
        )

        val targetSuffix = dslName.replaceFirstChar { it.uppercase() }
        val result = runnerWithKgp("compileKotlin$targetSuffix").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":runKonanClang")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":cinteropMymath$targetSuffix")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin$targetSuffix")?.outcome)
        val order = result.tasks.map { it.path }
        assertTrue(order.indexOf(":runKonanClang") < order.indexOf(":cinteropMymath$targetSuffix"), "archive before cinterop: $order")
    }
}
