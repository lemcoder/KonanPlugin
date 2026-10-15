package io.github.lemcoder.bindings

import io.github.lemcoder.bindings.BindingContext
import io.github.lemcoder.bindings.bindingConsumers
import io.github.lemcoder.dsl.KonanExtension
import io.github.lemcoder.dsl.bindings.BindingSpec
import org.gradle.api.Action
import org.gradle.api.Project

/**
 * Every binding is prepared for every consumer now — conventions and eager wiring — and registered with
 * the consumers the project turns out to have once the build script has run.
 */
internal fun Project.registerBindings(konan: KonanExtension) {
    konan.bindings.all(object : Action<BindingSpec> {
        override fun execute(binding: BindingSpec) = registerBinding(konan, binding)
    })
}

private fun Project.registerBinding(konan: KonanExtension, binding: BindingSpec) {
    // The container fires before create()'s configure block runs: anything that needs the declared values
    // waits for afterEvaluate.
    binding.defFile.convention(layout.projectDirectory.file("src/nativeInterop/cinterop/${binding.name}.def"))

    val consumers = bindingConsumers(BindingContext(this, konan, binding))
    consumers.forEach { it.prepare() }
    afterEvaluate {
        consumers.filter { it.isUsed() }.forEach { it.register() }
    }
}
