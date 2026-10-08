package io.github.bigswlittlesw.lighten.cli

import io.github.bigswlittlesw.lighten.update.ReleaseVersion
import io.github.bigswlittlesw.lighten.update.SelfUpdate
import io.github.bigswlittlesw.lighten.update.currentInstallation
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.ParameterException
import picocli.CommandLine.Spec
import java.util.concurrent.Callable

/** Checks for, or installs, a newer release in place of the running binary. See [SelfUpdate]. */
@Command(name = "update", description = ["Update lighten to the latest release."])
internal class UpdateCommand : Callable<Int> {
    @Option(names = ["--check"], description = ["Show the installed and the latest version; change nothing."])
    private var check = false

    @Option(
        names = ["--version"], paramLabel = "<version>",
        description = ["Install this release, such as 1.2.3, even if it is older."],
    )
    private var version: String? = null

    @Spec private lateinit var spec: CommandSpec

    override fun call(): Int {
        val commandLine = spec.commandLine()
        val requested = version?.let {
            ReleaseVersion.parseOrNull(it.removePrefix("v"))
                ?: throw ParameterException(commandLine, "--version must be a release version, such as 1.2.3")
        }
        if (check && requested != null) {
            throw ParameterException(commandLine, "--check and --version cannot be used together")
        }
        val update = SelfUpdate(currentInstallation(), commandLine.out, commandLine.err)
        return if (check) update.check() else update.install(requested)
    }
}
