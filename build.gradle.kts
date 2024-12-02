repositories {
    mavenCentral()
}

plugins {
    id("com.gradle.plugin-publish") version "1.3.0"
    `kotlin-dsl`
}

version = "1.0.0"
group = "io.github.lemcoder"

gradlePlugin {
    website.set("https://github.com/lemcoder/KonanPlugin")
    vcsUrl.set("https://github.com/lemcoder/KonanPlugin")

    plugins {
        create("KonanPlugin") {
            id = "io.github.lemcoder.konanplugin"
            implementationClass = "io.github.lemcoder.konanplugin.KonanPlugin"
            displayName = "Konan Plugin"
            description = "Gradle plugin to compile C sources to static libraries using Kotlin Konan compiler"
            tags.set(listOf("cross-compile", "konan", "c", "kotlin"))
        }
    }
}

dependencies {
    implementation(gradleApi())
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.0")
}