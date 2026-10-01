package io.github.bigswlittlesw.homelight.cli

import com.fasterxml.jackson.core.JsonFactory
import io.github.bigswlittlesw.homelight.domain.RelocationSourceState
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path
import java.util.Locale

/** Machine-readable JSON renderer for relocation status. */
internal class StatusRenderer {
    private val jsonFactory = JsonFactory()

    fun renderJson(snapshots: List<StatusSnapshot>, output: PrintWriter) {
        output.println(toJson(snapshots))
    }

    fun renderUnconfiguredJson(config: Path, output: PrintWriter) {
        val jsonOutput = StringWriter()
        try {
            jsonFactory.createGenerator(jsonOutput).use { generator ->
                generator.writeStartObject()
                generator.writeBooleanField("configured", false)
                generator.writeStringField("configPath", config.toAbsolutePath().normalize().toString())
                generator.writeArrayFieldStart("relocations")
                generator.writeEndArray()
                generator.writeEndObject()
            }
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to render unconfigured status as JSON", exception)
        }
        output.println(jsonOutput)
    }

    private fun toJson(snapshots: List<StatusSnapshot>): String {
        val json = StringWriter()
        try {
            jsonFactory.createGenerator(json).use { generator ->
                generator.writeStartArray()
                for (snapshot in snapshots) {
                    generator.writeStartObject()
                    generator.writeStringField("sourcePath", snapshot.sourcePath.toString())
                    generator.writeStringField("targetPath", snapshot.targetPath.toString())
                    generator.writeStringField("state", snapshot.state.name.lowercase(Locale.getDefault()))
                    generator.writeEndObject()
                }
                generator.writeEndArray()
            }
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to render status as JSON", exception)
        }
        return json.toString()
    }
}

@JvmRecord
internal data class StatusSnapshot(val sourcePath: Path, val targetPath: Path, val state: RelocationSourceState)
