pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("com.android.application") version "9.2.1"
    }
    // The konan plugin, built from this checkout.
    includeBuild("../..")
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral() // PanamaPort
    }
}

rootProject.name = "android-ffm-example"
