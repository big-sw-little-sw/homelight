package io.github.bigswlittlesw.homelight.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import io.github.bigswlittlesw.homelight.application.userGuide
import java.io.PrintWriter

/** Prints the user guide that the Help screen shows, as Markdown, for reading without the TUI. */
internal class GuideCommand(private val out: PrintWriter) : ExitCodeCommand("guide") {
    // Unused, but accepted like on every other command, as picocli's inherited options were.
    private val shared by SharedOptions()

    override fun help(context: Context) = "Print the user guide."

    override fun call(): Int {
        out.print(userGuide())
        out.flush()
        return 0
    }
}
