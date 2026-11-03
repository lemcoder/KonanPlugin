package com.example.nativeapp

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import com.v7878.foreign.Arena
import com.v7878.foreign.ValueLayout.JAVA_INT
import cppmath.cppmath as cpp

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = buildString {
            // C through JNI: libcmathstubs.so.
            appendLine("C, JNI:")
            appendLine("  c_add(2, 3)    = ${cmath.c_add(2, 3)}")
            appendLine("  c_greeting()   = ${cmath.c_greeting()}")

            // Rust through JNI: the crate's staticlib linked into librustmathstubs.so.
            appendLine("Rust, JNI:")
            appendLine("  rust_fib(20)   = ${rustmath.rust_fib(20)}")
            appendLine("  rust_count_vowels(\"Kotlin Native\") = ${rustmath.rust_count_vowels("Kotlin Native")}")

            // C++ through FFM on PanamaPort: libcppmath.so, no stub.
            appendLine("C++, FFM:")
            Arena.ofConfined().use { arena ->
                appendLine("  cpp_median([5, 1, 4, 2]) = ${cpp.cpp_median(arena.allocateFrom(JAVA_INT, 5, 1, 4, 2), 4)}")
                val reversed = cpp.cpp_reverse(arena.allocateFrom("kotlin")).reinterpret(Long.MAX_VALUE).getString(0)
                appendLine("  cpp_reverse(\"kotlin\")    = $reversed")
            }
        }
        Log.i("KonanExample", text)

        setContentView(TextView(this).apply {
            this.text = text
            textSize = 16f
            gravity = Gravity.CENTER
        })
    }
}
