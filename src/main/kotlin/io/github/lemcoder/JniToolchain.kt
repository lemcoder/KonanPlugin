package io.github.lemcoder

import io.github.lemcoder.jvm.JvmInteropSupport

/**
 * A JDK home that ships `include/jni.h`, for a build that compiles the generated JNI stub itself.
 *
 * In generate-only mode the plugin hands over the `.c` and another build system — CMake, Bazel —
 * compiles it, and that build needs the header. The JVM running Gradle is not a reliable source:
 * IDE-bundled JBRs strip `include/`, and CMake's `FindJNI` insists on a full JDK when only the
 * header is wanted. Pass this as `JAVA_HOME` to the external build.
 *
 * @throws IllegalStateException when no installed JDK ships the header.
 */
fun jniHome(): String = JvmInteropSupport.detectJniHome().absolutePath
