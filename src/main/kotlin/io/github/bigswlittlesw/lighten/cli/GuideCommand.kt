package io.github.bigswlittlesw.homelight.cli

import io.github.bigswlittlesw.homelight.application.userGuide
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec
import java.util.concurrent.Callable

/** Prints the user guide that the Help screen shows, as Markdown, for reading without the TUI. */
@Command(name = "guide", description = ["Print the user guide."])
internal class GuideCommand : Callable<Int> {
    @Spec private lateinit var spec: CommandSpec

    override fun call(): Int {
        spec.commandLine().out.print(userGuide())
        spec.commandLine().out.flush()
        return 0
    }
}
