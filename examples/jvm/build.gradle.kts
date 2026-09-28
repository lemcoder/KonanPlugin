import io.github.lemcoder.hostKonanTarget
import org.jetbrains.kotlin.konan.target.KonanTarget
import io.github.lemcoder.interop.jvmInterops

plugins {
    kotlin("jvm") version "2.4.20"
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

lateinit var mymathLibDir: Provider<Directory>

kotlin {
    jvmToolchain(17)

    // Declared like a cinterop, on the compilation it belongs to. The def names the archive above,
    // so the plugin also links the JNI library; drop `staticLibraries` from it and you get the
    // bindings and the .c stub only, for another build system to compile.
    target.compilations["main"].jvmInterops {
        create("mymath") {
            defFile(project.file("src/main/nativeInterop/mymath.def"))
            includeDirs.from(file("native"))
            mymathLibDir = resolvedLibraryDirectory
        }
    }
}

application { mainClass.set("example.MainKt") }

tasks.named<JavaExec>("run") {
    dependsOn("linkJvmInteropMymath")
    // loadLibrary() resolves the stub from java.library.path.
    val libDir = mymathLibDir
    jvmArgumentProviders.add { listOf("-Djava.library.path=${libDir.get().asFile.absolutePath}") }
}
