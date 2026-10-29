package io.github.lemcoder

import org.gradle.testkit.runner.GradleRunner
import java.io.File

/**
 * The plugin-under-test classpath with KGP on it. TestKit loads the plugin under test where a plugin the
 * test build applies on its own cannot see it — not how a real build loads them — so a fixture that
 * applies a Kotlin plugin gets KGP from here and requests it without a version. Fixtures that must prove
 * the plugin loads without KGP use plain `withPluginClasspath()`.
 */
internal fun GradleRunner.withPluginClasspathAndKgp(): GradleRunner {
    val metadata = java.util.Properties().apply {
        TestKitSupport::class.java.classLoader.getResourceAsStream("plugin-under-test-metadata.properties").use { load(it) }
    }
    val plugin = metadata.getProperty("implementation-classpath").split(File.pathSeparator)
    val kgp = System.getProperty("kgp.classpath").split(File.pathSeparator)
    return withPluginClasspath((plugin + kgp).filter { it.isNotEmpty() }.map(::File))
}

/**
 * A runner on the Gradle version `-PtestGradleVersion` names, else the one building the plugin: how the
 * suite checks the oldest Gradle a supported Kotlin version runs on.
 */
internal fun testRunner(): GradleRunner = GradleRunner.create().apply {
    System.getProperty("test.gradle.version")?.takeIf { it.isNotEmpty() }?.let(::withGradleVersion)
}

/**
 * Whether Kotlin knows the build host. Kotlin/Native has no Linux arm64 host: CI runs there on an
 * unofficial distribution, which the plugin can use, but KGP and `kotlin-native-utils` from Maven throw
 * "Unknown host target" there and build no Kotlin/Native target.
 */
internal val kotlinKnowsHost: Boolean by lazy {
    runCatching { org.jetbrains.kotlin.konan.target.HostManager.host }.isSuccess
}

private object TestKitSupport
