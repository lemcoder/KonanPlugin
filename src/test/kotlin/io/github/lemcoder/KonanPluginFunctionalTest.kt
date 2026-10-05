package io.github.lemcoder

import io.github.lemcoder.index.PinnedKotlinNative
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Functional tests of the `konan { libraries; bindings }` DSL with Gradle TestKit. Most only configure
 * the task graph (`--dry-run`), so no real toolchain is needed; the multiplatform one runs cinterop.
 */
class KonanPluginFunctionalTest {

    private val projectDir = createTempDirectory("konan-testkit").toFile()
    private val host = hostKonanTarget()
    private val hostSuffix = host.name.replaceFirstChar { it.uppercase() }

    private fun runner(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args)

    /** A dummy Kotlin/Native home, so configuration that reads it resolves without a distribution. */
    private fun konanStub(): String = File(projectDir, "konan-stub").apply { mkdirs() }.absolutePath.replace("\\", "/")

    private fun write(plugins: String, konan: String) {
        File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
        File(projectDir, "mymath.def").writeText("headers = mymath.h\npackage = example\n")
        File(projectDir, "mymath.h").writeText("int my_add(int a, int b);\n")
        File(projectDir, "build.gradle.kts").writeText(
            "import org.jetbrains.kotlin.konan.target.KonanTarget\n\n" +
                "plugins {\n${plugins.trimIndent().prependIndent("    ")}\n}\n\n" +
                "konan {\n    konanHome.set(file(\"${konanStub()}\"))\n${konan.trimIndent().prependIndent("    ")}\n}\n"
        )
    }

    private val mymath = """
        val mymath = libraries.create("mymath") { targets(KonanTarget.${host.name.uppercase()}) }
        bindings {
            create("mymath") {
                defFile.set(file("mymath.def"))
                library(mymath)
            }
        }
    """.trimIndent()

    @Test
    fun `a library compiles from conventions alone`() {
        write("""id("io.github.lemcoder.konanplugin")""", """libraries { create("mymath") { targets(KonanTarget.MACOS_ARM64) } }""")

        val result = runner("compileNativeLibraryMymath", "--dry-run").build()

        assertTrue(":compileNativeLibraryMymath SKIPPED" in result.output, result.output)
    }

    @Test
    fun `a library built with zig sits behind the same task, with no konan clang`() {
        write(
            """id("io.github.lemcoder.konanplugin")""",
            """libraries { create("mymath") { targets(KonanTarget.MACOS_ARM64); zig { } } }""",
        )

        val result = runner("compileNativeLibraryMymath", "--dry-run").build()

        assertTrue(":zigBuildMymath SKIPPED" in result.output && ":compileNativeLibraryMymath SKIPPED" in result.output, result.output)
        assertFalse(":konanClangMymath" in result.output, result.output)
    }

    @Test
    fun `zig builds each target's archive where every provider puts it`() {
        val zig = System.getenv("ZIG")
        assumeTrue(zig != null && File(zig).canExecute(), "Set ZIG to a zig executable")
        File(projectDir, "native").mkdirs()
        File(projectDir, "native/mymath.c").writeText("int my_add(int a, int b) { return a + b; }\n")
        File(projectDir, "native/build.zig").writeText(
            """
            const std = @import("std");
            pub fn build(b: *std.Build) void {
                const target = b.standardTargetOptions(.{});
                const optimize = b.standardOptimizeOption(.{});
                const mod = b.createModule(.{ .target = target, .optimize = optimize, .link_libc = true });
                mod.addCSourceFiles(.{ .files = &.{"mymath.c"} });
                b.installArtifact(b.addLibrary(.{ .name = "mymath", .linkage = .static, .root_module = mod }));
            }
            """.trimIndent()
        )
        write(
            """id("io.github.lemcoder.konanplugin")""",
            """
            libraries {
                create("mymath") {
                    targets(KonanTarget.MACOS_ARM64, KonanTarget.LINUX_X64, KonanTarget.MINGW_X64)
                    zig { executable.set(file("${zig.replace("\\", "/")}")) }
                }
            }
            """,
        )

        runner("compileNativeLibraryMymath").build()

        listOf("macos_arm64", "linux_x64", "mingw_x64").forEach { target ->
            // Windows' mymath.lib too: consumers only ever look for lib<name>.a.
            assertTrue(File(projectDir, "build/konan/libraries/mymath/$target/libmymath.a").isFile, "archive for $target")
        }
    }

    @Test
    fun `without a Kotlin or Android plugin a binding links nothing and loads no KGP type`() {
        write("""id("io.github.lemcoder.konanplugin")""", mymath)

        val result = runner("tasks", "--all").build()

        assertFalse("linkJniMymath$hostSuffix" in result.output, "no JVM consumer, so no JNI link:\n${result.output}")
        assertFalse("cinterop" in result.output, "no multiplatform project, so no cinterop:\n${result.output}")
    }

