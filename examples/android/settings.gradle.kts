pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        mavenLocal() // the konan plugin, published via `../../gradlew publishToMavenLocal`
    }
    plugins {
        id("com.android.application") version "9.2.1"
        id("io.github.lemcoder.konanplugin") version "1.1.2"
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "android-example"
