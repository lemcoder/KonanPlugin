package com.example.jniapp

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import example.add
import example.scale

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Calls into native C through the generated JNI bridges (libexamplestubs.so).
        val text = buildString {
            appendLine("JNI from Kotlin/Native cinterop:")
            appendLine("my_add(2, 3)  = ${add(2, 3)}")
            appendLine("my_scale(4.0) = ${scale(4.0)}")
        }

        setContentView(TextView(this).apply {
            this.text = text
            textSize = 20f
            gravity = Gravity.CENTER
        })
    }
}
