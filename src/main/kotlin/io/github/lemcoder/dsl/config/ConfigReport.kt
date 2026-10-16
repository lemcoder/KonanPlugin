package io.github.lemcoder.dsl.config

import io.github.lemcoder.dsl.builds.ExternalBuildTask
import io.github.lemcoder.kotlin.UsesKonanDistribution
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** `konanConfig`: every `konan.config` field, where its value comes from, and which tasks need it. */
internal fun Project.registerConfigReport(config: KonanConfig) {
    tasks.register("konanConfig", KonanConfigReportTask::class.java) {
        group = "konan"
        description = "Print konan.config: each path, where it is set, and which tasks need it."
        val reportPath = path
        ConfigField.entries.forEach { values.add(config.resolve(it, reportPath)) }
        projectPath.set(project.path)
        usages.set(
            project.provider {
                // Tasks read config as they are configured; realize them so every reader has asked.
                project.tasks.withType(UsesKonanDistribution::class.java).toList()
                project.tasks.withType(ExternalBuildTask::class.java).toList()
                config.usages.mapKeys { it.key.name }.mapValues { it.value.toList() }
            }
        )
        outputs.upToDateWhen { false }
    }
}

@DisableCachingByDefault(because = "Prints a report")
internal abstract class KonanConfigReportTask : DefaultTask() {
    @get:Input abstract val values: ListProperty<ConfigValue>
    @get:Input abstract val usages: MapProperty<String, List<String>>
    @get:Input abstract val projectPath: Property<String>

    @TaskAction
    fun report() {
        val usages = usages.get()
        val width = ConfigField.entries.maxOf { it.dslName.length }
        val lines = values.get().map { value ->
            val users = usages[value.field.name].orEmpty()
            val problem = value.problem()
            val status = when {
                users.isEmpty() -> "not needed here"
                problem == null -> "ok"
                else -> "NEEDED, ${problem.removePrefix("is ")}"
            }
            val needs = if (users.isEmpty()) "" else "\n${" ".repeat(width + 4)}needed by ${users.joinToString()}"
            "  ${value.field.dslName.padEnd(width)}  ${value.describe()} — $status$needs"
        }
        logger.lifecycle((listOf("konan.config for ${projectPath.get()}:") + lines).joinToString("\n"))
        val missing = values.get().filter { usages[it.field.name].orEmpty().isNotEmpty() && it.problem() != null }
        if (missing.isNotEmpty()) {
            logger.lifecycle("\nSet a field in konan { config { } }, as the Gradle property konan.config.<name> or as its environment variable; `gradlew <task>` prints how for each.")
        }
    }
}
