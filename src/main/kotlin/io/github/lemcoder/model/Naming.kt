package io.github.lemcoder.model

/** `mymath` → `Mymath`, as task names embed a name. */
internal fun String.capitalized(): String = replaceFirstChar { it.uppercase() }

/** Every task name the plugin registers: `<prefix><Owner>[<Target>]`, e.g. `linkJniMymathAndroid_arm64`. */
internal fun taskName(prefix: String, owner: String, target: NativeTarget? = null): String =
    prefix + owner.capitalized() + (target?.taskSuffix ?: "")

/**
 * The JNI library a binding's generated code loads: cinterop's own stub naming, the package's parts
 * joined plus `stubs` — `io.example.m` → `ioexamplemstubs`.
 */
internal fun stubLibraryBaseName(packageName: String): String = packageName.split('.').joinToString("") + "stubs"
