import io.github.lemcoder.hostKonanTarget

plugins {
    kotlin("jvm") version "2.4.20"
    id("io.github.lemcoder.konanplugin")
    application
}

repositories { mavenCentral() }

konan {
    // native/*.c -> one static archive per target.
    val mymath = libraries.create("mymath") { targets(hostKonanTarget()) }
    // The C API in the .def, linked against that library. A kotlin("jvm") project gets JNI bindings,
    // added to its main source set, and a JNI library per target a JVM can load.
    bindings {
        create("mymath") {
            defFile.set(file("src/main/nativeInterop/mymath.def"))
            includeDirs.from("native")
            library(mymath)
        }
    }
}

kotlin { jvmToolchain(17) }

application { mainClass.set("example.MainKt") }

tasks.named<JavaExec>("run") {
    dependsOn("linkJniMymath")
    // loadLibrary() resolves the stub from java.library.path.
    val libDir = konan.bindings.named("mymath").flatMap { it.jni.libraryDirectory }
    jvmArgumentProviders.add { listOf("-Djava.library.path=${libDir.get().asFile.absolutePath}") }
}
