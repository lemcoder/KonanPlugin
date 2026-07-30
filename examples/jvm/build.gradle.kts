import io.github.lemcoder.KonanTarget

plugins {
    kotlin("jvm") version "2.3.10"
    id("io.github.lemcoder.konanplugin")
    application
}

repositories { mavenCentral() }

val host = KonanTarget.host()

// Cross-compile native/*.c -> build/native/<target>/libmymath.a, then generate the JNI bridges and
// link the stub shared library against it. konanPath / jniHome are auto-detected; the nested block
// inherits the target, header dir and static library from the enclosing one.
konanConfig {
    targets(host)
    libName.set("mymath")

    // A host-only target doesn't auto-enable the JNI leg, so declaring the block is what opts in.
    jvmInterop {
        packageName.set("example")
    }
}

// Generated bridges become part of the main source set.
kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated/jvmInterop/kotlin"))
}

application { mainClass.set("example.MainKt") }

tasks.named<JavaExec>("run") {
    dependsOn("linkJvmInterop")
    // loadLibrary() resolves the stub from java.library.path.
    jvmArgs("-Djava.library.path=${layout.buildDirectory.dir("jvmInterop/jniLibs/${host.abiDir}").get().asFile.absolutePath}")
}
