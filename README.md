# Konan Plugin

The **Konan Plugin** is a custom Gradle plugin that facilitates compiling C/C++ sources using the `run_konan` utility. It simplifies multi-platform native compilation for Kotlin Native projects.

---

## Features
- Compiles C/C++ source files to `.o` object files.
- Supports linking object files into static libraries (`.a`).
- Works with all Kotlin Native targets 
- Configurable via a Gradle extension.

---

## Installation
1. Apply the plugin in your `build.gradle.kts`:
   ```kotlin
   plugins {
       id("io.github.lemcoder.konanplugin") version "1.0.0"
   }
   ```

2. Ensure that the Kotlin/Native toolchain is installed and the `run_konan` utility is accessible.

---

## Configuration
The plugin provides an extension called `KonanPluginExtension`. Below are the configuration options:

| Property      | Type                | Description                                 |
|---------------|---------------------|---------------------------------------------|
| `targets`     | `List<KonanTarget>` | List of Kotlin/Native targets to compile for. |
| `sourceDir`   | `String`            | Directory containing C/C++ source files.    |
| `headerDir`   | `String`            | Directory containing `.h` header files.     |
| `libName`     | `String`            | Name of the output static library.          |
| `outputDir`   | `String`            | Directory for output object files and libraries. |
| `konanPath`   | `String`            | Path to the `run_konan` utility.            |

---

## Usage
After applying the plugin, configure it in your build script using the provided extension:

### Sample Configuration
```kotlin
configure<KonanPluginExtension> {
    targets = buildList {
        add(KonanTarget.LINUX_X64)
        add(KonanTarget.MINGW_X64)
        if (System.getProperty("os.name").lowercase().contains("mac")) {
            add(KonanTarget.IOS_ARM64)
            add(KonanTarget.IOS_SIMULATOR_ARM64)
            add(KonanTarget.IOS_X64)
            add(KonanTarget.MACOS_X64)
            add(KonanTarget.MACOS_ARM64)
        }
    }
    sourceDir = "${rootDir}/native/src"
    headerDir = "${rootDir}/native/include"
    outputDir = "${rootDir}/native/lib"
    libName = "mylib"
    konanPath = localKonanDir.listFiles()?.first {
        it.name.contains("<LATEST_KOTLIN_VERSION>") // e.g., "2.1.0"
    }?.absolutePath
}
```

---

## Tasks
The plugin defines a task, `runKonanClang`, that:
1. Compiles C/C++ source files into `.o` object files.
2. Links object files into a `.a` static library.

### Example
1. Place your C/C++ source files in the directory specified by `sourceDir` (e.g., `native/src`).
2. Place your `.h` headers in the directory specified by `headerDir` (e.g., `native/include`).
3. Run the build:
   ```bash
   ./gradlew runKonanClang
   ```

The compiled static libraries will be output to `outputDir`.

---

## Example Workflow
### Directory Structure
```plaintext
project-root/
├── build.gradle.kts
├── native/
│   ├── src/
│   │   ├── file1.c
│   ├── include/
│   │   ├── file1.h
│   └── lib/ (generated output)
└── settings.gradle.kts
```

### Output
After running the task:
```plaintext
native/lib/
├── linux_x64/
│   └── libtsf.a
├── mingw_x64/
│   └── libtsf.a
```

---

## License
This project is licensed under the Apache 2.0 License. For more details, see the `LICENSE` file.

--- 

Happy coding! 😊