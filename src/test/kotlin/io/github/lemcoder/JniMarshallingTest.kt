package io.github.lemcoder

import io.github.lemcoder.util.ParamKind
import io.github.lemcoder.util.marshalStub
import io.github.lemcoder.util.parseBridgeKinds
import io.github.lemcoder.util.parseBridgeNames
import io.github.lemcoder.util.stripCinterop
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the marshalling transforms, over cinterop output for a header that covers the shapes
 * that matter: a plain function, a `const char*` return, a string argument, a struct return, a
 * struct-by-value argument, and primitive out-buffers.
 */
class JniMarshallingTest {

    private val rawKotlin = """
        @file:JvmName("probe")
        package probe

        import kotlinx.cinterop.*

        @ExperimentalForeignApi
        fun koi_backend_init(): Unit {
            return kniBridge0()
        }

        @ExperimentalForeignApi
        fun koi_model_load_mem(data: CValuesRef<*>?, size: Int): CPointer<KoiModel>? {
            memScoped {
                return interpretCPointer<KoiModel>(kniBridge9(data?.getPointer(memScope).rawValue, size))
            }
        }

        @ExperimentalForeignApi
        fun koi_system_info(): CPointer<ByteVar>? {
            return interpretCPointer<ByteVar>(kniBridge1())
        }

        @ExperimentalForeignApi
        fun koi_model_load(path: String?): CPointer<KoiModel>? {
            memScoped {
                return interpretCPointer<KoiModel>(kniBridge2(path?.cstr?.getPointer(memScope).rawValue))
            }
        }

        @ExperimentalForeignApi
        fun koi_default_session_params(): CValue<KoiSessionParams> {
            val kniRetVal = nativeHeap.alloc<KoiSessionParams>()
            try {
                kniBridge3(kniRetVal.rawPtr)
                return kniRetVal.readValue()
            } finally { nativeHeap.free(kniRetVal) }
        }

        @ExperimentalForeignApi
        fun koi_session_create(model: CValuesRef<KoiModel>?, params: CValue<KoiSessionParams>): CPointer<KoiSession>? {
            memScoped {
                return interpretCPointer<KoiSession>(kniBridge4(model?.getPointer(memScope).rawValue, params.getPointer(memScope).rawValue))
            }
        }

        @ExperimentalForeignApi
        fun koi_embed(session: CValuesRef<KoiSession>?, text: String?, out_buf: CValuesRef<FloatVar>?, buf_size: Int): Int {
            memScoped {
                return kniBridge5(session?.getPointer(memScope).rawValue, text?.cstr?.getPointer(memScope).rawValue, out_buf?.getPointer(memScope).rawValue, buf_size)
            }
        }
        private external fun kniBridge0(): Unit
        private external fun kniBridge1(): NativePtr
        private external fun kniBridge2(p0: NativePtr): NativePtr
        private external fun kniBridge3(p0: NativePtr): Unit
        private external fun kniBridge4(p0: NativePtr, p1: NativePtr): NativePtr
        private external fun kniBridge5(p0: NativePtr, p1: NativePtr, p2: NativePtr, p3: Int): Int
        private val loadLibrary = loadKonanLibrary("probestubs")
    """.trimIndent()

    private val rawC = """
        #include <jni.h>

        JNIEXPORT void JNICALL Java_probe_probe_kniBridge0 (JNIEnv* jniEnv, jclass jclss) {
            koi_backend_init();
        }
        JNIEXPORT jlong JNICALL Java_probe_probe_kniBridge1 (JNIEnv* jniEnv, jclass jclss) {
            return (jlong)koi_system_info();
        }
        JNIEXPORT jlong JNICALL Java_probe_probe_kniBridge2 (JNIEnv* jniEnv, jclass jclss, jlong p0) {
            return (jlong)koi_model_load((char*)p0);
        }
        JNIEXPORT void JNICALL Java_probe_probe_kniBridge3 (JNIEnv* jniEnv, jclass jclss, jlong p0) {
            KoiSessionParams kniStructResult = koi_default_session_params();
            memcpy((void*) p0, &kniStructResult, sizeof(kniStructResult));
        }
        JNIEXPORT jlong JNICALL Java_probe_probe_kniBridge4 (JNIEnv* jniEnv, jclass jclss, jlong p0, jlong p1) {
            return (jlong)koi_session_create((struct KoiModel*)p0, *(KoiSessionParams*)p1);
        }
        JNIEXPORT jint JNICALL Java_probe_probe_kniBridge5 (JNIEnv* jniEnv, jclass jclss, jlong p0, jlong p1, jlong p2, jint p3) {
            return (jint)koi_embed((struct KoiSession*)p0, (char*)p1, (float*)p2, p3);
        }
    """.trimIndent()

