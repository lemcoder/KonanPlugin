// Declares the plugins once for both modules, so they share one classloader.
plugins {
    kotlin("multiplatform") apply false
    id("com.android.kotlin.multiplatform.library") apply false
    id("com.android.application") apply false
    id("io.github.lemcoder.konanplugin") apply false
}
