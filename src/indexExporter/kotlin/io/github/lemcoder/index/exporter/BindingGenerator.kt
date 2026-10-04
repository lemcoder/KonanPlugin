package io.github.lemcoder.index.exporter

import org.jetbrains.kotlin.native.interop.indexer.NativeIndex
import java.io.File

/**
 * Turns the [NativeIndex] cinterop built into generated files.
 *
 * Runs in the exporter's process, next to the index, so it reads upstream's model directly: nothing about
 * the index crosses into the Gradle daemon, only what the generator writes.
 */
interface BindingGenerator {
    /** How the exporter's command line and the Gradle task name this generator. */
    val name: String

    /** Writes this generator's output for [index] into [outputDirectory], which starts out empty. */
    fun generate(index: NativeIndex, outputDirectory: File)
}

/** Every generator the exporter can run, by [BindingGenerator.name]. */
val bindingGenerators: Map<String, BindingGenerator> = listOf<BindingGenerator>(DumpGenerator).associateBy { it.name }
