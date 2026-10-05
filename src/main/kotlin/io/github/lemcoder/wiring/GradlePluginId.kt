package io.github.lemcoder.wiring

import org.gradle.api.Project

/** The other plugins whose presence decides how bindings are consumed. */
internal enum class GradlePluginId(val id: String) {
    KOTLIN_JVM("org.jetbrains.kotlin.jvm"),
    KOTLIN_MULTIPLATFORM("org.jetbrains.kotlin.multiplatform"),
    /** Every AGP application and library plugin; the KMP Android plugin does not apply it. */
    ANDROID_BASE("com.android.base"),
    ANDROID_KMP_LIBRARY("com.android.kotlin.multiplatform.library"),
}

internal fun Project.hasPlugin(plugin: GradlePluginId): Boolean = plugins.hasPlugin(plugin.id)

/** Runs [action] once [plugin] is applied, now if it already is. */
internal fun Project.withPlugin(plugin: GradlePluginId, action: () -> Unit) {
    plugins.withId(plugin.id) { action() }
}
