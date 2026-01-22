repositories {
    mavenCentral()
}

plugins {
    id("com.gradle.plugin-publish") version "1.3.0"
    `kotlin-dsl`
}

version = "1.1.0"
group = "io.github.lemcoder"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

gradlePlugin {
    website.set("https://github.com/lemcoder/KonanPlugin")
    vcsUrl.set("https://github.com/lemcoder/KonanPlugin")

    plugins {
        create("KonanPlugin") {
            id = "io.github.lemcoder.konanplugin"
            implementationClass = "io.github.lemcoder.KonanPlugin"
            displayName = "Konan Plugin"
            description = "Gradle plugin to compile C sources to static libraries using Kotlin Konan compiler"
            tags.set(listOf("cross-compile", "konan", "c", "kotlin"))
        }
    }
}

dependencies {
    implementation(gradleApi())
}