package io.github.lemcoder

import io.github.lemcoder.kotlin.KonanDistribution
import io.github.lemcoder.model.NativeTarget
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
    private val host = NativeTarget.host
    private val hostSuffix = host.konanName.replaceFirstChar { it.uppercase() }

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
            "import io.github.lemcoder.model.NativeTarget\nimport io.github.lemcoder.model.Executable\n\n" +
                "plugins {\n${plugins.trimIndent().prependIndent("    ")}\n}\n\n" +
                "konan {\n    konanHome.set(file(\"${konanStub()}\"))\n${konan.trimIndent().prependIndent("    ")}\n}\n"
        )
    }

    private val mymath = """
        val mymath = libraries.create("mymath") { targets(NativeTarget.${host.name}) }
        bindings {
            create("mymath") {
                defFile.set(file("mymath.def"))
                library(mymath)
            }
        }
    """.trimIndent()

    @Test
    fun `a library compiles from conventions alone`() {
        write("""id("io.github.lemcoder.konanplugin")""", """libraries { create("mymath") { targets(NativeTarget.MACOS_ARM64) } }""")

        val result = runner("compileNativeLibraryMymath", "--dry-run").build()

        assertTrue(":compileNativeLibraryMymath SKIPPED" in result.output, result.output)
    }

    @Test
    fun `a library built with zig sits behind the same task, with no konan clang`() {
        write(
            """id("io.github.lemcoder.konanplugin")""",
            """libraries { create("mymath") { targets(NativeTarget.MACOS_ARM64); zig { } } }""",
        )

        val result = runner("compileNativeLibraryMymath", "--dry-run").build()

        assertTrue(":zigBuildMymathMacos_arm64 SKIPPED" in result.output && ":compileNativeLibraryMymath SKIPPED" in result.output, result.output)
        assertFalse(":konanClangMymath" in result.output, result.output)
    }

    @Test
    fun `a library built by any build system lands where every provider puts it`() {
        assumeTrue(File("/bin/sh").canExecute(), "needs /bin/sh")
        write(
            """id("io.github.lemcoder.konanplugin")""",
            """
            libraries {
                create("mymath") {
                    targets(NativeTarget.LINUX_X64, NativeTarget.MINGW_X64)
                    external {
                        step {
                            executable.set(Executable.OnPath("sh"))
                            arguments.add("build.sh")
                            workingDirectory.set(layout.projectDirectory)
                        }
                        sources.from("build.sh")
                    }
                }
            }
            """,
        )
        // Writes straight into the output directory the contract names.
        File(projectDir, "build.sh").writeText(
            "printf '%s' \"${'$'}KONAN_TARGET\" > \"${'$'}KONAN_LIBRARY_OUTPUT_DIR/lib${'$'}KONAN_LIBRARY_NAME.a\"\n"
        )

        val first = runner("compileNativeLibraryMymath").build()
        val second = runner("compileNativeLibraryMymath").build()

        listOf("linux_x64", "mingw_x64").forEach { target ->
            assertEquals(target, File(projectDir, "build/konan/libraries/mymath/$target/libmymath.a").readText())
            assertEquals(TaskOutcome.UP_TO_DATE, second.task(":externalBuildMymath${target.replaceFirstChar { it.uppercase() }}")?.outcome, second.output)
        }
        assertFalse(":konanClangMymath" in first.output, first.output)
    }

    @Test
    fun `prebuilt archives are placed where every provider puts them`() {
        File(projectDir, "libs").mkdirs()
        File(projectDir, "libs/libmymath-linux.a").writeText("linux")
        File(projectDir, "libs/mymath.lib").writeText("windows")
        write(
            """id("io.github.lemcoder.konanplugin")""",
            """
            libraries {
                create("mymath") {
                    prebuilt {
                        archive(NativeTarget.LINUX_X64, file("libs/libmymath-linux.a"))
                        archive(NativeTarget.MINGW_X64, file("libs/mymath.lib"))
                    }
                }
            }
            """,
        )

        val result = runner("compileNativeLibraryMymath").build()

        assertEquals("linux", File(projectDir, "build/konan/libraries/mymath/linux_x64/libmymath.a").readText())
        assertEquals("windows", File(projectDir, "build/konan/libraries/mymath/mingw_x64/libmymath.a").readText())
        assertFalse(":konanClangMymath" in result.output, result.output)
    }

    @Test
    fun `a prebuilt library names the targets it has no archive for, and archives that do not exist`() {
        write(
            """id("io.github.lemcoder.konanplugin")""",
            """
            libraries {
                create("mymath") {
                    targets(NativeTarget.LINUX_X64, NativeTarget.MACOS_ARM64)
                    prebuilt { archive(NativeTarget.LINUX_X64, file("libs/absent.a")) }
                }
            }
            """,
        )
        assertTrue("has no archive for macos_arm64" in runner("compileNativeLibraryMymath").buildAndFail().output)

        File(projectDir, "build.gradle.kts").writeText(File(projectDir, "build.gradle.kts").readText().replace(", NativeTarget.MACOS_ARM64", ""))
        assertTrue("no such file" in runner("compileNativeLibraryMymath").buildAndFail().output)
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
                    targets(NativeTarget.MACOS_ARM64, NativeTarget.LINUX_X64, NativeTarget.MINGW_X64)
                    zig { executable.set(Executable.File(file("${zig.replace("\\", "/")}"))) }
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
            mymath.replace("targets(NativeTarget.${host.name})", "targets(NativeTarget.${host.name}, NativeTarget.ANDROID_ARM64, NativeTarget.LINUX_ARM64)"),
        )

        val result = runner("linkJniMymath", "--dry-run").withPluginClasspathAndKgp().build()

        assertTrue(":linkJniMymath$hostSuffix SKIPPED" in result.output, result.output)
        assertTrue(":linkJniMymathAndroid_arm64 SKIPPED" in result.output, result.output)
        if (host != NativeTarget.LINUX_ARM64) assertFalse("linkJniMymathLinux_arm64" in result.output, "not loadable here:\n${result.output}")
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
                            buildTargets.add("mymath-jni")
                        }
                    }
                }
            }
            """,
        )
        File(projectDir, "CMakeLists.txt").writeText("cmake_minimum_required(VERSION 3.16)\n")

        val result = runner("linkJniMymath", "--dry-run").withPluginClasspathAndKgp().build()

        assertTrue(":cmakeBuildMymath$hostSuffix SKIPPED" in result.output, result.output)
        assertFalse(":linkJniMymath$hostSuffix" in result.output, "no plugin link with an external build:\n${result.output}")
    }

    @Test
    fun `CMake receives the contract as cache entries and builds into the plugin's directory`() {
        val cmake = listOf("/opt/homebrew/bin/cmake", "/usr/local/bin/cmake", "/usr/bin/cmake").map(::File).firstOrNull { it.canExecute() }
        assumeTrue(cmake != null, "needs cmake")
        write(
            """kotlin("jvm")${"\n"}id("io.github.lemcoder.konanplugin")""",
            """
            bindings {
                create("mymath") {
                    defFile.set(file("mymath.def"))
                    jni { cmake { path.set(file("CMakeLists.txt")) } }
                }
            }
            """,
        )
        File(projectDir, "CMakeLists.txt").writeText(
            """
            cmake_minimum_required(VERSION 3.16)
            project(contract NONE)
            file(WRITE "${'$'}{CMAKE_LIBRARY_OUTPUT_DIRECTORY}/contract.txt" "${'$'}{KONAN_JNI_LIB_NAME}|${'$'}{KONAN_JNI_STUB_DIR}")
            """.trimIndent()
        )

        runner("linkJniMymath").withPluginClasspathAndKgp().build()

        val contract = File(projectDir, "build/konan/bindings/mymath/jni/lib/${host.konanName}/contract.txt").readText().split('|')
        assertTrue(contract[0].isNotEmpty(), "library name: $contract")
        assertTrue(File(contract[1]).isDirectory, "stub directory: $contract")
    }

    @Test
    fun `a generic external build runs its steps with the contract and its library is copied into place`() {
        assumeTrue(File("/bin/sh").canExecute(), "needs /bin/sh")
        write(
            """kotlin("jvm")${"\n"}id("io.github.lemcoder.konanplugin")""",
            """
            bindings {
                create("mymath") {
                    defFile.set(file("mymath.def"))
                    jni {
                        external {
                            step {
                                executable.set(Executable.File(file("/bin/sh")))
                                arguments.add("build.sh")
                                workingDirectory.set(layout.projectDirectory)
                            }
                            producedLibrary(NativeTarget.${host.name}, file("out/built"))
                        }
                    }
                }
            }
            """,
        )
        // Leaves its library somewhere of its own, as most build systems do.
        File(projectDir, "build.sh").writeText(
            "mkdir -p out && printf '%s|%s|%s' \"\$KONAN_TARGET\" \"\$KONAN_JNI_LIB_NAME\" \"\$KONAN_JNI_STUB_DIR\" > out/built\n"
        )

        val result = runner("linkJniMymath").withPluginClasspathAndKgp().build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":externalBuildMymath$hostSuffix")?.outcome, result.output)
        val libraries = File(projectDir, "build/konan/bindings/mymath/jni/lib/${host.konanName}").listFiles().orEmpty()
        val library = libraries.singleOrNull() ?: error("expected one library, got ${libraries.toList()}")
        val (target, name, stubDirectory) = library.readText().split('|')
        assertEquals(host.konanName, target)
        assertTrue(library.name.contains(name), "${library.name} is named after $name")
        assertTrue(File(stubDirectory).isDirectory, "stub directory: $stubDirectory")
    }

    @Test
    fun `an FFM library built by the project's own build lands where the bindings load it`() {
        assumeTrue(File("/bin/sh").canExecute(), "needs /bin/sh")
        write(
            """id("io.github.lemcoder.konanplugin")""",
            """
            bindings {
                create("mymath") {
                    defFile.set(file("mymath.def"))
                    ffm {
                        external {
                            step {
                                executable.set(Executable.OnPath("sh"))
                                arguments.add("build.sh")
                                workingDirectory.set(layout.projectDirectory)
                            }
                        }
                    }
                }
            }
            """,
        )
        // Writes straight into the output directory the contract names, under the name it gives.
        File(projectDir, "build.sh").writeText(
            "printf '%s' \"${'$'}KONAN_TARGET\" > \"${'$'}KONAN_FFM_OUTPUT_DIR/${host.sharedLibraryName("x").replace("x", "${'$'}KONAN_FFM_LIB_NAME")}\"\n"
        )

        val result = runner("externalBuildFfmMymath$hostSuffix").build()

        val library = File(projectDir, "build/konan/bindings/mymath/ffm/lib/${host.konanName}/${host.sharedLibraryName("mymath")}")
        assertEquals(host.konanName, library.readText(), result.output)
    }

    @Test
    fun `CMake builds the FFM library under the name the bindings load`() {
        val cmake = listOf("/opt/homebrew/bin/cmake", "/usr/local/bin/cmake", "/usr/bin/cmake").map(::File).firstOrNull { it.canExecute() }
        assumeTrue(cmake != null, "needs cmake")
        File(projectDir, "native").mkdirs()
        File(projectDir, "native/mymath.c").writeText("int my_add(int a, int b) { return a + b; }\n")
        File(projectDir, "native/CMakeLists.txt").writeText(
            """
            cmake_minimum_required(VERSION 3.16)
            project(mymath C)
            add_library(${'$'}{KONAN_FFM_LIB_NAME} SHARED mymath.c)
            """.trimIndent()
        )
        write(
            """id("io.github.lemcoder.konanplugin")""",
            """
            bindings {
                create("mymath") {
                    defFile.set(file("mymath.def"))
                    ffm { cmake { path.set(file("native/CMakeLists.txt")) } }
                }
            }
            """,
        )

        val result = runner("cmakeBuildFfmMymath$hostSuffix").build()

        val library = File(projectDir, "build/konan/bindings/mymath/ffm/lib/${host.konanName}/${host.sharedLibraryName("mymath")}")
        assertTrue(library.isFile, "expected $library:\n${result.output}")
    }

    @Test
    fun `a prebuilt FFM library keeps its own name`() {
        File(projectDir, "vendor").mkdirs()
        File(projectDir, "vendor/libggml-host.so").writeText("vendor")
        write(
            """id("io.github.lemcoder.konanplugin")""",
            """
            bindings {
                create("mymath") {
                    defFile.set(file("mymath.def"))
                    ffm {
                        libraryName.set("ggml")
                        prebuilt { sharedLibrary(NativeTarget.${host.name}, file("vendor/libggml-host.so")) }
                    }
                }
            }
            """,
        )

        runner("prebuiltFfmMymath").build()

        assertEquals("vendor", File(projectDir, "build/konan/bindings/mymath/ffm/lib/${host.konanName}/${host.sharedLibraryName("ggml")}").readText())
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
        assertTrue(":linkFfmMymath$hostSuffix SKIPPED" in result.output, "the host's library:\n${result.output}")
    }

    @Test
    fun `a multiplatform project binds through KGP's cinterop, after compiling the library`() {
        assumeTrue(KonanDistribution.pinned() != null, "Kotlin/Native ${KonanDistribution.pinnedVersion} is not installed")
        // KMP's DSL names targets in camelCase: macos_arm64 -> macosArm64.
        val dslName = host.konanName.split('_').mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar { it.uppercase() } }.joinToString("")
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
            "\nkonan.konanHome.set(file(\"${KonanDistribution.pinned()!!.home.absolutePath.replace("\\", "/")}\"))\n" +
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
