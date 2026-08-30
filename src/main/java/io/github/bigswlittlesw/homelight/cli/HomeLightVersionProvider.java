package io.github.bigswlittlesw.homelight.cli;

import picocli.CommandLine.IVersionProvider;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class HomeLightVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        return new String[] { "homelight " + resolveVersion() };
    }

    public static String resolveVersion() {
        try (InputStream stream = HomeLightVersionProvider.class.getResourceAsStream("/io/github/bigswlittlesw/homelight/version.properties")) {
            if (stream != null) {
                var properties = new Properties();
                properties.load(stream);
                String version = properties.getProperty("version");
                if (version != null && !version.isBlank() && !version.startsWith("${")) {
                    return version;
                }
            }
        } catch (IOException _) {
            // fallback when resource is unreadable
        }
        String implementationVersion = HomeLightVersionProvider.class.getPackage().getImplementationVersion();
        if (implementationVersion != null && !implementationVersion.isBlank()) {
            return implementationVersion;
        }
        return "unknown";
    }
}
