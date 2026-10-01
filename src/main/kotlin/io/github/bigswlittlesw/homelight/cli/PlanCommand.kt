package io.github.bigswlittlesw.homelight.cli

import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation
import io.github.bigswlittlesw.homelight.application.isUnconfiguredDefault
import io.github.bigswlittlesw.homelight.config.ConfigurationLoader
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan
import io.github.bigswlittlesw.homelight.tui.launchTui
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.ParameterException
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.Spec
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(name = "plan", description = ["Show the filesystem actions required to converge configured relocations."])
internal class PlanCommand : Callable<Int> {
    @field:ParentCommand
    private lateinit var parent: HomeLightCommand

    @field:Option(names = ["--json"], description = ["Emit JSON."])
    private var json = false

    @field:Option(names = ["--no-color"], description = ["Disable terminal color."])
    private var noColor = false

    @field:Option(names = ["--source-path"], description = ["Override the source path for the first relocation."])
    private var sourcePath: Path? = null

    @field:Option(names = ["--target-path"], description = ["Override the target path for the first relocation."])
    private var targetPath: Path? = null

    @field:Spec
    private lateinit var spec: CommandSpec

    override fun call(): Int {
        val source = sourcePath
        val target = targetPath
        val override = when {
            source != null && target != null -> ConfigurationLoader.PathOverride(source, target)
            source == null && target == null -> null
            else -> throw ParameterException(spec.commandLine(), "--source-path and --target-path must be provided together")
        }

        val configPath = parent.config
        if (json) {
            val plan = if (isUnconfiguredDefault(configPath)) ReconciliationPlan(listOf(), listOf())
            else ConfigurationEvaluation().loadRequired(configPath, override).plan
            renderPlanJson(plan, spec.commandLine().out)
            return 0
        }

        return launchTui(configPath, parent.debugStepDelayMillis, spec.commandLine().err)
    }
}
