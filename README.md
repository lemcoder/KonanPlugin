# Konan Plugin

The **Konan Plugin** is a custom Gradle plugin that facilitates compiling C/C++ sources using the `run_konan` utility. It simplifies multi-platform native compilation for Kotlin Native projects.

> This plugin is based on awesome work by [aSemy](https://gist.github.com/aSemy) 🚀 

## Features
- Compiles C/C++ source files to `.o` object files.
- Supports linking object files into static libraries (`.a`).
- Works with all Kotlin Native targets (passed as simple strings).
- Zero runtime dependencies (only requires Gradle API).
- Support for custom compiler arguments.
- Configurable via a Gradle extension.

## Installation
1. Apply the plugin in your `build.gradle.kts`:
   ```kotlin
   plugins {
       id("io.github.lemcoder.konanplugin") version "1.1.0"
   }
   ```

2. Ensure that the Kotlin/Native toolchain is installed and the `run_konan` utility is accessible.

## Configuration
The plugin provides an extension called `konanConfig`. Below are the configuration options:

| Property                   | Type           | Description                                 |
|----------------------------|----------------|---------------------------------------------|
| `targets`                  | `List<String>` | List of Kotlin/Native target names to compile for (e.g., `"linux_x64"`, `"mingw_x64"`). |
| `sourceDir`                | `String`       | Directory containing C/C++ source files.    |
| `headerDir`                | `String`       | Directory containing `.h` header files.     |
| `libName`                  | `String`       | Name of the output static library.          |
| `outputDir`                | `String`       | Directory for output object files and libraries. |
| `konanPath`                | `String`       | Path to the `run_konan` utility.            |
| `additionalCompilerArgs`   | `List<String>` | Additional compiler arguments to pass to clang. |

## Usage
After applying the plugin, configure it in your build script using the `konanConfig` extension:

### Sample Configuration
```kotlin
konanConfig {
    targets.addAll("linux_x64", "mingw_x64")
    
    // Optionally add platform-specific targets
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        targets.addAll(
            "ios_arm64",
            "ios_simulator_arm64", 
            "ios_x64",
            "macos_x64",
            "macos_arm64"
        )
    }
    
    sourceDir.set("${rootDir}/native/src")
    headerDir.set("${rootDir}/native/include")
    outputDir.set("${rootDir}/native/lib")
    libName.set("mylib")
    
    konanPath.set(
        localKonanDir.listFiles()?.first {
            it.name.contains("<LATEST_KOTLIN_VERSION>") // e.g., "2.1.0"
        }?.absolutePath
    )
    
    // Optional: Add custom compiler arguments
    additionalCompilerArgs.addAll("-O2", "-Wall")
}
```

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
│   └── lib.a
├── mingw_x64/
│   └── lib.a
```

## License
This project is licensed under the Apache 2.0 License. For more details, see the `LICENSE` file.

Happy coding! 😊
