package io.github.lemcoder.toolchain

import io.github.lemcoder.model.HostOs
import java.io.File

/**
 * A JDK that ships `include/jni.h`, which the JNI stub compiles against. The JVM running Gradle is not a
 * reliable one: IDE-bundled JBRs — Android Studio's, often the Gradle daemon — strip `include/`.
 */
internal object Jdk {
    private const val MISSING = "No JDK with include/jni.h found. Set jni.jdkHome."

    /** `include` and its OS subdirectory under [jdkHome], if it ships jni.h; the macOS `Contents/Home` layout too. */
    fun jniIncludeDirectoriesOf(jdkHome: File): List<File>? =
        homeWithHeaders(jdkHome)?.let { listOf(it.resolve("include"), it.resolve("include/${HostOs.current.jniIncludeSubdirectory}")) }

    /** [jniIncludeDirectoriesOf] the first installed JDK that has them. */
    fun detectJniIncludeDirectories(): List<File> =
        candidates().firstNotNullOfOrNull(::jniIncludeDirectoriesOf) ?: error(MISSING)

    /** The home of the JDK [detectJniIncludeDirectories] uses, for an external build's `JAVA_HOME`. */
    fun detectHome(): File = candidates().firstNotNullOfOrNull(::homeWithHeaders) ?: error(MISSING)

    private fun homeWithHeaders(jdkHome: File): File? =
        listOf(jdkHome, jdkHome.resolve("Contents/Home")).firstOrNull { it.resolve("include/jni.h").isFile }

    /** JDK homes worth checking, most specific first. */
    private fun candidates(): List<File> = buildList {
        System.getenv("JNI_HOME")?.let { add(File(it)) }
        System.getenv("JAVA_HOME")?.let { add(File(it)) }
        System.getProperty("java.home")?.let { add(File(it)) }
        File(System.getProperty("user.home"), "Library/Java/JavaVirtualMachines").listFiles()?.let { addAll(it) }
        File("/Library/Java/JavaVirtualMachines").listFiles()?.let { addAll(it) }
        File("/usr/lib/jvm").listFiles()?.let { addAll(it) }
    }

    private val HostOs.jniIncludeSubdirectory: String
        get() = when (this) {
            HostOs.MACOS -> "darwin"
            HostOs.LINUX -> "linux"
            HostOs.WINDOWS -> "win32"
        }
}
