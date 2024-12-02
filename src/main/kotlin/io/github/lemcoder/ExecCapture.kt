package io.github.lemcoder

import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import java.io.ByteArrayOutputStream

fun ExecOperations.execCapture(
    configure: ExecSpec.() -> Unit,
): ExecCaptureResult {

    val (result, output) = ByteArrayOutputStream().use { os ->
        exec {
            isIgnoreExitValue = true
            standardOutput = os
            errorOutput = os
            configure()
        } to os.toString()
    }

    return if (result.exitValue != 0) {
        ExecCaptureResult.Error(output, result)
    } else {
        ExecCaptureResult.Success(output, result)
    }
}


sealed class ExecCaptureResult(
    val output: String,
    private val result: ExecResult,
) : ExecResult by result {
    class Success(output: String, result: ExecResult) : ExecCaptureResult(output, result)
    class Error(output: String, result: ExecResult) : ExecCaptureResult(output, result)
}