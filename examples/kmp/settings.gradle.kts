pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("multiplatform") version "2.4.20"
        id("com.android.kotlin.multiplatform.library") version "9.2.1"
        id("com.android.application") version "9.2.1"
    }
    // The konan plugin, built from source.
    includeBuild("../..")
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kmp-example"
include(":shared", ":androidApp")
