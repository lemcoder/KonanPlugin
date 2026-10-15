package io.github.lemcoder.dsl.bindings

import io.github.lemcoder.dsl.bindings.ffm.FfmConsumer
import io.github.lemcoder.dsl.bindings.jni.JniConsumer
import io.github.lemcoder.dsl.KonanExtension
import io.github.lemcoder.model.DefFile
import io.github.lemcoder.model.NativeArtifacts
import io.github.lemcoder.wiring.GradlePluginId
import io.github.lemcoder.wiring.hasPlugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider

/**
 * One way a project uses a binding — Kotlin/Native, JNI, FFM. A consumer is found from the project's
 * plugins and the binding's settings, never declared, and takes the library only as [NativeArtifacts]:
 * it never learns which provider built it.
 */
internal interface BindingConsumer {
    /**
     * Runs when the binding is added, before its configure block: conventions, and wiring Gradle or AGP
     * only accept at configuration time. Must not read the binding's declared values.
     */
    fun prepare() {}

    /** Whether the project consumes the binding this way. Called once the build script has run. */
    fun isUsed(): Boolean

    /** Registers this consumer's tasks and wiring. Called once the build script has run, if [isUsed]. */
    fun register()
}

/** Every consumer the plugin knows, for one binding. A new kind of consumer is one more entry here. */
internal fun bindingConsumers(context: BindingContext): List<BindingConsumer> =
    listOf(KotlinNativeConsumer(context), JniConsumer(context), FfmConsumer(context))

/** What every consumer of one binding shares. */
internal class BindingContext(val project: Project, val konan: KonanExtension, val binding: BindingSpec) {
    val bindingName: String = binding.name
    val buildDirectory = project.layout.buildDirectory

    /** The archives of the library the binding names, once the build script has been evaluated. */
    fun artifacts(): NativeArtifacts? = binding.library.orNull?.let { NativeArtifacts.of(project, it) }

    /** The package the bindings go in: the binding's, else the `.def`'s own. */
    val packageName: Provider<String> = binding.packageName.orElse(
        project.provider { binding.defFile.orNull?.asFile?.takeIf { it.isFile }?.let { DefFile.parse(it).packageName } }
    )

    fun hasPlugin(id: GradlePluginId): Boolean = project.hasPlugin(id)
}
