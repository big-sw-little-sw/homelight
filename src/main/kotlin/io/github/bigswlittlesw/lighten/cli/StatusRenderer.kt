package io.github.bigswlittlesw.lighten.cli

import io.github.bigswlittlesw.lighten.fs.RelocationSourceState
import kotlinx.serialization.Serializable
import java.io.PrintWriter
import java.nio.file.Path
import java.util.Locale

/** An unconfigured default path has no relocations, so it is written with `configured` false and an empty list. */
internal fun renderStatusJson(
    config: Path, snapshots: List<StatusSnapshot>, output: PrintWriter, configured: Boolean = true,
) {
    val relocations = snapshots.map { snapshot ->
        RelocationStatusJson(
            snapshot.sourcePath.toString(), snapshot.targetPath.toString(),
            snapshot.state.name.lowercase(Locale.ROOT),
        )
    }
    val status = StatusJson(JSON_SCHEMA, configured, config.toAbsolutePath().normalize().toString(), relocations)
    output.println(encodeJson(StatusJson.serializer(), status))
}

internal data class StatusSnapshot(val sourcePath: Path, val targetPath: Path, val state: RelocationSourceState)

@Serializable
private data class StatusJson(
    val schema: Int, val configured: Boolean, val configPath: String, val relocations: List<RelocationStatusJson>,
)

@Serializable
private data class RelocationStatusJson(val sourcePath: String, val targetPath: String, val state: String)
