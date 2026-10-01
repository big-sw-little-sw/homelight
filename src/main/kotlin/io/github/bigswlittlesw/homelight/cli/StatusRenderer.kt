package io.github.bigswlittlesw.homelight.cli

import io.github.bigswlittlesw.homelight.domain.RelocationSourceState
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.PrintWriter
import java.nio.file.Path
import java.util.Locale

/** Machine-readable JSON renderer for relocation status. */
internal class StatusRenderer {
    fun renderJson(snapshots: List<StatusSnapshot>, output: PrintWriter) {
        val relocations = snapshots.map { snapshot ->
            StatusJson(
                snapshot.sourcePath.toString(), snapshot.targetPath.toString(),
                snapshot.state.name.lowercase(Locale.getDefault()),
            )
        }
        output.println(encodeJson(ListSerializer(StatusJson.serializer()), relocations))
    }

    fun renderUnconfiguredJson(config: Path, output: PrintWriter) {
        val status = UnconfiguredStatusJson(false, config.toAbsolutePath().normalize().toString(), listOf())
        output.println(encodeJson(UnconfiguredStatusJson.serializer(), status))
    }
}

internal data class StatusSnapshot(val sourcePath: Path, val targetPath: Path, val state: RelocationSourceState)

@Serializable
private data class StatusJson(val sourcePath: String, val targetPath: String, val state: String)

@Serializable
private data class UnconfiguredStatusJson(
    val configured: Boolean, val configPath: String, val relocations: List<StatusJson>,
)
