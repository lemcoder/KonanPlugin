package io.github.lemcoder.index

import java.io.File

/**
 * Where cinterop's header indexer runs: inside a Kotlin/Native distribution, whose compiler jar must be
 * the Kotlin version the plugin's copy of the model was vendored from — [kotlinNativeVersion] — or the
 * index may not read back. [ExportNativeIndexTask] runs it.
 */
object NativeIndexer {
    /**
     * The Kotlin version the model was vendored from — `kotlinNativeVersion` in the plugin build —
     * and so the distribution the indexer must run in.
     */
    val kotlinNativeVersion: String by lazy {
        java.util.Properties().apply {
            NativeIndexer::class.java.getResourceAsStream("native-index.properties").use { load(checkNotNull(it)) }
        }.getProperty("version")
    }

    /** The installed distribution of [kotlinNativeVersion] under `~/.konan`, if any. */
    fun pinnedKonanHome(): File? =
        File(System.getProperty("user.home"), ".konan")
            .listFiles { f -> f.isDirectory && f.name.startsWith("kotlin-native-prebuilt-") && f.name.endsWith("-$kotlinNativeVersion") }
            ?.singleOrNull()
}
