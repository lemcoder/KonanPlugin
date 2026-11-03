plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
}

dependencies {
    // For KotlinCompile in native-index.gradle.kts; the version kotlin-dsl itself uses.
    implementation(kotlin("gradle-plugin", embeddedKotlinVersion))
}
