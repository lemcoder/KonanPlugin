package com.example.ffmapp

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import com.v7878.foreign.Arena
import com.v7878.foreign.ValueLayout.JAVA_INT
import example.example as mymath

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Calls into C through jextract's FFM bindings on PanamaPort (libmymath.so, no JNI stub).
        val text = buildString {
            appendLine("FFM via PanamaPort:")
            appendLine("my_add(2, 3)         = ${mymath.my_add(2, 3)}")
            appendLine("my_scale(4.0)        = ${mymath.my_scale(4.0)}")
            Arena.ofConfined().use { arena ->
                appendLine("my_length(\"kotlin\")  = ${mymath.my_length(arena.allocateFrom("kotlin"))}")
                appendLine("my_greeting()        = ${mymath.my_greeting().reinterpret(Long.MAX_VALUE).getString(0)}")
                appendLine("my_sum([1, 2, 3, 4]) = ${mymath.my_sum(arena.allocateFrom(JAVA_INT, 1, 2, 3, 4), 4)}")
                val buffer = arena.allocate(JAVA_INT, 3)
                mymath.my_fill(buffer, 3, 7)
                appendLine("my_fill -> ${buffer.toArray(JAVA_INT).toList()}")
            }
        }
        Log.i("KonanFfm", text)

        setContentView(TextView(this).apply {
            this.text = text
            textSize = 18f
            gravity = Gravity.CENTER
        })
    }
}
