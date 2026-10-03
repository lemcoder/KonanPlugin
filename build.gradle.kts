plugins {
    `kotlin-dsl`
    id("com.gradle.plugin-publish") version "2.2.1"
    // cinterop's NativeIndex exporter and the vendored model: buildSrc/src/main/kotlin/native-index.gradle.kts
    id("native-index")
}

version = "1.2.0-alpha05"
group = "io.github.lemcoder"

repositories {
    google()
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
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
    // KonanTarget & HostManager: the plugin speaks Kotlin/Native's own target type, so a build can pass
    // `kotlinNativeTarget.konanTarget` straight in. Not compileOnly — it appears in decorated DSL
    // signatures, which Gradle resolves in projects that have no Kotlin plugin on the classpath.
    implementation(providers.gradleProperty("kotlinNativeVersion").map { "org.jetbrains.kotlin:kotlin-native-utils:$it" })
    // Used only to auto-wire generated sources/jniLibs into Android projects via the AGP variant API.
    // compileOnly: the consuming Android project supplies AGP at runtime.
    compileOnly("com.android.tools.build:gradle-api:9.2.1")

    // Functional testing of the plugin with Gradle TestKit (see doc/testing-with-test-kit.md).
    // gradleTestKit() + the java-gradle-plugin (applied by kotlin-dsl) auto-inject the
    // plugin-under-test classpath into withPluginClasspath().
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
