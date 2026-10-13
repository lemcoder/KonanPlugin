# Android FFM example

An Android app that calls `native/mymath.c` through **FFM** bindings instead of JNI. The plugin:

- cross-compiles `native/*.c` to `libmymath.a` per ABI;
- runs jextract over the same `.def` the JNI and Kotlin/Native bindings use, and moves its output from
  `java.lang.foreign` onto [PanamaPort](https://github.com/vova7878/PanamaPort)'s `com.v7878.foreign`,
  the FFM API backported to Android 8.0+, which it adds as a dependency;
- links the library itself as `libmymath.so` per ABI into `jniLibs/` — FFM needs no stub.

## Requirements

- jextract, as `konan.config.jextract=/path/to/jextract` in `~/.gradle/gradle.properties` (or `KONAN_JEXTRACT`).
- `compileSdk` 37 and `minSdk` 26, which PanamaPort requires; Java 21 source, which jextract's output uses.
- 64-bit ABIs: jextract writes the build host's LP64 layouts, so 32-bit ABIs get no library.

## Run

```bash
./gradlew installDebug
adb shell am start -n com.example.ffmapp/.MainActivity
adb logcat -s KonanFfm
```

```
FFM via PanamaPort:
my_add(2, 3)         = 5
my_scale(4.0)        = 40.0
my_length("kotlin")  = 6
my_greeting()        = hello from C
my_sum([1, 2, 3, 4]) = 10
my_fill -> [7, 7, 7]
```
