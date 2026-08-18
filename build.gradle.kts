repositories {
    google()
    mavenCentral()
}

plugins {
    id("com.gradle.plugin-publish") version "1.3.0"
    `kotlin-dsl`
}

version = "1.2.0-alpha06"
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
    // KonanTarget & HostManager: the plugin speaks Kotlin/Native's own target type, so a build can pass
    // `kotlinNativeTarget.konanTarget` straight in. Not compileOnly — it appears in decorated DSL
    // signatures, which Gradle resolves in projects that have no Kotlin plugin on the classpath.
    implementation("org.jetbrains.kotlin:kotlin-native-utils:2.2.10")
    // Used only to auto-wire generated sources/jniLibs into Android projects via the AGP variant API.
    // compileOnly: the consuming Android project supplies AGP at runtime.
    compileOnly("com.android.tools.build:gradle-api:9.2.1")

    // Functional testing of the plugin with Gradle TestKit (see doc/testing-with-test-kit.md).
    // gradleTestKit() + the java-gradle-plugin (applied by kotlin-dsl) auto-inject the
    // plugin-under-test classpath into withPluginClasspath().
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}