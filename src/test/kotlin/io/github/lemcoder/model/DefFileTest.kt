package io.github.lemcoder.model

import kotlin.test.Test
import kotlin.test.assertEquals

class DefFileTest {
    @Test
    fun `linker options of a platform library are read before the separator`() {
        val def = DefFile.parse(
            """
            headers = aaudio/AAudio.h
            package = aaudio
            linkerOpts = -laaudio  -llog
            ---
            linkerOpts = -lnot_a_property
            """.trimIndent()
        )
        assertEquals("aaudio", def.packageName)
        assertEquals(listOf("-laaudio", "-llog"), def.linkerOpts)
        assertEquals(emptyList(), def.staticLibraries)
    }
}
