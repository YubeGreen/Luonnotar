package com.yubegreen.luonnotar.privileged

import java.io.IOException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal data class GuardianCommandResult(
    val command: List<String>,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val durationMs: Long
) {
    val success: Boolean get() = !timedOut && exitCode == 0

    fun summary(limit: Int = 240): String {
        val body = when {
            stdout.isNotBlank() -> stdout
            stderr.isNotBlank() -> stderr
            timedOut -> "timeout"
            else -> "exit=$exitCode"
        }.replace('\n', ' ').trim()
        return body.take(limit)
    }
}

internal class GuardianCommandRunner(
    private val defaultTimeoutMs: Long = 8_000L
) {
    fun run(vararg command: String, timeoutMs: Long = defaultTimeoutMs): GuardianCommandResult =
        run(command.toList(), timeoutMs)

    fun run(command: List<String>, timeoutMs: Long = defaultTimeoutMs): GuardianCommandResult {
        val started = System.nanoTime()
        return runCatching {
            val process = ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outThread = drain(process.inputStream.bufferedReader(), stdout)
            val errThread = drain(process.errorStream.bufferedReader(), stderr)
            val finished = process.waitFor(timeoutMs.coerceAtLeast(250L), TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(250L, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(750L, TimeUnit.MILLISECONDS)
                }
            }
            outThread.join(1_000L)
            errThread.join(1_000L)
            GuardianCommandResult(
                command = command,
                exitCode = if (finished) process.exitValue() else -1,
                stdout = stdout.toString().trim(),
                stderr = stderr.toString().trim(),
                timedOut = !finished,
                durationMs = elapsedMs(started)
            )
        }.getOrElse { error ->
            GuardianCommandResult(
                command = command,
                exitCode = -1,
                stdout = "",
                stderr = "${error.javaClass.simpleName}: ${error.message}",
                timedOut = false,
                durationMs = elapsedMs(started)
            )
        }
    }

    private fun drain(reader: BufferedReader, destination: StringBuilder) =
        thread(name = "luonnotar-command-drain", isDaemon = true) {
            try {
                reader.useLines { lines ->
                    lines.forEach { line ->
                        if (destination.length < MAX_CAPTURE_CHARS) {
                            destination.append(line).append('\n')
                        }
                    }
                }
            } catch (_: IOException) {
                /*
                 * 命令超时、进程被销毁或流被其他线程关闭时，
                 * readLine() 可能抛出 InterruptedIOException。
                 *
                 * drain 只是辅助输出线程，流关闭属于正常清理路径，
                 * 不能让异常逃出线程并终止整个特权引擎。
                 */
            }
        }

    private fun elapsedMs(started: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    companion object {
        private const val MAX_CAPTURE_CHARS = 128 * 1024
    }
}
