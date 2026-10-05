package io.github.bigswlittlesw.homelight.cli

import io.github.bigswlittlesw.homelight.domain.RelocationSourceState
import kotlinx.serialization.Serializable
import java.io.PrintWriter
import java.nio.file.Path
import java.util.Locale

internal fun renderStatusJson(snapshots: List<StatusSnapshot>, output: PrintWriter) {
    val relocations = snapshots.map { snapshot ->
        StatusJson(
            snapshot.sourcePath.toString(), snapshot.targetPath.toString(),
            snapshot.state.name.lowercase(Locale.ROOT),
        )
    }
    output.println(encodeJson(ConfiguredStatusJson.serializer(), ConfiguredStatusJson(JSON_SCHEMA, relocations)))
}

internal fun renderUnconfiguredStatusJson(config: Path, output: PrintWriter) {
    val status = UnconfiguredStatusJson(JSON_SCHEMA, false, config.toAbsolutePath().normalize().toString(), listOf())
    output.println(encodeJson(UnconfiguredStatusJson.serializer(), status))
}

internal data class StatusSnapshot(val sourcePath: Path, val targetPath: Path, val state: RelocationSourceState)

@Serializable
private data class StatusJson(val sourcePath: String, val targetPath: String, val state: String)

@Serializable
private data class ConfiguredStatusJson(val schema: Int, val relocations: List<StatusJson>)

@Serializable
private data class UnconfiguredStatusJson(
    val schema: Int, val configured: Boolean, val configPath: String, val relocations: List<StatusJson>,
)