    @Test
    fun `a JVM project links the JNI library for the host, after compiling the library`() {
        write("""kotlin("jvm")${"\n"}id("io.github.lemcoder.konanplugin")""", mymath)

        val result = runner("linkJniMymath", "--dry-run").withPluginClasspathAndKgp().build()

        listOf(":compileNativeLibraryMymath", ":generateJniMymath", ":linkJniMymath$hostSuffix").forEach {
            assertTrue("$it SKIPPED" in result.output, "expected $it in the graph:\n${result.output}")
        }
    }

    @Test
    fun `JNI targets default to the library's that a JVM can load`() {
        write(
            """kotlin("jvm")${"\n"}id("io.github.lemcoder.konanplugin")""",
            mymath.replace("targets(KonanTarget.${host.name.uppercase()})", "targets(KonanTarget.${host.name.uppercase()}, KonanTarget.ANDROID_ARM64, KonanTarget.LINUX_ARM64)"),
        )

        val result = runner("linkJniMymath", "--dry-run").withPluginClasspathAndKgp().build()

        assertTrue(":linkJniMymath$hostSuffix SKIPPED" in result.output, result.output)
        assertTrue(":linkJniMymathAndroid_arm64 SKIPPED" in result.output, result.output)
        if (host.name != "linux_arm64") assertFalse("linkJniMymathLinux_arm64" in result.output, "not loadable here:\n${result.output}")
    }

    @Test
    fun `an external CMake build replaces the plugin's JNI link`() {
        write(
            """kotlin("jvm")${"\n"}id("io.github.lemcoder.konanplugin")""",
            """
            bindings {
                create("mymath") {
                    defFile.set(file("mymath.def"))
                    jni {
                        cmake {
                            path.set(file("CMakeLists.txt"))
                            targets.add("mymath-jni")
                        }
                    }
                }
            }
            """,
        )
        File(projectDir, "CMakeLists.txt").writeText("cmake_minimum_required(VERSION 3.16)\n")

        val result = runner("linkJniMymath", "--dry-run").withPluginClasspathAndKgp().build()

        assertTrue(":cmakeConfigureMymath SKIPPED" in result.output && ":cmakeBuildMymath SKIPPED" in result.output, result.output)
        assertFalse(":linkJniMymath$hostSuffix" in result.output, "no plugin link with an external build:\n${result.output}")
    }

    @Test
    fun `ffm() adds jextract generation and a shared library for the host`() {
        write(
            """kotlin("jvm")${"\n"}id("io.github.lemcoder.konanplugin")""",
            mymath.replace("library(mymath)", "library(mymath)\n        ffm()"),
        )

        val result = runner("compileJava", "linkFfmMymath", "--dry-run").withPluginClasspathAndKgp().build()

        assertTrue(":generateFfmMymath SKIPPED" in result.output, "the Java sources come from jextract:\n${result.output}")
        assertTrue(":linkFfmMymath SKIPPED" in result.output, result.output)
    }

    @Test
    fun `a multiplatform project binds through KGP's cinterop, after compiling the library`() {
        assumeTrue(PinnedKotlinNative.home() != null, "Kotlin/Native ${PinnedKotlinNative.version} is not installed")
        // KMP's DSL names targets in camelCase: macos_arm64 -> macosArm64.
        val dslName = host.name.split('_').mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar { it.uppercase() } }.joinToString("")
        val targetSuffix = dslName.replaceFirstChar { it.uppercase() }

        File(projectDir, "native").mkdirs()
        File(projectDir, "native/mymath.h").writeText("int my_add(int a, int b);\n")
        File(projectDir, "native/mymath.c").writeText("#include \"mymath.h\"\nint my_add(int a, int b) { return a + b; }\n")
        File(projectDir, "src/${dslName}Main/kotlin").mkdirs()
        File(projectDir, "src/${dslName}Main/kotlin/Use.kt").writeText(
            "@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)\n\nfun sum(): Int = example.my_add(2, 3)\n"
        )
        write("""kotlin("multiplatform")${"\n"}id("io.github.lemcoder.konanplugin")""", mymath)
        // The real distribution, not the stub: this one compiles.
        File(projectDir, "build.gradle.kts").appendText(
            "\nkonan.konanHome.set(file(\"${PinnedKotlinNative.home()!!.absolutePath.replace("\\", "/")}\"))\n" +
                "kotlin { $dslName() }\nrepositories { mavenCentral() }\n"
        )
        File(projectDir, "mymath.def").writeText("headers = mymath.h\npackage = example\n")
        File(projectDir, "build.gradle.kts").writeText(
            File(projectDir, "build.gradle.kts").readText()
                .replace("defFile.set(file(\"mymath.def\"))", "defFile.set(file(\"mymath.def\"))\n            includeDirs.from(\"native\")")
        )

        val result = runner("compileKotlin$targetSuffix", "--stacktrace").withPluginClasspathAndKgp().build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileNativeLibraryMymath")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":cinteropMymath$targetSuffix")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin$targetSuffix")?.outcome)
        val order = result.tasks.map { it.path }
        assertTrue(order.indexOf(":compileNativeLibraryMymath") < order.indexOf(":cinteropMymath$targetSuffix"), "$order")
    }
}
