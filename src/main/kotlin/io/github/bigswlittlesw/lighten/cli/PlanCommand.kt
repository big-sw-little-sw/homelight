package io.github.bigswlittlesw.lighten.cli

import io.github.bigswlittlesw.lighten.application.ConfigurationEvaluation
import io.github.bigswlittlesw.lighten.application.isUnconfiguredDefault
import io.github.bigswlittlesw.lighten.config.ConfigurationLoader
import io.github.bigswlittlesw.lighten.tui.launchTui
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
    @ParentCommand
    private lateinit var parent: LightenCommand

    @Option(names = ["--json"], description = ["Emit JSON."])
    private var json = false

    @Option(names = ["--source-path"], description = ["Override the source path for the first relocation."])
    private var sourcePath: Path? = null

    @Option(names = ["--target-path"], description = ["Override the target path for the first relocation."])
    private var targetPath: Path? = null

    @Spec
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
            val loaded = if (isUnconfiguredDefault(configPath)) null
            else ConfigurationEvaluation().loadRequired(configPath, override)
            renderPlanJson(loaded, spec.commandLine().out)
            return 0
        }

        return launchTui(configPath, parent.debugStepDelayMillis, spec.commandLine().err)
    }
}
