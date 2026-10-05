package io.github.bigswlittlesw.homelight.reconcile

import io.github.bigswlittlesw.homelight.config.Relocation
import io.github.bigswlittlesw.homelight.config.realSpelling
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.PrintWriter
import org.junit.jupiter.api.Assertions.assertEquals
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit

/**
 * Another JVM that publishes targets and probes locks, as a second homelight process would. File locks belong to a
 * process, so only another process can show whether a lock is really held.
 */
internal class ForeignStagingProcess : Closeable {
    private val process = ProcessBuilder(
        ProcessHandle.current().info().command().orElseThrow(), "-cp", System.getProperty("java.class.path"),
        "io.github.bigswlittlesw.homelight.reconcile.ForeignStagingProcessKt",
    ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val input = PrintWriter(process.outputStream, true)
    private val output = BufferedReader(InputStreamReader(process.inputStream))

    /** Runs [migrationOnly] there and returns the migration's message: `completed`, or why it failed. */
    fun migrate(source: Path, target: Path): String = send("migrate $source\t$target")

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

/** Reads `migrate <source>\t<target>` or `lock <lock file>` lines and answers each with one line. */
fun main() {
    for (line in generateSequence(::readLine)) {
        val (command, argument) = line.split(" ", limit = 2)
        val reply = when (command) {
            "migrate" -> {
                val (source, target) = argument.split("\t")
                ReconciliationExecutor().execute(migrationOnly(Path.of(source), Path.of(target)))
                    .relocations.single().actions.single().message
            }
            "lock" -> FileChannel.open(Path.of(argument), StandardOpenOption.WRITE).use { channel ->
                channel.tryLock()?.use { "free" } ?: "held"
            }
            else -> "unknown command: $command"
        }
        println(reply)
        System.out.flush()
    }
}

/**
 * A plan that only publishes [source] at [target], without review: its guards, not the planner, see the current
 * state, so it can run while another operation has the target in flight or has published it.
 */
internal fun migrationOnly(source: Path, target: Path) = ReconciliationPlan(
    listOf(RelocationPlan(Relocation(source, target), RelocationOutcome.CONVERGED,
        listOf(ReconciliationAction.MigrateDirectoryForPublication(source, target)), listOf())),
    listOf(),
)

/** Where [target]'s staged copy lives in [stagingRoot]; its lock file is the same name plus `.lock`. */
internal fun stagedCopy(stagingRoot: Path, target: Path): Path {
    val digest = MessageDigest.getInstance("SHA-256").digest(realSpelling(target).toString().toByteArray())
    return stagingRoot.resolve("operation-${HexFormat.of().formatHex(digest)}")
}

internal fun lockOf(copy: Path): Path = copy.resolveSibling("${copy.fileName}.lock")

/** After a migration, the default staging root holds only the target's lock file, which is never deleted. */
internal fun assertOnlyLockLeft(target: Path) {
    val staging = target.resolveSibling(".homelight-staging")
    Files.list(staging).use { entries -> assertEquals(listOf(lockOf(stagedCopy(staging, target))), entries.toList()) }
}
