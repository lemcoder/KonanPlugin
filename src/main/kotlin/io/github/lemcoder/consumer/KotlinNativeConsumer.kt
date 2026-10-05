package io.github.lemcoder.consumer

import io.github.lemcoder.wiring.GradlePluginId
import io.github.lemcoder.wiring.KgpWiring

/**
 * Kotlin/Native targets, through KGP's own cinterop: the adapter declares the binding on each target's
 * main compilation and embeds the library's archive in the klib.
 */
internal class KotlinNativeConsumer(private val context: BindingContext) : BindingConsumer {
    override fun isUsed(): Boolean = context.hasPlugin(GradlePluginId.KOTLIN_MULTIPLATFORM)

    override fun register() = KgpWiring.declareCinterops(context.project, context.binding, context.artifacts())
}
