package io.github.lemcoder.util

import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.gradle.process.JavaExecSpec
import java.io.ByteArrayOutputStream

fun ExecOperations.execCapture(
    configure: ExecSpec.() -> Unit,
): ExecCaptureResult = capture { os ->
    exec {
        isIgnoreExitValue = true
        standardOutput = os
        errorOutput = os
        configure()
    }
}

fun ExecOperations.javaExecCapture(
    configure: JavaExecSpec.() -> Unit,
): ExecCaptureResult = capture { os ->
    javaexec {
        isIgnoreExitValue = true
        standardOutput = os
        errorOutput = os
        configure()
    }
}

private fun capture(run: (ByteArrayOutputStream) -> ExecResult): ExecCaptureResult {
    val (result, output) = ByteArrayOutputStream().use { os -> run(os) to os.toString() }

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
