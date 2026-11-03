package example

// The generated bindings (build/generated/jvmInterop/mymath/kotlin/example/example.kt) are named after
// the C functions, with strings and primitive buffers marshalled.
fun main() {
    println("my_add(2, 3)         = ${my_add(2, 3)}")                     // 5
    println("my_scale(4.0)        = ${my_scale(4.0)}")                    // 40.0
    println("my_length(\"kotlin\")  = ${my_length("kotlin")}")             // 6
    println("my_greeting()        = ${my_greeting()}")                    // hello from C
    println("my_sum([1, 2, 3, 4]) = ${my_sum(intArrayOf(1, 2, 3, 4), 4)}") // 10

    val buffer = IntArray(3)
    my_fill(buffer, buffer.size, 7)
    println("my_fill -> ${buffer.toList()}")                               // [7, 7, 7]
}
