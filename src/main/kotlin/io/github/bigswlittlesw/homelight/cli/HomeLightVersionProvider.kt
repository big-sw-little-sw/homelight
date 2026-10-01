package io.github.bigswlittlesw.homelight.cli

import io.github.bigswlittlesw.homelight.config.isJavaBlank
import picocli.CommandLine.IVersionProvider
import java.io.IOException
import java.util.Properties

class HomeLightVersionProvider : IVersionProvider {

    override fun getVersion(): Array<String> = arrayOf("homelight " + resolveVersion())

    companion object {
        @JvmStatic
        fun resolveVersion(): String {
            try {
                HomeLightVersionProvider::class.java
                    .getResourceAsStream("/io/github/bigswlittlesw/homelight/version.properties").use { stream ->
                        if (stream != null) {
                            val properties = Properties()
                            properties.load(stream)
                            val version: String? = properties.getProperty("version")
                            if (version != null && !version.isJavaBlank() && !version.startsWith("\${")) {
                                return version
                            }
                        }
                    }
            } catch (_: IOException) {
                // fallback when resource is unreadable
            }
            val implementationVersion: String? = HomeLightVersionProvider::class.java.`package`.implementationVersion
            if (implementationVersion != null && !implementationVersion.isJavaBlank()) {
                return implementationVersion
            }
            return "unknown"
        }
    }
}
