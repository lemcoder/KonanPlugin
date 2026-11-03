import io.github.lemcoder.model.NativeTarget

plugins {
    kotlin("jvm") version "2.4.20"
    id("io.github.lemcoder.konanplugin")
    application
}

repositories { mavenCentral() }

konan {
    // Where the Kotlin plugin installs the Kotlin/Native version this plugin needs; or set
    // konan.config.konanHome in ~/.gradle/gradle.properties.
    config { konanHome.set(kotlinNativeDistribution()) }
    // native/*.c -> one static archive per target.
    val mymath = libraries.create("mymath") { targets(NativeTarget.host) }
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

// The JNI library travels in the jar (konan/natives/<target>/), where the bindings load it from:
// `run`, `installDist` and a plain `java -cp` need no java.library.path.
application { mainClass.set("example.MainKt") }
