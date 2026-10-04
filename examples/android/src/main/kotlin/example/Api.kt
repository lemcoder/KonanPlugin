package example

// Idiomatic API over the generated JNI bindings (build/generated/jvmInterop/kotlin/example/example.kt),
// which are named after the C functions.
fun add(a: Int, b: Int): Int = my_add(a, b)
fun scale(x: Double): Double = my_scale(x)
