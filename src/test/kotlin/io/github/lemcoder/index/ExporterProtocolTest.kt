package io.github.lemcoder.index

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The command line between the plugin and the index exporter carries every request intact. */
class ExporterProtocolTest {
    private val requests = listOf(
        GeneratorRequest.Dump,
        GeneratorRequest.Jni(stubLibrary = "examplestubs"),
        GeneratorRequest.Jni(stubLibrary = "examplestubs", visibility = GeneratedVisibility.PUBLIC),
        GeneratorRequest.Jextract(library = "mymath"),
        GeneratorRequest.Jextract(library = "mymath", jextract = File("/opt/jextract/bin/jextract"), headerClass = "MyMath"),
    )

    @Test
    fun `every request survives the round trip`() {
        requests.forEach { request ->
            val invocation = ExporterInvocation(request, File("/tmp/out"), listOf("-def", "mymath.def", "-target", "macos_arm64"))
            assertEquals(invocation, ExporterInvocation.parse(invocation.toArgs().toTypedArray()))
        }
    }
}
