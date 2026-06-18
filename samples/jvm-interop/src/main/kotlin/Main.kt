import sample.mymath.my_add
import sample.mymath.my_scale

fun main() {
    // These call into the C library through the generated JNI bridges.
    println("my_add(2, 3)    = ${my_add(2, 3)}")     // expect 5
    println("my_scale(4.0)   = ${my_scale(4.0)}")    // expect 40.0
}
