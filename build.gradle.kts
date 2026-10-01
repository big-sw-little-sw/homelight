plugins {
    application
    alias(libs.plugins.graalvm.native)
}

group = "io.github.bigswlittlesw"
version = "1.0-SNAPSHOT"

val mainClassName = "io.github.bigswlittlesw.homelight.cli.HomeLightCommand"

repositories {
    mavenCentral()
    // TamboUI snapshots.
    maven("https://central.sonatype.com/repository/maven-snapshots/") {
        mavenContent { snapshotsOnly() }
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

dependencies {
    implementation(platform(libs.tamboui.bom))
    implementation(libs.tamboui.toolkit)
    implementation(libs.tamboui.jline3.backend)
    implementation(libs.picocli)
    implementation(libs.snakeyaml)
    implementation(libs.jackson.core)
    // Generates the picocli native-image configuration under META-INF/native-image/picocli-generated.
    annotationProcessor(libs.picocli.codegen)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.compileJava {
    options.compilerArgs.add("-Aproject=${project.group}/${project.name}")
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("io/github/bigswlittlesw/homelight/version.properties") {
        expand("project" to mapOf("version" to version))
    }
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass = mainClassName
}

// `./gradlew nativeCompile` with a GraalVM 25 JDK as JAVA_HOME (or GRAALVM_HOME).
// Platform flags (static linking, libc, -march) come from NATIVE_IMAGE_OPTIONS; see ci/native/.
graalvmNative {
    binaries.named("main") {
        imageName = "homelight"
        mainClass = mainClassName
        buildArgs.addAll(
            "--no-fallback",
            "-H:+ReportExceptionStackTraces",
            // JLine's JNI provider calls System.load; silences the JDK restricted-method warning.
            "--enable-native-access=ALL-UNNAMED",
        )
    }
}
