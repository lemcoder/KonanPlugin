package io.github.lemcoder.model

/**
 * Where a jar carries the native libraries generated bindings load: `konan/natives/<target>/<file>`, the
 * target by its Kotlin/Native name. The plugin bundles them there and the generated loader looks there, so
 * both sides compile this one spelling.
 */
object BundledLibraries {
    const val RESOURCE_ROOT: String = "konan/natives"
}
