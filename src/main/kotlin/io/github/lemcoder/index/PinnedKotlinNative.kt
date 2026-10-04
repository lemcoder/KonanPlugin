package io.github.lemcoder.index

import java.io.File

/**
 * The Kotlin/Native distribution the index exporter was compiled against, and so the one it must run in:
 * the exporter links against that version's compiler jar — its internal functions and the `NativeIndex`
 * model the generators read — so another version may not link. `kotlinNativeVersion` in the plugin build.
 */
object PinnedKotlinNative {
    val version: String by lazy {
        java.util.Properties().apply {
            PinnedKotlinNative::class.java.getResourceAsStream("kotlin-native.properties").use { load(checkNotNull(it)) }
        }.getProperty("version")
    }

    /** The installed distribution of [version] under `~/.konan`, if any. */
    fun home(): File? =
        File(System.getProperty("user.home"), ".konan")
            .listFiles { f -> f.isDirectory && f.name.startsWith("kotlin-native-prebuilt-") && f.name.endsWith("-$version") }
            ?.singleOrNull()
}
