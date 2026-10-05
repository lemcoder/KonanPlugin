plugins {
    `kotlin-dsl`
    id("com.gradle.plugin-publish") version "2.2.1"
    // The index exporter and its generators: buildSrc/src/main/kotlin/native-index.gradle.kts
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

val kgpForTests: Configuration = configurations.create("kgpForTests")

dependencies {
    "kgpForTests"(providers.gradleProperty("kotlinNativeVersion").map { "org.jetbrains.kotlin:kotlin-gradle-plugin:$it" })
    // KonanTarget & HostManager: the plugin speaks Kotlin/Native's own target type, so a build can pass
    // `kotlinNativeTarget.konanTarget` straight in. Not compileOnly — it appears in decorated DSL
    // signatures, which Gradle resolves in projects that have no Kotlin plugin on the classpath.
    implementation(providers.gradleProperty("kotlinNativeVersion").map { "org.jetbrains.kotlin:kotlin-native-utils:$it" })
    // Used only by the KGP cinterop adapter, inside plugins.withId: the consuming project supplies KGP.
    compileOnly(providers.gradleProperty("kotlinNativeVersion").map { "org.jetbrains.kotlin:kotlin-gradle-plugin:$it" })
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
    // TestKit loads the plugin under test where plugins the test build applies cannot see it, so the
    // KGP adapter's test puts KGP on that same classpath itself. Only that test: the others must keep
    // proving the plugin loads without KGP.
    val kgp: FileCollection = kgpForTests
    inputs.files(kgp).withPropertyName("kgpForTests").withNormalizer(ClasspathNormalizer::class.java)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dkgp.classpath=${kgp.asPath}") })
}
