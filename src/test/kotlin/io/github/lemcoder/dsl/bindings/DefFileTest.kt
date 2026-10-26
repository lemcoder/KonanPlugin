package io.github.lemcoder.dsl.bindings

import kotlin.test.Test
import kotlin.test.assertEquals

class DefFileTest {
    @Test
    fun `a def without a package is named after its file, as a package name`() {
        assertEquals("koinference", DefFile.defaultPackage(java.io.File("src/koinference.def")))
        assertEquals("koinference_llama", DefFile.defaultPackage(java.io.File("koinference-llama.def")))
        assertEquals("_3d", DefFile.defaultPackage(java.io.File("3d.def")))
    }

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
