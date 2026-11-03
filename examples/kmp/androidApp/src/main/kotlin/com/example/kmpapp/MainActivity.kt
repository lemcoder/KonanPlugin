package com.example.kmpapp

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import com.example.shared.add
import com.example.shared.scale

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The shared module's common API, here over JNI into libmymathstubs.so.
        val text = buildString {
            appendLine("C from Kotlin Multiplatform:")
            appendLine("add(2, 3)  = ${add(2, 3)}")
            appendLine("scale(4.0) = ${scale(4.0)}")
        }

        setContentView(TextView(this).apply {
            this.text = text
            textSize = 20f
            gravity = Gravity.CENTER
        })
    }
}
