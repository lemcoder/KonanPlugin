package io.github.lemcoder.dsl.builds

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * How many external builds run at once, across the whole build: one by default. Each already uses every
 * core (`make -j<cores>`, `cmake --build --parallel <cores>`, cargo, zig), so two at once only stack —
 * five llama.cpp builds side by side exhausted 32 GB. `konan.externalBuilds.maxParallel` raises it.
 */
internal abstract class ExternalBuildLimit : BuildService<BuildServiceParameters.None> {
    companion object {
        const val PROPERTY = "konan.externalBuilds.maxParallel"

        fun register(project: Project): Provider<ExternalBuildLimit> =
            project.gradle.sharedServices.registerIfAbsent("konanExternalBuilds", ExternalBuildLimit::class.java) {
                maxParallelUsages.set(project.providers.gradleProperty(PROPERTY).map { it.toInt() }.orElse(1))
            }
    }
}
