package com.example.shared

// Over the generated JNI bindings (internal external funs named after the C functions).
actual fun add(a: Int, b: Int): Int = com.example.mymath.my_add(a, b)
actual fun scale(x: Double): Double = com.example.mymath.my_scale(x)
