pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    // Use the plugin from this repository instead of the Gradle Plugin Portal.
    includeBuild("../..")
}

rootProject.name = "jvm-interop-sample"
