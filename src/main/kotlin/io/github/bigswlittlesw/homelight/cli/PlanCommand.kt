package io.github.bigswlittlesw.homelight.cli

import io.github.bigswlittlesw.homelight.application.ConfigurationEvaluation
import io.github.bigswlittlesw.homelight.application.isUnconfiguredDefault
import io.github.bigswlittlesw.homelight.config.ConfigurationLoader
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan
import io.github.bigswlittlesw.homelight.tui.TuiLauncher
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
    private var parent: HomeLightCommand? = null

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

    private fun config(): Path = parent?.config() ?: ConfigurationLoader.DEFAULT_PATH

    override fun call(): Int {
        if ((sourcePath == null) != (targetPath == null)) {
            throw ParameterException(spec.commandLine(), "--source-path and --target-path must be provided together")
        }
        val override = sourcePath?.let { source -> ConfigurationLoader.PathOverride(source, targetPath!!) }

        val configPath = config()
        if (json) {
            if (isUnconfiguredDefault(configPath)) {
                PlanRenderer().renderJson(ReconciliationPlan(listOf(), listOf()), spec.commandLine().out)
                return 0
            }
            val plan = ConfigurationEvaluation().loadRequired(configPath, override).plan
            PlanRenderer().renderJson(plan, spec.commandLine().out)
            return 0
        }

        return TuiLauncher.launchPlan(configPath, parent!!.debugStepDelayMillis(), spec.commandLine().err)
    }
}
