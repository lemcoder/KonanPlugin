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
            description = "Builds C/C++ libraries with Kotlin/Native's toolchain and binds them to Kotlin/Native, " +
                "the JVM and Android from one cinterop .def: generated JNI bindings, or FFM through jextract."
            tags.set(listOf("kotlin-native", "cinterop", "jni", "ffm", "jextract", "android", "cross-compile", "c"))
        }
    }
}

val kgpForTests: Configuration = configurations.create("kgpForTests")

dependencies {
    // -PkgpTestVersion runs the KGP fixtures on another Kotlin version; -PtestGradleVersion on another Gradle.
    "kgpForTests"(
        providers.gradleProperty("kgpTestVersion").orElse(providers.gradleProperty("kotlinNativeVersion"))
            .map { "org.jetbrains.kotlin:kotlin-gradle-plugin:$it" }
    )
    // KonanTarget, for NativeTarget.of(konanTarget) and the KGP adapter — both reached only where KGP, which
    // brings it, is applied. The DSL speaks the plugin's own NativeTarget, so nothing decorated needs it.
    compileOnly(providers.gradleProperty("kotlinNativeVersion").map { "org.jetbrains.kotlin:kotlin-native-utils:$it" })
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
    // NativeTargetTest compares the plugin's targets with the Kotlin/Native version it is built against.
    testImplementation(providers.gradleProperty("kotlinNativeVersion").map { "org.jetbrains.kotlin:kotlin-native-utils:$it" })
}

tasks.test {
    useJUnitPlatform()
    // TestKit loads the plugin under test where plugins the test build applies cannot see it, so the
    // KGP adapter's test puts KGP on that same classpath itself. Only that test: the others must keep
    // proving the plugin loads without KGP.
    val kgp: FileCollection = kgpForTests
    inputs.files(kgp).withPropertyName("kgpForTests").withNormalizer(ClasspathNormalizer::class.java)
    val gradleVersion = providers.gradleProperty("testGradleVersion").orElse("")
    inputs.property("testGradleVersion", gradleVersion)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dkgp.classpath=${kgp.asPath}", "-Dtest.gradle.version=${gradleVersion.get()}") })
}
