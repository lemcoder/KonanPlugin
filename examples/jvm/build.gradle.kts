import io.github.lemcoder.abiDir
import io.github.lemcoder.hostKonanTarget
import org.jetbrains.kotlin.konan.target.KonanTarget
import io.github.lemcoder.interop.jvmInterops

plugins {
    kotlin("jvm") version "2.3.10"
    id("io.github.lemcoder.konanplugin")
    application
}

repositories { mavenCentral() }

val host = hostKonanTarget()

// konanConfig compiles, nothing else: native/*.c -> build/native/<target>/libmymath.a.
konanConfig {
    targets(host)
    libName.set("mymath")
}

kotlin {
    jvmToolchain(17)

    // Declared like a cinterop, on the compilation it belongs to. The def names the archive above,
    // so the plugin also links the JNI library; drop `staticLibraries` from it and you get the
    // bindings and the .c stub only, for another build system to compile.
    target.compilations["main"].jvmInterops {
        create("mymath") {
            defFile(project.file("src/main/nativeInterop/mymath.def"))
            includeDirs.from(file("native"))
        }
    }
}

application { mainClass.set("example.MainKt") }

tasks.named<JavaExec>("run") {
    dependsOn("linkJvmInteropMymath")
    // loadLibrary() resolves the stub from java.library.path.
    jvmArgs(
        "-Djava.library.path=" +
            layout.buildDirectory.dir("jvmInterop/mymath/jniLibs/${host.abiDir}").get().asFile.absolutePath
    )
}
