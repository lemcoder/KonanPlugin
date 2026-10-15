package com.example.shared

// The API common code sees. Each platform implements it over its own generated binding.
expect fun add(a: Int, b: Int): Int
expect fun scale(x: Double): Double
