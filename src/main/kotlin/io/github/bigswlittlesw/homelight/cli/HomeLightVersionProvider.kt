package io.github.bigswlittlesw.homelight.cli

import io.github.bigswlittlesw.homelight.application.resolveVersion
import picocli.CommandLine.IVersionProvider

// picocli creates the provider reflectively through its no-arg constructor, which is public on the JVM.
internal class HomeLightVersionProvider : IVersionProvider {
    override fun getVersion(): Array<String> = arrayOf("homelight " + resolveVersion())
}
