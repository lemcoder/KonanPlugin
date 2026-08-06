package example

// Idiomatic API the user writes on top of the generated low-level JNI bridges
// (build/generated/jvmInterop/kotlin/example/example.kt).
fun add(a: Int, b: Int): Int = kniBridge0(a, b)        // C: my_add(a: Int, b: Int): Int
fun scale(x: Double): Double = kniBridge1(x)           // C: my_scale(x: Double): Double
