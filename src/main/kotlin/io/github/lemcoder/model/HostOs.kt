package io.github.lemcoder.model

/** The operating system the build runs on: the one place the plugin reads `os.name` and `os.arch`. */
internal enum class HostOs {
    MACOS, LINUX, WINDOWS;

    companion object {
        val current: HostOs by lazy {
            val name = System.getProperty("os.name").lowercase()
            when {
                name.contains("mac") || name.contains("darwin") -> MACOS
                name.contains("windows") -> WINDOWS
                else -> LINUX
            }
        }

        val architecture: Architecture by lazy {
            when (System.getProperty("os.arch").lowercase()) {
                "aarch64", "arm64" -> Architecture.ARM64
                "x86", "i386", "i686" -> Architecture.X86
                "arm" -> Architecture.ARM32
                else -> Architecture.X64
            }
        }
    }
}
