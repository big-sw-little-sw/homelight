package io.github.bigswlittlesw.homelight.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.MissingOption
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.cooccurring
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.nullableFlag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation
import io.github.bigswlittlesw.homelight.application.isUnconfiguredDefault
import io.github.bigswlittlesw.homelight.config.ConfigurationLoader
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan
import io.github.bigswlittlesw.homelight.tui.launchTui
import java.io.PrintWriter

internal class PlanCommand(private val out: PrintWriter, private val err: PrintWriter) : ExitCodeCommand("plan") {
    private val shared by SharedOptions()
    private val json by option("--json", help = "Emit JSON.").nullableFlag().once { it ?: false }
    private val override by PathOverrideOptions().cooccurring()

    override fun help(context: Context) = "Show the filesystem actions required to converge configured relocations."

    override fun call(): Int {
        val settings = settings(shared)
        if (!json) {
            return launchTui(settings.config, settings.debugStepDelayMillis, err)
        }
        val plan = if (isUnconfiguredDefault(settings.config)) ReconciliationPlan(listOf(), listOf())
        else ConfigurationEvaluation().loadRequired(settings.config, override?.toPathOverride()).plan
        renderPlanJson(plan, out)
        return 0
    }
}

/** Both or neither: given one, Clikt reports the other as missing, a usage error. */
private class PathOverrideOptions : OptionGroup() {
    val sourcePath by option("--source-path", help = "Override the source path for the first relocation.").path()
        .once(required = true) { it ?: throw MissingOption(option) }
    val targetPath by option("--target-path", help = "Override the target path for the first relocation.").path()
        .once(required = true) { it ?: throw MissingOption(option) }

    fun toPathOverride() = ConfigurationLoader.PathOverride(sourcePath, targetPath)
}
