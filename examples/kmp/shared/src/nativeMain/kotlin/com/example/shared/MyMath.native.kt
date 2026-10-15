@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.example.shared

// Over KGP's cinterop binding, which embeds libmymath.a in the klib: linked into every binary.
actual fun add(a: Int, b: Int): Int = com.example.mymath.my_add(a, b)
actual fun scale(x: Double): Double = com.example.mymath.my_scale(x)
