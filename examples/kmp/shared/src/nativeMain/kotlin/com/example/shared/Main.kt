package com.example.shared

// The desktop executables' entry point (macosArm64, linuxX64); the iOS framework ignores it.
fun main() {
    println("C from Kotlin/Native:")
    println("add(2, 3)  = ${add(2, 3)}")
    println("scale(4.0) = ${scale(4.0)}")
}
