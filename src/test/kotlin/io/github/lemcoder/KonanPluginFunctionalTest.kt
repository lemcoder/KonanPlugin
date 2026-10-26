package io.github.lemcoder

import io.github.lemcoder.dsl.config.konanConfig
import io.github.lemcoder.kotlin.KonanDistribution
import io.github.lemcoder.model.NativeTarget
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Functional tests of the `konan { libraries; bindings }` DSL with Gradle TestKit. Most only configure
 * the task graph (`--dry-run`), so no real toolchain is needed; the multiplatform one runs cinterop.
 */
class KonanPluginFunctionalTest {

    private val projectDir = createTempDirectory("konan-testkit").toFile()
    private val host = NativeTarget.host
    private val hostSuffix = host.konanName.replaceFirstChar { it.uppercase() }

    private fun runner(vararg args: String) = testRunner()
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
                "konan {\n    config.konanHome.set(file(\"${konanStub()}\"))\n$installedTools${konan.trimIndent().prependIndent("    ")}\n}\n"
        )
    }

    /** make and cmake as this machine has them, for fixtures that run them; the plugin no longer looks. */
    private val installedTools: String = listOf("make", "cmake").joinToString("") { name ->
        val directories = System.getenv("PATH").orEmpty().split(File.pathSeparator) + listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
        directories.map { File(it, name) }.firstOrNull { it.canExecute() }
            ?.let { "    config.$name.set(file(\"${it.absolutePath.replace("\\", "/")}\"))\n" }.orEmpty()
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
    fun `config reads the build script, then Gradle properties, then the environment`() {
        write(
            """id("io.github.lemcoder.konanplugin")""",
            """
            config.jextract.set(file("/from/dsl/jextract"))
            libraries { create("mymath") { targets(NativeTarget.LINUX_X64); cmake { } } }
            """,
        )

        val output = runner("konanConfig", "-Pkonan.config.jextract=/from/property/jextract", "-Pkonan.config.zig=/from/property/zig")
            .withEnvironment(System.getenv() + mapOf("KONAN_ZIG" to "/from/env/zig", "KONAN_CARGO" to "/from/env/cargo"))
            .build().output

        assertContains(output, "jextract   /from/dsl/jextract (build script) — not needed here")
        assertContains(output, "zig        /from/property/zig (Gradle property konan.config.zig) — not needed here")
        assertContains(output, "cargo      /from/env/cargo (environment variable KONAN_CARGO) — not needed here")
        assertContains(output, "needed by :cmakeBuildMymathLinux_x64")
    }

    @Test
    fun `a task needing an unset path says how to set it`() {
        write("""id("io.github.lemcoder.konanplugin")""", """libraries { create("mymath") { targets(NativeTarget.${host.name}); cargo { } } }""")

        val output = runner("cargoBuildMymath$hostSuffix").withEnvironment(System.getenv() - "KONAN_CARGO").buildAndFail().output

        assertContains(output, "konan.config.cargo is not set — :cargoBuildMymath$hostSuffix needs it.")
        assertContains(output, "KONAN_CARGO=/path/to/cargo")
    }

    @Test
    fun `binding generation refuses a Kotlin-Native version it has no exporter for`() {
        write("""id("io.github.lemcoder.konanplugin")""", """bindings { create("mymath") { defFile.set(file("mymath.def")) } }""")
        // A distribution as far as the plugin looks: run_konan, and a compiler jar that says 1.9.24.
        File(konanStub(), "bin/run_konan").apply { parentFile.mkdirs(); writeText("#!/bin/sh\n"); setExecutable(true) }
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.IMPLEMENTATION_VERSION] = "1.9.24"
        }
        File(konanStub(), "konan/lib/kotlin-native-compiler-embeddable.jar").apply { parentFile.mkdirs() }
            .outputStream().use { JarOutputStream(it, manifest).close() }

        val output = runner("generateJniMymath").buildAndFail().output

        assertContains(output, "is Kotlin/Native 1.9.24, which this plugin cannot generate bindings with.")
        assertContains(output, "2.0.0, 2.0.10, 2.0.20, 2.0.21")
    }

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
            "printf '%s' \"${'$'}KONAN_TARGET\" > \"${'$'}KONAN_OUTPUT_DIR/lib${'$'}KONAN_LIB_NAME.a\"\n"
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
    fun `a library build that leaves several archives is merged into one`() {
        val tools = listOf("cc", "ar").map { tool -> listOf("/usr/bin", "/opt/homebrew/bin").map { File(it, tool) }.firstOrNull { it.canExecute() } }
        assumeTrue(tools.all { it != null } && File("/bin/sh").canExecute(), "needs cc, ar and /bin/sh")
        val (cc, ar) = tools.map { it!!.absolutePath }
        val home = realKonanHome()
        write(
            """kotlin("jvm")${"\n"}id("io.github.lemcoder.konanplugin")""",
            """
            config.konanHome.set(file("$home"))
            libraries {
                create("mymath") {
                    targets(NativeTarget.${host.name})
                    external {
                        step {
                            executable.set(Executable.File(file("/bin/sh")))
                            arguments.add("build.sh")
                            workingDirectory.set(layout.projectDirectory)
                        }
                    }
                }
            }
            bindings { create("mymath") { defFile.set(file("mymath.def")); library(libraries.getByName("mymath")) } }
            """,
        )
        // Two archives, as a CMake project with a dependency leaves them: my_add in libmymath.a needs
        // extra_value from libextra.a.
        File(projectDir, "a.c").writeText("int extra_value(void);\nint my_add(int a, int b) { return a + b + extra_value(); }\n")
        File(projectDir, "b.c").writeText("int extra_value(void) { return 0; }\n")
        File(projectDir, "build.sh").writeText(
            """
            set -e
            mkdir -p obj
            $cc -c -fPIC a.c -o obj/a.o && $ar rcs "${'$'}KONAN_OUTPUT_DIR/lib${'$'}KONAN_LIB_NAME.a" obj/a.o
            $cc -c -fPIC b.c -o obj/b.o && $ar rcs "${'$'}KONAN_OUTPUT_DIR/libextra.a" obj/b.o
            """.trimIndent()
        )

        // Linking the JNI library needs both: the merged archive must carry extra_value.
        val result = runner("linkJniMymath").withPluginClasspathAndKgp().build()

        val archive = File(projectDir, "build/konan/libraries/mymath/${host.konanName}/libmymath.a")
        val members = ProcessBuilder(ar, "t", archive.absolutePath).start().inputStream.bufferedReader().readText()
        assertTrue("a.o" in members && "b.o" in members, "expected both objects in $archive:\n$members\n${result.output}")
        assertTrue(File(projectDir, "build/konan/bindings/mymath/jni/lib/${host.konanName}/${host.sharedLibraryName("examplestubs")}").isFile, result.output)
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
                        library(NativeTarget.LINUX_X64, file("libs/libmymath-linux.a"))
                        library(NativeTarget.MINGW_X64, file("libs/mymath.lib"))
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
                    prebuilt { library(NativeTarget.LINUX_X64, file("libs/absent.a")) }
                }
            }
            """,
        )
        assertTrue("has no archive for macos_arm64" in runner("compileNativeLibraryMymath").buildAndFail().output)

        File(projectDir, "build.gradle.kts").writeText(File(projectDir, "build.gradle.kts").readText().replace(", NativeTarget.MACOS_ARM64", ""))
        assertTrue("no such file" in runner("compileNativeLibraryMymath").buildAndFail().output)
    }

    /** The installed Kotlin/Native distribution, for tests that really cross-compile with it. */
    private fun realKonanHome(): String {
        assumeTrue(KonanDistribution.installed() != null, "Kotlin/Native ${KonanDistribution.defaultVersion} is not installed")
        return KonanDistribution.installed()!!.home.absolutePath.replace("\\", "/")
    }

    @Test
    fun `make cross-compiles a library with Kotlin-Native's toolchain`() {
        assumeTrue(listOf("/usr/bin/make", "/opt/homebrew/bin/make").any { File(it).canExecute() }, "needs make")
        assumeTrue(host != NativeTarget.LINUX_X64, "needs a host other than linux_x64")
        val home = realKonanHome()
        File(projectDir, "native").mkdirs()
        File(projectDir, "native/mymath.c").writeText("int my_add(int a, int b) { return a + b; }\n")
        // Nothing but the contract and the compilers make is handed.
        File(projectDir, "native/Makefile").writeText(
            "OUT := $(KONAN_OUTPUT_DIR)\n" +
                "all:\n\t$(CC) -c -fPIC mymath.c -o $(OUT)/mymath.o\n\t$(AR) rcs $(OUT)/lib$(KONAN_LIB_NAME).a $(OUT)/mymath.o\n"
        )
        write(
            """id("io.github.lemcoder.konanplugin")""",
            """
            config.konanHome.set(file("$home"))
            libraries {
                create("mymath") {
                    targets(NativeTarget.LINUX_X64)
                    make { directory.set(file("native")) }
                }
            }
            """,
        )

        val result = runner("compileNativeLibraryMymath").build()

        val archive = File(projectDir, "build/konan/libraries/mymath/linux_x64/libmymath.a").readBytes()
        assertTrue(archive.copyOfRange(0, 8).decodeToString() == "!<arch>\n", result.output)
        // An x86-64 ELF object: e_machine 0x3e, 18 bytes into the member.
        val elf = archive.indexOfSequence(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        assertTrue(elf > 0 && archive[elf + 18] == 0x3e.toByte(), "expected an x86-64 ELF member:\n${result.output}")
    }

    @Test
    fun `the jar builds only the JVM targets' JNI libraries, not Android's`() {
        val hostAndAndroid = mymath.replace("targets(NativeTarget.${host.name})", "targets(NativeTarget.${host.name}, NativeTarget.ANDROID_ARM64)")
        write("""kotlin("jvm")${"\n"}id("io.github.lemcoder.konanplugin")""", hostAndAndroid)

        // The plugin's own link, per target.
        val own = runner("jar", "--dry-run").withPluginClasspathAndKgp().build().output
        assertTrue(":linkJniMymath$hostSuffix SKIPPED" in own && ":bundleJniMymath SKIPPED" in own, own)
        assertFalse(":linkJniMymathAndroid_arm64 SKIPPED" in own, own)

        // The project's own build, per target block.
        File(projectDir, "build.gradle.kts").appendText(
            """
            konan.bindings.named("mymath") {
                jni { cmake { path.set(file("CMakeLists.txt")); target(NativeTarget.${host.name}) { }; target(NativeTarget.ANDROID_ARM64) { } } }
            }
            """.trimIndent()
        )
        val cmake = runner("jar", "--dry-run").withPluginClasspathAndKgp().build().output
        assertTrue(":cmakeBuildJniMymath$hostSuffix SKIPPED" in cmake, cmake)
        assertFalse(":cmakeBuildJniMymathAndroid_arm64 SKIPPED" in cmake, cmake)
    }

    @Test
    fun `a CMake preset configures into the build directory the target block sets`() {
        val cmake = listOf("/opt/homebrew/bin/cmake", "/usr/local/bin/cmake", "/usr/bin/cmake").map(::File).firstOrNull { it.canExecute() }
        assumeTrue(cmake != null, "needs cmake")
        File(projectDir, "native").mkdirs()
        File(projectDir, "native/CMakeLists.txt").writeText(
            """
            cmake_minimum_required(VERSION 3.21)
            project(mymath NONE)
            file(WRITE "${'$'}{CMAKE_ARCHIVE_OUTPUT_DIRECTORY}/lib${'$'}{KONAN_LIB_NAME}.a" "archive")
            """.trimIndent()
        )
        File(projectDir, "native/CMakePresets.json").writeText(
            """{ "version": 3, "configurePresets": [ { "name": "host", "binaryDir": "${'$'}{sourceDir}/build/host" } ] }"""
        )
        write(
            """id("io.github.lemcoder.konanplugin")""",
            """
            libraries {
                create("mymath") {
                    targets(NativeTarget.${host.name})
                    cmake {
                        target(NativeTarget.${host.name}) {
                            preset.set("host")
                            buildDirectory.set(layout.buildDirectory.dir("cmake/mine"))
                        }
                    }
                }
            }
            """,
        )

        val result = runner("compileNativeLibraryMymath").build()

        // Configure and build agree on the directory that was set; the preset's own is never created.
        assertTrue(File(projectDir, "build/cmake/mine/CMakeCache.txt").isFile, result.output)
        assertFalse(File(projectDir, "native/build/host").exists(), result.output)
        assertTrue(File(projectDir, "build/konan/libraries/mymath/${host.konanName}/libmymath.a").isFile, result.output)
    }

    @Test
    fun `CMake builds a library's archive for the host and, cross-compiled, for linux_x64`() {
        val cmake = listOf("/opt/homebrew/bin/cmake", "/usr/local/bin/cmake", "/usr/bin/cmake").map(::File).firstOrNull { it.canExecute() }
        assumeTrue(cmake != null, "needs cmake")
        assumeTrue(host != NativeTarget.LINUX_X64, "needs a host other than linux_x64")
        val home = realKonanHome()
        File(projectDir, "native").mkdirs()
        File(projectDir, "native/mymath.c").writeText("int my_add(int a, int b) { return a + b; }\n")
        File(projectDir, "native/CMakeLists.txt").writeText(
            """
            cmake_minimum_required(VERSION 3.16)
            project(mymath C)
            add_library(${'$'}{KONAN_LIB_NAME} STATIC mymath.c)
            """.trimIndent()
        )
        write(
            """id("io.github.lemcoder.konanplugin")""",
            """
            config.konanHome.set(file("$home"))
            libraries {
                create("mymath") {
                    targets(NativeTarget.${host.name}, NativeTarget.LINUX_X64)
                    cmake { }
                }
            }
            """,
        )

        val result = runner("compileNativeLibraryMymath").build()

        assertTrue(File(projectDir, "build/konan/libraries/mymath/${host.konanName}/libmymath.a").isFile, result.output)
        val linux = File(projectDir, "build/konan/libraries/mymath/linux_x64/libmymath.a").readBytes()
        val elf = linux.indexOfSequence(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        assertTrue(elf > 0 && linux[elf + 18] == 0x3e.toByte(), "expected an x86-64 ELF member:\n${result.output}")
    }

    @Test
    fun `CMake cross-compiles and links a JNI library through Kotlin-Native's toolchain file`() {
        val cmake = listOf("/opt/homebrew/bin/cmake", "/usr/local/bin/cmake", "/usr/bin/cmake").map(::File).firstOrNull { it.canExecute() }
        assumeTrue(cmake != null, "needs cmake")
        assumeTrue(host != NativeTarget.LINUX_X64, "needs a host other than linux_x64")
        val home = realKonanHome()
        File(projectDir, "native").mkdirs()
        File(projectDir, "native/mymath.c").writeText("int my_add(int a, int b) { return a + b; }\n")
        File(projectDir, "native/CMakeLists.txt").writeText(
            """
            cmake_minimum_required(VERSION 3.16)
            project(mymath C)
            add_library(${'$'}{KONAN_LIB_NAME} SHARED mymath.c)
            """.trimIndent()
        )
        write(
            """kotlin("jvm")${"\n"}id("io.github.lemcoder.konanplugin")""",
            """
            config.konanHome.set(file("$home"))
            bindings {
                create("mymath") {
                    defFile.set(file("mymath.def"))
                    jni { cmake { path.set(file("native/CMakeLists.txt")); target(NativeTarget.LINUX_X64) { } } }
                }
            }
            """,
        )

        val result = runner("cmakeBuildJniMymathLinux_x64").withPluginClasspathAndKgp().build()

        val library = File(projectDir, "build/konan/bindings/mymath/jni/lib/linux_x64").listFiles().orEmpty().single { it.name.endsWith(".so") }
        val bytes = library.readBytes()
        assertTrue(bytes.copyOfRange(1, 4).decodeToString() == "ELF" && bytes[18] == 0x3e.toByte(), "expected an x86-64 ELF library:\n${result.output}")
    }

    private fun ByteArray.indexOfSequence(sequence: ByteArray): Int =
        (0..size - sequence.size).firstOrNull { start -> sequence.indices.all { this[start + it] == sequence[it] } } ?: -1

    @Test
    fun `cargo builds a Rust crate into the library's archive`() {
        val cargo = File(System.getProperty("user.home"), ".cargo/bin/cargo")
        assumeTrue(cargo.canExecute(), "needs cargo")
        File(projectDir, "native/src").mkdirs()
        File(projectDir, "native/Cargo.toml").writeText(
            "[package]\nname = \"my-math\"\nversion = \"0.1.0\"\nedition = \"2021\"\n\n[lib]\ncrate-type = [\"staticlib\"]\n"
        )
        File(projectDir, "native/src/lib.rs").writeText("#[no_mangle]\npub extern \"C\" fn my_add(a: i32, b: i32) -> i32 { a + b }\n")
        write(
            """id("io.github.lemcoder.konanplugin")""",
            """
            config.cargo.set(file("${cargo.absolutePath.replace("\\", "/")}"))
            libraries {
                create("mymath") {
                    targets(NativeTarget.${host.name})
                    cargo {
                        crateName.set("my-math")
                    }
                }
            }
            """,
        )

        val result = runner("compileNativeLibraryMymath").build()

        // Cargo names it libmy_math.a; consumers find the library's own name.
        val archive = File(projectDir, "build/konan/libraries/mymath/${host.konanName}/libmymath.a")
        assertTrue(archive.isFile && archive.readBytes().copyOfRange(0, 8).decodeToString() == "!<arch>\n", result.output)
        assertTrue(":cargoBuildMymath$hostSuffix" in result.output, result.output)
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
            config.zig.set(file("${zig.replace("\\", "/")}"))
            libraries {
                create("mymath") {
                    targets(NativeTarget.MACOS_ARM64, NativeTarget.LINUX_X64, NativeTarget.MINGW_X64)
                    zig { }
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
    fun `an Android link of C++ carries libc++ and the unwinder, and leaves nothing undefined`() {
        val home = realKonanHome()
        File(projectDir, "native").mkdirs()
        // operator new, std::vector and a throw: libc++, libc++abi and _Unwind_* — none of which Android provides.
        File(projectDir, "native/mymath.cpp").writeText(
            """
            #include <numeric>
            #include <stdexcept>
            #include <vector>
            extern "C" int my_add(int a, int b) {
                std::vector<int> values{a, b};
                try { if (values.empty()) throw std::runtime_error("empty"); } catch (const std::exception &) { return 0; }
                return std::accumulate(values.begin(), values.end(), 0);
            }
            """.trimIndent()
        )
        val android = mymath.replace("targets(NativeTarget.${host.name})", "targets(NativeTarget.ANDROID_ARM64)")
        write("""kotlin("jvm")${"\n"}id("io.github.lemcoder.konanplugin")""", "config.konanHome.set(file(\"$home\"))\n$android")

        // The link uses --no-undefined: without the runtime it fails here, not when the app loads the library.
        val result = runner("linkJniMymathAndroid_arm64").withPluginClasspathAndKgp().build()

        assertTrue(File(projectDir, "build/konan/bindings/mymath/jni/jniLibs/arm64-v8a/libexamplestubs.so").isFile, result.output)
    }

    @Test
    fun `the JNI links for the host and Android depend on generating the stub they compile`() {
        val home = realKonanHome()
        File(projectDir, "native").mkdirs()
        File(projectDir, "native/mymath.c").writeText("int my_add(int a, int b) { return a + b; }\n")
        val hostAndAndroid = mymath.replace("targets(NativeTarget.${host.name})", "targets(NativeTarget.${host.name}, NativeTarget.ANDROID_ARM64)")
        write("""kotlin("jvm")${"\n"}id("io.github.lemcoder.konanplugin")""", "config.konanHome.set(file(\"$home\"))\n$hostAndAndroid")

        // Executed, not a dry run: Gradle rejects a link that reads the stub without depending on its
        // generator only when both run — and here only with more than one link.
        val result = runner("linkJniMymath").withPluginClasspathAndKgp().build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateJniMymath")?.outcome, result.output)
        assertTrue(File(projectDir, "build/konan/bindings/mymath/jni/lib/${host.konanName}/${host.sharedLibraryName("examplestubs")}").isFile, result.output)
        assertTrue(File(projectDir, "build/konan/bindings/mymath/jni/jniLibs/arm64-v8a/libexamplestubs.so").isFile, result.output)
    }

    @Test
    fun `a JVM project's jar carries the JNI library unless told not to`() {
        write("""kotlin("jvm")${"\n"}id("io.github.lemcoder.konanplugin")""", mymath)

        val bundled = runner("jar", "--dry-run").withPluginClasspathAndKgp().build()
        listOf(":linkJniMymath$hostSuffix", ":bundleJniMymath", ":processResources", ":jar").forEach {
            assertTrue("$it SKIPPED" in bundled.output, "expected $it before the jar:\n${bundled.output}")
        }

        File(projectDir, "build.gradle.kts").appendText("konan.bindings.named(\"mymath\") { jni.bundleInJar.set(false) }\n")
        val plain = runner("jar", "--dry-run").withPluginClasspathAndKgp().build()
        assertFalse(":bundleJniMymath" in plain.output, plain.output)
    }

    @Test
    fun `per-target DSL options reach the tasks`() {
        write(
            """kotlin("jvm")${"\n"}id("io.github.lemcoder.konanplugin")""",
            """
            val mymath = libraries.create("mymath") { androidTargets() }
            bindings {
                create("mymath") {
                    defFile.set(file("mymath.def"))
                    library(mymath)
                    jni {
                        targets(NativeTarget.ANDROID_ARM64, NativeTarget.ANDROID_X64)
                        linkerOptions.add("-lcommon")
                        linkerOptionsFor(NativeTarget.ANDROID_ARM64, "-larm64only")
                    }
                }
            }
            """,
        )
        // Read back what the tasks were given, through their declared inputs.
        File(projectDir, "build.gradle.kts").appendText(
            """
            afterEvaluate {
                println("TARGETS=" + konan.libraries.getByName("mymath").targets.get().map { it.konanName }.sorted())
                listOf("Android_arm64", "Android_x64").forEach {
                    println("LINK_${'$'}it=" + tasks.getByName("linkJniMymath${'$'}it").inputs.properties["additionalLinkerArgs"])
                }
            }
            """.trimIndent()
        )

        val output = runner("help").withPluginClasspathAndKgp().build().output

        assertTrue("TARGETS=[android_arm32, android_arm64, android_x64, android_x86]" in output, output)
        assertTrue("LINK_Android_arm64=[-lcommon, -larm64only]" in output, output)
        assertTrue("LINK_Android_x64=[-lcommon]" in output, output)
    }

    @Test
    fun `an external step gets its per-target arguments`() {
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
                            argumentsFor(NativeTarget.LINUX_X64, "A=1", "B=2")
                            argumentsFor(NativeTarget.MINGW_X64, provider { listOf("C=3") })
                            workingDirectory.set(layout.projectDirectory)
                        }
                    }
                }
            }
            """,
        )
        // Records the arguments it was run with as the "archive".
        File(projectDir, "build.sh").writeText("printf '%s ' \"$@\" > \"${'$'}KONAN_OUTPUT_DIR/lib${'$'}KONAN_LIB_NAME.a\"\n")

        runner("compileNativeLibraryMymath").build()

        assertEquals("A=1 B=2 ", File(projectDir, "build/konan/libraries/mymath/linux_x64/libmymath.a").readText())
        assertEquals("C=3 ", File(projectDir, "build/konan/libraries/mymath/mingw_x64/libmymath.a").readText())
    }

    @Test
    fun `a multiplatform jvm() target compiles after its JNI bindings are generated`() {
        write("""kotlin("multiplatform")${"\n"}id("io.github.lemcoder.konanplugin")""", mymath)
        File(projectDir, "build.gradle.kts").appendText("kotlin { jvm() }\nrepositories { mavenCentral() }\n")

        val result = runner("compileKotlinJvm", "--dry-run").withPluginClasspathAndKgp().build()

        assertTrue(":generateJniMymath SKIPPED" in result.output, result.output)
    }

    @Test
    fun `ffm() serves a multiplatform project's jvm() targets in place of JNI`() {
        write("""kotlin("multiplatform")${"\n"}id("io.github.lemcoder.konanplugin")""", mymath.replace("library(mymath)", "library(mymath)\n        ffm()"))
        File(projectDir, "build.gradle.kts").appendText("kotlin { jvm() }\nrepositories { mavenCentral() }\n")

        val result = runner("jvmJar", "--dry-run").withPluginClasspathAndKgp().build()

        listOf(":generateFfmMymath", ":linkFfmMymath$hostSuffix", ":bundleFfmMymath").forEach {
            assertTrue("$it SKIPPED" in result.output, "expected $it:\n${result.output}")
        }
        // jvm()'s own Java source set from Kotlin 2.1.20; before, withJava()'s, which is main.
        val compileJava = listOf(":compileJvmMainJava", ":compileJava").firstOrNull { "$it SKIPPED" in result.output }
        assertTrue(compileJava != null && result.output.indexOf(compileJava) > result.output.indexOf(":generateFfmMymath"), result.output)
        assertFalse(":generateJniMymath" in result.output, "JNI is replaced on jvm():\n${result.output}")
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

        assertTrue(":cmakeBuildJniMymath$hostSuffix SKIPPED" in result.output, result.output)
        assertFalse(":linkJniMymath$hostSuffix" in result.output, "no plugin link with an external build:\n${result.output}")
    }

    @Test
    fun `CMake receives the contract as cache entries and builds into the plugin's directory`() {
        val cmake = listOf("/opt/homebrew/bin/cmake", "/usr/local/bin/cmake", "/usr/bin/cmake").map(::File).firstOrNull { it.canExecute() }
        assumeTrue(cmake != null, "needs cmake")
        // The generator runs in the real distribution.
        val home = realKonanHome()
        write(
            """kotlin("jvm")${"\n"}id("io.github.lemcoder.konanplugin")""",
            """
            config.konanHome.set(file("$home"))
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
            file(WRITE "${'$'}{CMAKE_LIBRARY_OUTPUT_DIRECTORY}/contract.txt" "${'$'}{KONAN_LIB_NAME}|${'$'}{KONAN_JNI_STUB_DIR}|${'$'}{KONAN_JNI_INCLUDE_DIR}")
            """.trimIndent()
        )

        runner("linkJniMymath").withPluginClasspathAndKgp().build()

        val contract = File(projectDir, "build/konan/bindings/mymath/jni/lib/${host.konanName}/contract.txt").readText().split('|')
        assertTrue(contract[0].isNotEmpty(), "library name: $contract")
        assertTrue(File(contract[1]).isDirectory, "stub directory: $contract")
        assertTrue(File(contract[2], "jni.h").isFile, "the plugin's jni.h, no JDK: $contract")
    }

    @Test
    fun `a generic external build runs its steps with the contract and its library is copied into place`() {
        assumeTrue(File("/bin/sh").canExecute(), "needs /bin/sh")
        val home = realKonanHome()
        write(
            """kotlin("jvm")${"\n"}id("io.github.lemcoder.konanplugin")""",
            """
            config.konanHome.set(file("$home"))
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
            "mkdir -p out && printf '%s|%s|%s' \"\$KONAN_TARGET\" \"\$KONAN_LIB_NAME\" \"\$KONAN_JNI_STUB_DIR\" > out/built\n"
        )

        val result = runner("linkJniMymath").withPluginClasspathAndKgp().build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":externalBuildJniMymath$hostSuffix")?.outcome, result.output)
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
            "printf '%s' \"${'$'}KONAN_TARGET\" > \"${'$'}KONAN_OUTPUT_DIR/${host.sharedLibraryName("x").replace("x", "${'$'}KONAN_LIB_NAME")}\"\n"
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
            add_library(${'$'}{KONAN_LIB_NAME} SHARED mymath.c)
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
                        prebuilt { library(NativeTarget.${host.name}, file("vendor/libggml-host.so")) }
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
        assumeTrue(KonanDistribution.installed() != null, "Kotlin/Native ${KonanDistribution.defaultVersion} is not installed")
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
            "\nkonan.config.konanHome.set(file(\"${KonanDistribution.installed()!!.home.absolutePath.replace("\\", "/")}\"))\n" +
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
