package io.github.lemcoder.index

import java.io.File

/**
 * Where cinterop's header indexer runs: inside a Kotlin/Native distribution of exactly
 * [kotlinNativeVersion]. The exporter is compiled against that version's compiler jar — its internal
 * functions and the `NativeIndex` model the generators read — so another version may not link.
 * [GenerateFromNativeIndexTask] runs it.
 */
object NativeIndexer {
    /** `kotlinNativeVersion` in the plugin build: the compiler jar the exporter was compiled against. */
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