    private val kinds = parseBridgeKinds(rawKotlin)

    @Test
    fun `classifies strings, buffers and structs`() {
        assertEquals(listOf(ParamKind.STRING), kinds[2])
        assertEquals(listOf(ParamKind.BYTE_ARRAY), kinds[3], "struct returns are written through an out pointer")
        assertEquals(listOf(ParamKind.RAW, ParamKind.BYTE_ARRAY), kinds[4], "opaque handle stays an address")
        assertEquals(listOf(ParamKind.RAW, ParamKind.STRING, ParamKind.FLOAT_ARRAY, ParamKind.RAW), kinds[5])
    }

    @Test
    fun `a const void buffer crosses as a byte array`() {
        // load(const void* data, int size) is a common shape; cinterop writes it CValuesRef<*>.
        // Leaving it as an address would make the caller find off-heap memory for a ByteArray.
        val kinds = parseBridgeKinds(rawKotlin)

        assertEquals(listOf(ParamKind.BYTE_ARRAY, ParamKind.RAW), kinds[9])
    }

    @Test
    fun `bridges with nothing to marshal are left alone`() {
        assertFalse(kinds.containsKey(0))
        assertFalse(kinds.containsKey(1), "a const char* return is read via kniCString, not marshalled here")
    }

    @Test
    fun `kotlin declarations take the marshalled types`() {
        val kotlin = stripCinterop(rawKotlin, kinds)

        assertContains(kotlin, "external fun kniBridge2(p0: String?): Long")
        assertContains(kotlin, "external fun kniBridge3(p0: ByteArray?): Unit")
        assertContains(kotlin, "external fun kniBridge4(p0: Long, p1: ByteArray?): Long")
        assertContains(kotlin, "external fun kniBridge5(p0: Long, p1: String?, p2: FloatArray?, p3: Int): Int")
        assertContains(kotlin, "external fun kniCString(ptr: Long): String?")
    }

    @Test
    fun `internal bindings carry a JvmName so JNI still resolves them`() {
        val kotlin = stripCinterop(rawKotlin, kinds, internalBindings = true)

        // Kotlin mangles internal functions on the JVM (kniBridge2${'$'}module); JNI looks the symbol up
        // by the unmangled name, so every bridge needs the annotation.
        assertContains(kotlin, "@JvmName(\"kniBridge2\")\ninternal external fun kniBridge2(p0: String?): Long")
        assertContains(kotlin, "@JvmName(\"kniCString\")\ninternal external fun kniCString(ptr: Long): String?")
    }

    @Test
    fun `bindings are public unless asked otherwise`() {
        val kotlin = stripCinterop(rawKotlin, kinds)

        assertContains(kotlin, "external fun kniBridge2(p0: String?): Long")
        assertFalse(kotlin.contains("internal external"))
        assertFalse(kotlin.contains("@JvmName(\"kniBridge"))
    }

    // ---- naming ----------------------------------------------------------------------------

    @Test
    fun `each bridge is named for the C function its wrapper calls`() {
        val names = parseBridgeNames(rawKotlin)

        assertEquals("koi_backend_init", names[0])
        assertEquals("koi_system_info", names[1])
        assertEquals("koi_model_load", names[2])
        assertEquals("koi_default_session_params", names[3])
        assertEquals("koi_session_create", names[4])
        assertEquals("koi_embed", names[5])
        assertEquals("koi_model_load_mem", names[9])
    }

    @Test
    fun `named declarations carry the name as their JvmName too`() {
        val kotlin = stripCinterop(rawKotlin, kinds, internalBindings = true, names = parseBridgeNames(rawKotlin))

        assertContains(kotlin, "@JvmName(\"koi_model_load\")\ninternal external fun koi_model_load(p0: String?): Long")
        assertContains(kotlin, "/** C: koi_model_load(path: String?): CPointer<KoiModel>? */")
        assertFalse(kotlin.contains("external fun kniBridge"), "no bridge should be left numbered here")
    }

