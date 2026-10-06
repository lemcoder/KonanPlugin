// An Android app that calls a C library through FFM bindings: jextract writes them from the same .def,
// the plugin moves them onto PanamaPort (the FFM API backported to Android 8.0+) and links the library
// itself as libmymath.so per ABI — no JNI stub. Needs jextract on the PATH (or ffm { jextract.set(…) }).

import io.github.lemcoder.model.NativeTarget

plugins {
    id("com.android.application")
    id("io.github.lemcoder.konanplugin")
}

konan {
    // 64-bit ABIs only: jextract writes the host's LP64 layouts.
    val mymath = libraries.create("mymath") { targets(NativeTarget.ANDROID_ARM64, NativeTarget.ANDROID_X64) }
    bindings {
        create("mymath") {
            defFile.set(file("src/main/nativeInterop/mymath.def"))
            includeDirs.from("native")
            library(mymath)
            ffm() // in an Android module: PanamaPort bindings in place of JNI
        }
    }
}

android {
    namespace = "com.example.ffmapp"
    compileSdk = 37          // PanamaPort's AAR metadata requires it

    defaultConfig {
        applicationId = "com.example.ffmapp"
        minSdk = 26          // PanamaPort's floor
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        // jextract's output uses pattern matching in switch.
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
