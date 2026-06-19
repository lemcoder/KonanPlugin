@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import mymath.my_add
import mymath.my_scale

// On Kotlin/Native the cinterop bindings are the actual API directly — no JNI involved.
fun main() {
    println("my_add(2, 3)  = ${my_add(2, 3)}")    // expect 5
    println("my_scale(4.0) = ${my_scale(4.0)}")   // expect 40.0
}