    @Test
    fun `the JNI symbol escapes the underscores in the name`() {
        val c = marshalStub(rawC, kinds, parseBridgeNames(rawKotlin))

        // Java_<pkg>_<class>_<method>, so an underscore inside the method name has to become _1 or the
        // symbol reads as a different class entirely and the binding resolves as unimplemented.
        assertContains(c, "Java_probe_probe_koi_1model_1load ")
        assertContains(c, "Java_probe_probe_koi_1embed ")
        // A bridge with nothing to marshal is renamed as well, or its symbol stops matching @JvmName.
        assertContains(c, "Java_probe_probe_koi_1backend_1init ")
        assertFalse(c.contains("kniBridge"), "every symbol should have been renamed")
    }

    @Test
    fun `a name two bridges share is left numbered`() {
        val clashing = rawKotlin.replace("fun koi_system_info(", "fun koi_model_load(")
        val names = parseBridgeNames(clashing)

        assertFalse(names.containsKey(1), "koi_model_load now names two bridges, so neither may take it")
        assertFalse(names.containsKey(2))
        assertEquals("koi_embed", names[5], "unrelated bridges keep their names")
    }

    @Test
    fun `without names the bridges stay numbered`() {
        val kotlin = stripCinterop(rawKotlin, kinds, internalBindings = true)
        val c = marshalStub(rawC, kinds)

        assertContains(kotlin, "internal external fun kniBridge2(p0: String?): Long")
        assertContains(c, "Java_probe_probe_kniBridge2 ")
    }

    @Test
    fun `without kinds every parameter stays an address`() {
        val kotlin = stripCinterop(rawKotlin)

        assertContains(kotlin, "external fun kniBridge2(p0: Long): Long")
        assertFalse(kotlin.contains("kniCString"), "the helper is only emitted alongside marshalling")
    }

    @Test
    fun `strings are acquired and released around the call`() {
        val c = marshalStub(rawC, kinds)

        assertContains(c, "Java_probe_probe_kniBridge2 (JNIEnv* jniEnv, jclass jclss, jstring j0)")
        assertContains(c, "const char* p0 = j0 ? (*jniEnv)->GetStringUTFChars(jniEnv, j0, 0) : 0;")
        assertContains(c, "jlong kniResult = (jlong)koi_model_load((char*)p0);")
        assertContains(c, "if (j0) (*jniEnv)->ReleaseStringUTFChars(jniEnv, j0, p0);")
        assertContains(c, "    return kniResult;")
    }

    @Test
    fun `buffers are released in reverse order, after the result is captured`() {
        val c = marshalStub(rawC, kinds).substringAfter("kniBridge5 (")

        assertContains(c, "jlong p0, jstring j1, jfloatArray j2, jint p3)")
        assertTrue(
            c.indexOf("GetStringUTFChars") < c.indexOf("GetFloatArrayElements"),
            "strings must be acquired before array elements",
        )
        assertTrue(
            c.indexOf("ReleaseFloatArrayElements") < c.indexOf("ReleaseStringUTFChars"),
            "releases run in reverse acquisition order",
        )
        assertTrue(c.indexOf("kniResult =") < c.indexOf("ReleaseFloatArrayElements"))
    }

    @Test
    fun `void bridges release without a result variable`() {
        val c = marshalStub(rawC, kinds).substringAfter("kniBridge3 (").substringBefore("JNIEXPORT")

        assertContains(c, "jbyteArray j0)")
        assertContains(c, "memcpy((void*) p0, &kniStructResult, sizeof(kniStructResult));")
        assertContains(c, "if (j0) (*jniEnv)->ReleaseByteArrayElements(jniEnv, j0, p0, 0);")
        assertFalse(c.contains("kniResult"))
    }

    @Test
    fun `unmarshalled bridges keep their generated body and the helper is appended`() {
        val c = marshalStub(rawC, kinds)

        assertContains(c, "Java_probe_probe_kniBridge0 (JNIEnv* jniEnv, jclass jclss) {")
        assertContains(c, "return (jlong)koi_system_info();")
        assertContains(c, "JNIEXPORT jstring JNICALL Java_probe_probe_kniCString (JNIEnv* jniEnv, jclass jclss, jlong p0)")
        assertContains(c, "return p0 ? (*jniEnv)->NewStringUTF(jniEnv, (const char*)p0) : 0;")
    }
}
