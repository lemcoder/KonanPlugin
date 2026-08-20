package example

// Idiomatic API the user writes on top of the generated low-level JNI bridges, which carry the names
// of the C functions they call (build/generated/jvmInterop/kotlin/example/example.kt).
fun add(a: Int, b: Int): Int = my_add(a, b)
fun scale(x: Double): Double = my_scale(x)
