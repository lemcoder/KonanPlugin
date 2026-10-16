package io.github.lemcoder.dsl.config

import org.gradle.api.InvalidUserDataException
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigValueTest {
    @Test
    fun `an unset field says how to set it, in lookup order`() {
        val message = assertFailsWith<InvalidUserDataException> {
            ConfigValue(ConfigField.JEXTRACT, null, null, ":generateFfmMymath").require()
        }.message.orEmpty()
        assertContains(message, "konan.config.jextract is not set — :generateFfmMymath needs it.")
        assertContains(message, "1. build script         konan { config { jextract.set(file(\"/path/to/jextract\")) } }")
        assertContains(message, "2. gradle.properties    konan.config.jextract=/path/to/jextract")
        assertContains(message, "3. environment          KONAN_JEXTRACT=/path/to/jextract")
    }

    @Test
    fun `a value names where it came from and what is wrong with it`() {
        val relative = ConfigValue(ConfigField.CMAKE, "./cmake", ConfigSource.GRADLE_PROPERTY, ":cmakeBuild")
        assertEquals("is not an absolute path", relative.problem())
        assertContains(
            assertFailsWith<InvalidUserDataException> { relative.require() }.message.orEmpty(),
            "konan.config.cmake ./cmake (Gradle property konan.config.cmake) is not an absolute path — :cmakeBuild needs it.",
        )
        val directory = createTempDirectory("config").toFile()
        assertEquals("is not an executable file", ConfigValue(ConfigField.ZIG, directory.absolutePath, ConfigSource.ENVIRONMENT, ":z").problem())
        assertEquals("is not a directory", ConfigValue(ConfigField.KONAN_HOME, File(directory, "f").apply { writeText("") }.absolutePath, ConfigSource.DSL, ":k").problem())
        assertEquals(directory, ConfigValue(ConfigField.KONAN_HOME, directory.absolutePath, ConfigSource.DSL, ":k").require())
    }
}
