package io.github.lemcoder.dsl.bindings.jni

/**
 * The JNI library a binding's generated code loads: cinterop's own stub naming, the package's parts
 * joined plus `stubs` — `io.example.m` → `ioexamplemstubs`.
 */
internal fun stubLibraryBaseName(packageName: String): String = packageName.split('.').joinToString("") + "stubs"
