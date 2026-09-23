package example

// Idiomatic API the *user* writes on top of the generated low-level JNI bridges, which carry the
// names of the C functions they call. (See build/generated/jvmInterop/kotlin/.../example.kt.)
fun add(a: Int, b: Int): Int = my_add(a, b)
fun scale(x: Double): Double = my_scale(x)

fun main() {
    println("add(2, 3)    = ${add(2, 3)}")   // expect 5
    println("scale(4.0)   = ${scale(4.0)}")  // expect 40.0
}
