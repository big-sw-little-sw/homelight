package io.github.bigswlittlesw.lighten.cli

import io.github.bigswlittlesw.lighten.application.resolveVersion
import picocli.CommandLine.IVersionProvider

// picocli creates the provider reflectively through its no-arg constructor, which is public on the JVM.
internal class LightenVersionProvider : IVersionProvider {
    override fun getVersion(): Array<String> = arrayOf("lighten " + resolveVersion())
}
