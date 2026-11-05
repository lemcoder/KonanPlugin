package io.github.lemcoder.android

import io.github.lemcoder.dsl.config.ConfigValue
import java.util.concurrent.ConcurrentHashMap
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.logging.Logger
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Says that `ANDROID_NDK_HOME` lost to the Android Gradle plugin's NDK (#19), once per build however many
 * tasks use the NDK. Tasks ask as they run: a warning at configuration would not repeat when the
 * configuration cache is reused, and would reach modules that build nothing for Android.
 */
internal abstract class AndroidNdkNotice : BuildService<BuildServiceParameters.None> {
    private val warned = ConcurrentHashMap.newKeySet<String>()

    fun warnOnce(logger: Logger, warning: String) {
        if (warned.add(warning)) logger.warn(warning)
    }

    companion object {
        fun register(project: Project): Provider<AndroidNdkNotice> =
            project.gradle.sharedServices.registerIfAbsent("konanAndroidNdkNotice", AndroidNdkNotice::class.java) {}
    }
}

/** This task builds with [ndk]: warn when that left an `ANDROID_NDK_HOME` unused. */
internal fun Task.warnsOfUnusedNdkHome(ndk: Provider<ConfigValue>) {
    val notice = AndroidNdkNotice.register(project)
    usesService(notice)
    doFirst(WarnOfUnusedNdkHome(ndk, notice))
}

/** A class, not a lambda: Gradle will not call a task up to date whose action is a lambda. */
private class WarnOfUnusedNdkHome(
    private val ndk: Provider<ConfigValue>,
    private val notice: Provider<AndroidNdkNotice>,
) : Action<Task> {
    override fun execute(task: Task) {
        ndk.get().overriddenWarning()?.let { notice.get().warnOnce(task.logger, it) }
    }
}
