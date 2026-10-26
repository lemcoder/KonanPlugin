import java.net.HttpURLConnection
import java.net.URI

plugins {
    `kotlin-dsl`
    id("com.gradle.plugin-publish") version "2.2.1"
    // The index exporter and its generators: buildSrc/src/main/kotlin/native-index.gradle.kts
    id("native-index")
}

// Every published version is immutable on the Portal: bump before publishing, and never reuse one.
version = "2.0.0-alpha01"
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

// A version already on the Plugin Portal must never be published again, not even to mavenLocal: a local
// copy shadows the Portal's for every project that lists mavenLocal() first, so those projects break
// locally and build in CI. Skipped offline, or when the Portal cannot be reached.
val checkVersionUnpublished = tasks.register("checkVersionUnpublished") {
    group = "publishing"
    description = "Fails when this version is already on the Gradle Plugin Portal."
    val pluginVersion = version.toString()
    val offline = gradle.startParameter.isOffline
    val marker = "https://plugins.gradle.org/m2/io/github/lemcoder/konanplugin/io.github.lemcoder.konanplugin.gradle.plugin/" +
        "$pluginVersion/io.github.lemcoder.konanplugin.gradle.plugin-$pluginVersion.pom"
    inputs.property("version", pluginVersion)
    doLast {
        if (offline) return@doLast logger.warn("Offline: not checking whether $pluginVersion is on the Plugin Portal.")
        val status = runCatching {
            HttpURLConnection.setFollowRedirects(true)
            (URI(marker).toURL().openConnection() as HttpURLConnection).run {
                requestMethod = "HEAD"
                connectTimeout = 10_000
                readTimeout = 10_000
                responseCode.also { disconnect() }
            }
        }.getOrElse { return@doLast logger.warn("Could not reach the Plugin Portal ($it); not checking $pluginVersion.") }
        check(status != 200) {
            "Version $pluginVersion is already on the Gradle Plugin Portal. Bump `version` in build.gradle.kts: " +
                "publishing it again, even to mavenLocal, shadows the Portal's copy for every project that lists mavenLocal() first."
        }
    }
}
tasks.matching { it.name == "publishToMavenLocal" || it.name == "publishPlugins" }.configureEach { dependsOn(checkVersionUnpublished) }
