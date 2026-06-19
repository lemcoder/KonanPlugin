package example

// Idiomatic API the *user* writes on top of the generated low-level JNI bridges.
// (See build/generated/jvmInterop/kotlin/.../example.kt for the generated `kniBridgeN` + their C signatures.)
fun add(a: Int, b: Int): Int = kniBridge0(a, b)        // C: my_add(a: Int, b: Int): Int
fun scale(x: Double): Double = kniBridge1(x)           // C: my_scale(x: Double): Double

fun main() {
    println("add(2, 3)    = ${add(2, 3)}")   // expect 5
    println("scale(4.0)   = ${scale(4.0)}")  // expect 40.0
}
