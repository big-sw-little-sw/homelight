package io.github.bigswlittlesw.homelight.reconcile

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.PrintWriter
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

/**
 * Another JVM that runs stale cleanup and probes locks, as a second homelight process would. File locks belong to a
 * process, so only another process can show whether a lock is really held.
 */
internal class ForeignStagingProcess : Closeable {
    private val process = ProcessBuilder(
        ProcessHandle.current().info().command().orElseThrow(), "-cp", System.getProperty("java.class.path"),
        "io.github.bigswlittlesw.homelight.reconcile.ForeignStagingProcessKt",
    ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val input = PrintWriter(process.outputStream, true)
    private val output = BufferedReader(InputStreamReader(process.inputStream))

    fun cleanStaleStaging(stagingRoot: Path) = check(send("clean $stagingRoot") == "cleaned")

    fun lockIsHeld(lock: Path): Boolean = send("lock $lock") == "held"

    private fun send(command: String): String {
        input.println(command)
        return checkNotNull(output.readLine()) { "foreign staging process exited" }
    }

    override fun close() {
        input.close()
        if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
    }
}

/** Reads `clean <staging root>` or `lock <lock file>` lines and answers each with one line. */
fun main() {
    for (line in generateSequence(::readLine)) {
        val (command, path) = line.split(" ", limit = 2)
        val reply = when (command) {
            "clean" -> {
                cleanStaleStaging(Path.of(path))
                "cleaned"
            }
            "lock" -> FileChannel.open(Path.of(path), StandardOpenOption.WRITE).use { channel ->
                channel.tryLock()?.use { "free" } ?: "held"
            }
            else -> "unknown command: $command"
        }
        println(reply)
        System.out.flush()
    }
}
