import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.net.URI

/** The upstream files making up the vendored model. Each copy starts with a stamp naming its version. */
object NativeIndexModel {
    val files = listOf("NativeIndex.kt", "HeaderToIdMapper.kt")

    const val STAMP = "// Vendored from JetBrains/kotlin v"

    const val UPSTREAM_DIR = "kotlin-native/Interop/Indexer/src/main/kotlin/org/jetbrains/kotlin/native/interop/indexer"
}

/** Downloads the model for [version] from JetBrains/kotlin into [directory], stamped with the version. */
@UntrackedTask(because = "Writes into the source tree from the network")
abstract class UpdateNativeIndexModel : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:Internal
    abstract val directory: DirectoryProperty

    init {
        group = "native index"
        description = "Downloads the NativeIndex model for kotlinNativeVersion from JetBrains/kotlin into src/main."
    }

    @TaskAction
    fun update() {
        val base = "https://raw.githubusercontent.com/JetBrains/kotlin/v${version.get()}/${NativeIndexModel.UPSTREAM_DIR}"
        NativeIndexModel.files.forEach { name ->
            val upstream = URI("$base/$name").toURL().readText()
            directory.file(name).get().asFile.writeText(
                "${NativeIndexModel.STAMP}${version.get()}, unmodified. Update with ./gradlew updateNativeIndexModel.\n$upstream"
            )
        }
    }
}

/** Fails when a vendored model file is not stamped with [version]. */
@UntrackedTask(because = "A cheap check with no outputs")
abstract class VerifyNativeIndexModel : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:InputFiles @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sources: ConfigurableFileCollection

    init {
        group = "verification"
        description = "Fails when the vendored NativeIndex model is not from kotlinNativeVersion."
    }

    @TaskAction
    fun verify() {
        val expected = "${NativeIndexModel.STAMP}${version.get()},"
        val stale = sources.files.filterNot { it.isFile && it.useLines { lines -> lines.first().startsWith(expected) } }
        check(stale.isEmpty()) {
            "Vendored NativeIndex model is not from Kotlin ${version.get()}: ${stale.joinToString { it.name }}. " +
                "Run ./gradlew updateNativeIndexModel."
        }
    }
}
