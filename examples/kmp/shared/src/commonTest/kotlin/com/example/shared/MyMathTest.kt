package com.example.shared

import kotlin.test.Test
import kotlin.test.assertEquals

// Calls the C library: `iosSimulatorArm64Test` runs it on an iOS simulator.
class MyMathTest {
    @Test
    fun addsInC() = assertEquals(5, add(2, 3))

    @Test
    fun scalesInC() = assertEquals(40.0, scale(4.0))
}
